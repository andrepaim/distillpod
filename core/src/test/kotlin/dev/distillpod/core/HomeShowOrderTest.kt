package dev.distillpod.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** #276: Home's "Your shows" row puts pins, then Audio-filter shows, then the newest publications first. */
class HomeShowOrderTest {
    private fun rank(id: String, pinned: Boolean = false, audio: Boolean = true, latest: Long? = null, title: String = id) =
        HomeShowRank(id, title, pinned, audio, latest)

    private fun order(vararg ranks: HomeShowRank) = HomeShowOrder.sort(ranks.toList().shuffled(java.util.Random(7))) { it }.map { it.id }

    @Test fun `pinned shows precede unpinned shows regardless of format or date`() {
        assertEquals(listOf("pinned-video", "unpinned-audio"), order(
            rank("unpinned-audio", latest = 1_000_000), rank("pinned-video", pinned = true, audio = false, latest = 10)))
        assertEquals(listOf("pinned-old", "fresh"), order(rank("fresh", latest = 900), rank("pinned-old", pinned = true, latest = 1)))
    }

    @Test fun `within a pin group audio and mixed shows precede video-only shows`() {
        // A mixed audio/video show matches the Audio filter, so the caller marks it audio.
        assertEquals(listOf("mixed", "video", "audio", "video-new"), order(
            rank("video", pinned = true, audio = false, latest = 500), rank("mixed", pinned = true, latest = 100),
            rank("audio", latest = 1), rank("video-new", audio = false, latest = 9_000)))
    }

    @Test fun `within a format group newer latest episodes come first and unknown dates last`() {
        assertEquals(listOf("newest", "middle", "oldest", "nonpositive", "unknown"), order(
            rank("middle", latest = 200), rank("unknown", latest = null), rank("newest", latest = 300),
            rank("nonpositive", latest = 0), rank("oldest", latest = 100)))
    }

    @Test fun `two pinned audio shows follow their latest episodes`() {
        assertEquals(listOf("b", "a"), order(rank("a", pinned = true, latest = 10), rank("b", pinned = true, latest = 20)))
    }

    @Test fun `ties break by case-insensitive title then id in any default locale`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(listOf("i", "j", "a", "z", "u1", "u2"), order(
                rank("z", title = "Same", latest = 50), rank("j", title = "Jade", latest = 50),
                rank("a", title = "same", latest = 50), rank("i", title = "Indigo", latest = 50),
                rank("u2", title = "Untitled"), rank("u1", title = "Untitled")))
        } finally { Locale.setDefault(before) }
    }

    @Test fun `full priority across every rule`() {
        assertEquals(listOf("p-audio-new", "p-audio-old", "p-audio-none", "p-video", "u-audio-today", "u-audio-none", "u-video-new", "u-video-none"), order(
            rank("u-audio-today", latest = 10_000), rank("u-video-new", audio = false, latest = 20_000),
            rank("p-video", pinned = true, audio = false, latest = 30_000), rank("p-audio-old", pinned = true, latest = 5),
            rank("p-audio-new", pinned = true, latest = 50), rank("u-audio-none"), rank("u-video-none", audio = false),
            rank("p-audio-none", pinned = true)))
    }

    @Test fun `sort keeps caller items and ranks each once`() {
        data class Show(val id: String, val latest: Long)
        var calls = 0
        val sorted = HomeShowOrder.sort(listOf(Show("a", 1), Show("b", 2), Show("c", 3))) { calls++; HomeShowRank(it.id, it.id, false, true, it.latest) }
        assertEquals(listOf("c", "b", "a"), sorted.map { it.id })
        assertEquals(3, calls)
    }
}
