package dev.distillpod.android

import dev.distillpod.android.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ShowLibraryTest {
    private fun show(id: String, title: String = id) = PodcastEntity(id, "https://example.test/$id", title)
    private fun episode(id: String, show: String, mime: String? = "audio/mpeg", published: Long = 0, played: Long = 0,
                        completed: Boolean = false, position: Long = 0) =
        EpisodeEntity(id, show, id, id, "https://example.test/$id", mimeType = mime, publishedAt = published,
            lastPlayedAt = played, completed = completed, positionMs = position)

    @Test fun `mixed media appears in both filters while empty unknown shows remain in All`() {
        val rows = summarizeShows(listOf(show("audio"), show("video"), show("mixed"), show("empty"),
            show("youtube").copy(feedUrl = "https://www.youtube.com/@example/videos"), show("hidden").copy(subscribed = false)),
            listOf(episode("a", "audio", null), episode("v", "video", "VIDEO/MP4"),
                episode("m1", "mixed"), episode("m2", "mixed", "video/mp4"), episode("orphan", "missing")))
        assertEquals(listOf("audio", "empty", "mixed", "video", "youtube"), ids(rows))
        assertEquals(listOf("audio", "mixed"), ids(rows, ShowPreferences(format = ShowFormat.AUDIO)))
        assertEquals(listOf("mixed", "video", "youtube"), ids(rows, ShowPreferences(format = ShowFormat.VIDEO)))
        assertFalse(rows.first { it.show.id == "empty" }.hasAudio)
    }

    @Test fun `youtube media without mime type is video and publication uses actual dates only`() {
        val rows = summarizeShows(listOf(show("youtube").copy(refreshedAt = 9000)), listOf(
            episode("a", "youtube", null, published = 100).copy(audioUrl = "https://youtu.be/abcdefghijk"),
            episode("b", "youtube", "video/youtube", published = 300),
            episode("c", "youtube", "video/youtube", published = -1)))
        assertFalse(rows.single().hasAudio)
        assertTrue(rows.single().hasVideo)
        assertEquals(300L, rows.single().latestPublishedAt)
    }

    @Test fun `unfinished counts include never started and partial episodes but not completed episodes`() {
        val rows = summarizeShows(listOf(show("s"), show("empty")), listOf(
            episode("new", "s"), episode("partial", "s", played = 50, position = 2000),
            episode("done", "s", played = 100, completed = true)))
        val row = rows.first { it.show.id == "s" }
        assertEquals(3, row.episodeCount)
        assertEquals(2, row.unfinishedCount)
        assertEquals(100L, row.lastPlayedAt)
        assertEquals(0, rows.first { it.show.id == "empty" }.episodeCount)
    }

    @Test fun `latest episode and recently played sort independently with unknown dates last`() {
        val rows = summarizeShows(listOf(show("a").copy(refreshedAt = 9000), show("b"), show("z")), listOf(
            episode("a", "a", published = 100, played = 500),
            episode("b", "b", published = 300, played = 20), episode("z", "z")))
        assertEquals(listOf("b", "a", "z"), ids(rows, ShowPreferences(sort = ShowSort.LATEST_EPISODE)))
        assertEquals(listOf("a", "b", "z"), ids(rows, ShowPreferences(sort = ShowSort.RECENTLY_PLAYED)))
    }

    @Test fun `pins lead within both sorted groups and never bypass search or media filters`() {
        val rows = summarizeShows(listOf(show("a"), show("b"), show("c"), show("d")), listOf(
            episode("a", "a", published = 400), episode("b", "b", "video/mp4", published = 100),
            episode("c", "c", "video/mp4", published = 200), episode("d", "d", published = 300)))
        val preferences = ShowPreferences(sort = ShowSort.LATEST_EPISODE, pinnedFeeds = setOf(show("b").feedUrl, show("c").feedUrl))
        assertEquals(listOf("c", "b", "a", "d"), ids(rows, preferences))
        assertEquals(listOf("a", "d"), ids(rows, preferences.copy(format = ShowFormat.AUDIO)))
        assertEquals(listOf("a"), selectShows(rows, " A ", preferences).map { it.show.id })
    }

    @Test fun `search matches author and pins survive a local show id change`() {
        val original = show("old", "Zulu")
        val rows = summarizeShows(listOf(show("a", "Alpha"), original.copy(id = "restored", author = "The Creator")), emptyList())
        val preferences = ShowPreferences(pinnedFeeds = setOf(original.feedUrl))
        assertEquals(listOf("restored", "a"), ids(rows, preferences))
        assertEquals(listOf("restored"), selectShows(rows, "creator", preferences).map { it.show.id })
        assertTrue(selectShows(rows, "absent", preferences).isEmpty())
    }

    @Test fun `unknown and equal dates tie by title then id with stable case handling`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val rows = summarizeShows(listOf(show("z", "Same"), show("i", "Indigo"), show("j", "Jade"), show("a", "same")), emptyList())
            ShowSort.entries.forEach { sort -> assertEquals(listOf("i", "j", "a", "z"), ids(rows, ShowPreferences(sort = sort))) }
        } finally { Locale.setDefault(before) }
    }

    private fun ids(rows: List<ShowSummary>, preferences: ShowPreferences = ShowPreferences()) =
        selectShows(rows, "", preferences).map { it.show.id }
}
