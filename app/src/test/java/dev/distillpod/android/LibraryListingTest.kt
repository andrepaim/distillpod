package dev.distillpod.android

import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.features.LibraryListing
import dev.distillpod.android.features.libraryListing
import dev.distillpod.android.features.libraryListings
import dev.distillpod.android.features.feedProblemKey
import dev.distillpod.android.features.undismissedFeedProblems
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
        // Latest lists followed shows only (#266); the unfollowed show's retained episodes stay in the library.
        assertEquals(listOf("new", "half", "done", "v2", "v1"), listing.visible.map { it.id })
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
    }

    @Test fun `unfollowing a show removes its episodes from Latest only, and following again restores them (#266)`() {
        val unfollowed = listOf(followed.copy(subscribed = false), other, channel)
        val latest = libraryListing(unfollowed, episodes, "all", null, null)
        // Downloaded, partly played and finished episodes of the unfollowed show all leave Latest; its count is its rows.
        assertEquals(listOf("v2", "v1"), latest.visible.map { it.id })
        // Retained media stays listed and playable under Downloaded and In progress.
        val retained = episodes.map { if (it.id == "done") it.copy(localFile = "/media/done") else it }
        assertEquals(listOf("done", "offline"), libraryListing(unfollowed, retained, "downloads", null, null).visible.map { it.id })
        assertEquals(listOf("half", "played"), libraryListing(unfollowed, retained, "progress", null, null).visible.map { it.id })
        // The library still holds them: the filters stay and the show's own page lists everything.
        assertEquals(6, latest.episodeCount)
        assertEquals(listOf("new", "half", "done"), libraryListing(unfollowed, episodes, "all", "p1", null).visible.map { it.id })
        // Following again restores them to Latest.
        val refollowed = libraryListing(listOf(followed, other.copy(subscribed = true), channel), episodes, "all", null, null)
        assertEquals(listOf("new", "half", "done", "offline", "played", "stranger", "v2", "v1"), refollowed.visible.map { it.id })
    }

    @Test fun `continue listening lists the resume episode first, then other unfinished ones by last played`() {
        assertEquals(listOf("played", "half"), libraryListing(podcasts, episodes, "all", null, null).continueListening.map { it.id })
        // The loaded episode leads, even a finished one, and is not repeated.
        assertEquals(listOf("done", "played", "half"), libraryListing(podcasts, episodes, "all", null, "done").continueListening.map { it.id })
        assertEquals(listOf("half", "played"), libraryListing(podcasts, episodes, "all", null, "half").continueListening.map { it.id })
        // At most five, most recently played first.
        val many = (1..8).map { EpisodeEntity("m$it", "p1", "m$it", "M$it", "https://example.test/m$it", positionMs = 1_000, lastPlayedAt = 100L + it) }
        assertEquals(listOf("m8", "m7", "m6", "m5", "m4"), libraryListing(podcasts, many, "all", null, null).continueListening.map { it.id })
        assertTrue(libraryListing(podcasts, episodes.filter { it.positionMs == 0L }, "all", null, null).continueListening.isEmpty())
    }

    @Test fun `continue listening shows the episode loaded in the player, even when it is finished (#146)`() {
        // The mini-player shows the loaded episode; the card must never show a different one.
        assertEquals("done", libraryListing(podcasts, episodes, "all", null, "done").resume?.id)
        // Also for an episode outside the Library's collection (an unfollowed show, never played here).
        assertEquals("stranger", libraryListing(podcasts, episodes, "all", null, "stranger").resume?.id)
        // A stale player id that no longer matches a stored episode falls back to the last played one.
        assertEquals("played", libraryListing(podcasts, episodes, "all", null, "removed").resume?.id)
    }

    @Test fun `feed problems count followed shows on the Library and only the open show on its page (#146)`() {
        val failing = listOf(followed.copy(error = "Couldn't reach the feed."), other.copy(error = "Old error"), channel)
        // An unfollowed show is never refreshed, so its old error must not keep the notice up.
        assertEquals(listOf("p1"), libraryListing(failing, episodes, "all", null, null).feedProblems.map { it.id })
        assertEquals(listOf("p2"), libraryListing(failing, episodes, "all", "p2", null).feedProblems.map { it.id })
        assertEquals(emptyList<String>(), libraryListing(failing, episodes, "all", "yt", null).feedProblems.map { it.id })
        assertEquals(emptyList<String>(), libraryListing(podcasts, episodes, "all", null, null).feedProblems.map { it.id })
    }

    @Test fun `a dismissed refresh warning stays hidden until that show refreshes or another show fails (#264)`() {
        val a = followed.copy(error = "Couldn't reach the feed.", refreshedAt = 100)
        val b = channel.copy(error = "HTTP 404")
        val dismissed = setOf(a, b).mapTo(HashSet(), ::feedProblemKey)
        assertEquals(listOf(a, b), undismissedFeedProblems(listOf(a, b), emptySet()))
        // Failing again on retry, with the same or another reason, is the same failure.
        assertTrue(undismissedFeedProblems(listOf(a.copy(error = "HTTP 500"), b), dismissed).isEmpty())
        // A show that refreshed successfully and then failed again is a new failure.
        assertEquals(listOf("p1"), undismissedFeedProblems(listOf(a.copy(refreshedAt = 200), b), dismissed).map { it.id })
        // So is a show that was not failing when the warning was dismissed.
        val c = other.copy(subscribed = true, error = "Unreadable feed")
        assertEquals(listOf("p2"), undismissedFeedProblems(listOf(a, b, c), dismissed).map { it.id })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `Home's shows follow pin changes and new publications but not empty refreshes (#276)`() = runTest {
        val a = PodcastEntity("a", "https://example.test/a", "Alpha")
        val b = PodcastEntity("b", "https://example.test/b", "Bravo")
        val c = PodcastEntity("c", "https://example.test/c", "Charlie")
        val shows = MutableStateFlow(listOf(a, b, c))
        val library = MutableStateFlow(listOf(
            EpisodeEntity("a1", "a", "a1", "A1", "https://example.test/a1", publishedAt = 100),
            EpisodeEntity("b1", "b", "b1", "B1", "https://example.test/b1", publishedAt = 300),
            EpisodeEntity("c1", "c", "c1", "C1", "https://example.test/c1", publishedAt = 200)))
        val pins = MutableStateFlow(emptySet<String>())
        val state = libraryListings(shows, library, MutableStateFlow("all"), MutableStateFlow(PlaybackState()),
            MutableStateFlow("library"), pins).stateIn(backgroundScope + UnconfinedTestDispatcher(testScheduler), SharingStarted.Eagerly, null)
        fun order() = state.value!!.subscribed.map { it.id }
        assertEquals(listOf("b", "c", "a"), order())
        // Pinning in Shows moves the show ahead on Home; unpinning returns it.
        pins.value = setOf(a.feedUrl); advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), order())
        pins.value = emptySet(); advanceUntilIdle()
        assertEquals(listOf("b", "c", "a"), order())
        // A refresh that stores no new publication (only refreshedAt changes) does not promote the show.
        shows.value = listOf(a.copy(refreshedAt = 9_000), b, c); advanceUntilIdle()
        assertEquals(listOf("b", "c", "a"), order())
        // A refresh that adds a newer episode moves it to its place.
        library.value = library.value + EpisodeEntity("a2", "a", "a2", "A2", "https://example.test/a2", publishedAt = 250); advanceUntilIdle()
        assertEquals(listOf("b", "a", "c"), order())
        // A show page keeps the stored order: only Home shows the row.
        assertEquals(listOf("a", "b", "c"), libraryListing(shows.value, library.value, "all", "a", null, setOf(c.feedUrl)).subscribed.map { it.id })
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
