package dev.distillpod.android

import dev.distillpod.android.data.EpisodeDetailsState
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.features.episodeDate
import dev.distillpod.android.features.episodeMetadataText
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class EpisodeMetadataTextTest {
    private val now = Instant.parse("2026-11-12T15:00:00Z").toEpochMilli()
    private val brazil = ZoneId.of("America/Sao_Paulo")
    private val video = EpisodeEntity("e", "p", "abcdefghijk", "Video", "https://www.youtube.com/watch?v=abcdefghijk")
    private val podcast = EpisodeEntity("r", "p", "guid", "Episode", "https://example.com/audio.mp3")

    @Test fun `dates show the local day and add the year outside the current year`() {
        assertEquals("10 Nov", episodeDate(Instant.parse("2026-11-10T23:52:20Z").toEpochMilli(), now, brazil, Locale.ENGLISH))
        assertEquals("03 Feb 2019", episodeDate(Instant.parse("2019-02-03T22:17:34Z").toEpochMilli(), now, brazil, Locale.ENGLISH))
        // A date-only source stored at 12:00 UTC keeps its calendar day in UTC-3.
        assertEquals("10 Nov", episodeDate(Instant.parse("2026-11-10T12:00:00Z").toEpochMilli(), now, brazil, Locale.ENGLISH))
        assertEquals("", episodeDate(0, now, brazil, Locale.ENGLISH))
    }

    @Test fun `missing YouTube details show checking failure and unavailable states without invented text`() {
        val checking = episodeMetadataText(video, null, now, brazil, Locale.ENGLISH)
        assertEquals("Checking publication date…", checking.date)
        assertNull(checking.description)
        assertEquals("Loading the original description from YouTube…", checking.note)
        assertFalse(checking.retry)

        val failed = episodeMetadataText(video, EpisodeDetailsState.FAILED, now, brazil, Locale.ENGLISH)
        assertEquals("Publication date unavailable", failed.date)
        assertEquals("Couldn't load details from YouTube.", failed.note)
        assertTrue(failed.retry)

        val checked = episodeMetadataText(video, EpisodeDetailsState.CHECKED, now, brazil, Locale.ENGLISH)
        assertEquals("Publication date unavailable", checked.date)
        assertEquals("No description provided by the source.", checked.note)
        assertFalse(checked.retry)
    }

    @Test fun `known source values are shown as they are`() {
        val known = episodeMetadataText(video.copy(publishedAt = Instant.parse("2026-11-10T23:52:20Z").toEpochMilli(),
            description = "Original\n\ntext"), null, now, brazil, Locale.ENGLISH)
        assertEquals("10 Nov", known.date)
        assertEquals("Original\n\ntext", known.description)
        assertNull(known.note)
        // Podcast feeds do not trigger YouTube checks; missing values are stated explicitly.
        val rss = episodeMetadataText(podcast, null, now, brazil, Locale.ENGLISH)
        assertEquals("Publication date unavailable", rss.date)
        assertEquals("No description provided by the source.", rss.note)
    }
}
