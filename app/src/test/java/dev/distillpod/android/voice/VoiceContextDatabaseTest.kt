package dev.distillpod.android.voice

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceContextDatabaseTest {
    @Test fun `acquisition rechecks revision after preflight and preserves partial past only context`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val dao = db.library()
            val episode = EpisodeEntity("ep", "pod", "g", "Synthetic", "https://example.invalid", mediaFingerprint = "audio")
            val row = TranscriptEntity("ep", "rev", "audio", "tiny", "en", state = "partial")
            dao.putPodcast(PodcastEntity("pod", "https://example.invalid", "Synthetic"))
            dao.putEpisode(episode); dao.putTranscript(row)
            dao.putSegments(listOf(SegmentEntity("ep", "rev", 1000, 9000, "Earlier speech"),
                SegmentEntity("ep", "rev", 9000, 11000, "Future speech")))
            VoiceContext.check(db, "ep", 10000)
            dao.putEpisode(episode.copy(mediaFingerprint = "replacement"))
            try { VoiceContext.load(db, "ep", 10000); fail("Stale audio must not be acquired") }
            catch (error: VoiceContextUnavailable) { assertEquals(VoiceReadiness.STALE_AUDIO, error.readiness) }
            dao.putEpisode(episode)
            dao.putTranscript(row.copy(revision = "replacement"))
            try { VoiceContext.load(db, "ep", 10000); fail("Old revision must not supply segments") }
            catch (error: VoiceContextUnavailable) { assertEquals(VoiceReadiness.EMPTY_SELECTION, error.readiness) }
            dao.putTranscript(row)
            val source = VoiceContext.load(db, "ep", 10000)
            val selected = JSONObject(source).getJSONArray("sourceSegments")
            assertEquals(1, selected.length()); assertEquals("Earlier speech", selected.getJSONObject(0).getString("text"))
            assertFalse(VoiceContext.search(db, source, "speech").contains("Future speech"))
            dao.putTranscript(row.copy(source = "online"))
            try { VoiceContext.search(db, source, "speech"); fail("Changed online source must not be searched") }
            catch (_: IllegalStateException) { }
        } finally { db.close() }
    }
}
