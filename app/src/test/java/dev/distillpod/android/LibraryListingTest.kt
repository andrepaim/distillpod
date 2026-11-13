package dev.distillpod.android

import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.features.LibraryListing
import dev.distillpod.android.features.libraryListing
import dev.distillpod.android.features.libraryListings
import dev.distillpod.android.playback.PlaybackState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.plus
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** #141: Library filtering runs outside composition and ignores live player position. */
class LibraryListingTest {
    private val followed = PodcastEntity("p1", "https://example.test/one", "One")
    private val other = PodcastEntity("p2", "https://example.test/two", "Two", subscribed = false)
    private val channel = PodcastEntity("yt", "https://www.youtube.com/playlist?list=PL1234567890", "Channel")
    private val podcasts = listOf(followed, other, channel)
    private val episodes = listOf(
        EpisodeEntity("new", "p1", "new", "New", "https://example.test/new", publishedAt = 50),
        EpisodeEntity("half", "p1", "half", "Half", "https://example.test/half", publishedAt = 40, positionMs = 30_000, lastPlayedAt = 9),
        EpisodeEntity("done", "p1", "done", "Done", "https://example.test/done", publishedAt = 30, positionMs = 30_000, completed = true, lastPlayedAt = 10),
        EpisodeEntity("offline", "p2", "offline", "Offline", "https://example.test/offline", publishedAt = 20, localFile = "/media/offline"),
        EpisodeEntity("played", "p2", "played", "Played", "https://example.test/played", publishedAt = 10, positionMs = 5_000, lastPlayedAt = 12),
        EpisodeEntity("stranger", "p2", "stranger", "Stranger", "https://example.test/stranger", publishedAt = 5),
        EpisodeEntity("v2", "yt", "v2", "Second", "https://www.youtube.com/watch?v=bbbbbbbbbbb", publishedAt = 60, sourceOrder = 2),
        EpisodeEntity("v1", "yt", "v1", "First", "https://www.youtube.com/watch?v=aaaaaaaaaaa", publishedAt = 1, sourceOrder = 1),
    )

    @Test fun `library collects followed episodes and unfollowed ones that were downloaded or played`() {
        val listing = libraryListing(podcasts, episodes, "all", null, null)
        assertEquals(listOf("new", "half", "done", "offline", "played", "v2", "v1"), listing.visible.map { it.id })
        assertEquals(7, listing.episodeCount)
        assertEquals(listOf("p1", "yt"), listing.subscribed.map { it.id })
        assertNull(listing.selected)
        // The most recently played unfinished episode resumes when nothing is current.
        assertEquals("played", listing.resume?.id)
    }

    @Test fun `filters and current episode resume`() {
        assertEquals(listOf("offline"), libraryListing(podcasts, episodes, "downloads", null, null).visible.map { it.id })
        assertEquals(listOf("half", "played"), libraryListing(podcasts, episodes, "progress", null, null).visible.map { it.id })
        assertEquals("new", libraryListing(podcasts, episodes, "all", null, "new").resume?.id)
        // A finished current episode does not replace the resume candidate.
        assertEquals("played", libraryListing(podcasts, episodes, "all", null, "done").resume?.id)
    }

    @Test fun `show page lists only that show and keeps source order for video channels`() {
        val show = libraryListing(podcasts, episodes, "all", "p2", null)
        assertEquals(other, show.selected)
        assertEquals(listOf("offline", "played", "stranger"), show.visible.map { it.id })
        assertEquals(3, show.episodeCount)
        assertEquals(listOf("v1", "v2"), libraryListing(podcasts, episodes, "all", "yt", null).visible.map { it.id })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `live position ticks do not recompute the listing`() = runTest {
        val player = MutableStateFlow(PlaybackState("half", positionMs = 0, playing = true))
        val emitted = mutableListOf<LibraryListing?>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            libraryListings(MutableStateFlow(podcasts), MutableStateFlow(episodes), MutableStateFlow("all"), player,
                MutableStateFlow("library")).toList(emitted)
        }
        repeat(20) { player.value = player.value.copy(positionMs = it * 500L) }
        advanceUntilIdle()
        assertEquals(1, emitted.size)
        player.value = player.value.copy(episodeId = "new")
        advanceUntilIdle()
        assertEquals(2, emitted.size)
        assertEquals("new", emitted.last()?.resume?.id)
        job.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `listing follows the top route and filter before control returns`() = runTest {
        val route = MutableStateFlow("library")
        val filter = MutableStateFlow("all")
        // The ViewModel shares this eagerly on Main.immediate; an unconfined collector has the same
        // ordering: a navigation or filter change is visible as soon as the setter returns, so the
        // page renders rows for the state it was opened with, without extra dispatches.
        val state = libraryListings(MutableStateFlow(podcasts), MutableStateFlow(episodes), filter,
            MutableStateFlow(PlaybackState()), route).stateIn(backgroundScope + UnconfinedTestDispatcher(testScheduler), SharingStarted.Eagerly, null)
        assertNull(state.value?.podcastId)
        assertEquals(7, state.value?.episodeCount)
        route.value = "podcast:p2"
        assertEquals("p2", state.value?.podcastId)
        assertEquals(listOf("offline", "played", "stranger"), state.value?.visible?.map { it.id })
        filter.value = "downloads"
        assertEquals("downloads", state.value?.filter)
        assertEquals(listOf("offline"), state.value?.visible?.map { it.id })
        route.value = "settings"
        assertNull("other screens do not filter the library", state.value)
    }
}
