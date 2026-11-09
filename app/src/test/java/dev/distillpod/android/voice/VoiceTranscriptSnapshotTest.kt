package dev.distillpod.android.voice

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceTranscriptSnapshotTest {
    @Test fun `partial transcription supplies all completed past passages of the current revision`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val dao = db.library()
            dao.putPodcast(PodcastEntity("show", "https://example.test/feed", "Synthetic show"))
            dao.putEpisode(EpisodeEntity("ep", "show", "guid", "Synthetic episode", "https://example.test/audio", mediaFingerprint = "current"))
            dao.putEpisode(EpisodeEntity("other", "show", "other-guid", "Other episode", "https://example.test/other", mediaFingerprint = "other"))
            val row = TranscriptEntity("ep", "revision", "current", "fixture", "pt", state = "running", durationMs = 3_600_000)
            dao.putTranscript(row)
            val past = (0 until 180).map { i ->
                SegmentEntity("ep", row.revision, i * 10_000L, (i + 1) * 10_000L, "Passage $i: " + "explicação 🧠 ".repeat(30))
            }
            dao.putSegments(past + listOf(
                SegmentEntity("ep", row.revision, 1_795_000, 1_805_000, "Straddles the current position"),
                SegmentEntity("ep", row.revision, 1_800_000, 1_810_000, "Future speech"),
                SegmentEntity("ep", row.revision, 1_700_001, 1_600_000, "Malformedquery"),
                SegmentEntity("ep", "old-revision", 0, 1000, "Obsolete transcript"),
                SegmentEntity("other", row.revision, 0, 1000, "Unrelated episode")))
            val source = JSONObject(VoiceContext.load(db, "ep", 1_800_000)).getJSONArray("sourceSegments")
            assertEquals(past.size, source.length())
            for (i in past.indices) {
                assertEquals(past[i].startMs, source.getJSONObject(i).getLong("startMs"))
                assertEquals(past[i].text, source.getJSONObject(i).getString("text"))
            }
            val oldSource = VoiceContext.load(db, "ep", 1_800_000)
            assertEquals("[]", VoiceContext.search(db, oldSource, "Malformedquery"))
            dao.putTranscript(row.copy(revision = "replacement"))
            dao.putSegments(listOf(SegmentEntity("ep", "replacement", 1_790_000, 1_800_000, "Replacement only")))
            val replacement = JSONObject(VoiceContext.load(db, "ep", 1_800_000)).getJSONArray("sourceSegments")
            assertEquals(1, replacement.length())
            assertEquals("Replacement only", replacement.getJSONObject(0).getString("text"))
            assertTrue(runCatching { VoiceContext.search(db, oldSource, "Passage") }.isFailure)
            dao.putTranscript(row.copy(fingerprint = "obsolete"))
            assertTrue(runCatching { VoiceContext.load(db, "ep", 1_800_000) }.isFailure)
        } finally { db.close() }
    }
}
