package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryBackupTest {
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    private suspend fun seed(db: AppDatabase) {
        val dao = db.library()
        dao.putPodcast(PodcastEntity("show", "https://example.test/feed", "A show"))
        dao.putEpisode(EpisodeEntity("episode", "show", "guid", "Episode", "https://example.test/audio", durationMs = 60000,
            positionMs = 23000, localFile = "/private/device-only/audio", downloadState = "downloaded", downloadedBytes = 1000, mediaFingerprint = "sha"))
        dao.putTranscript(TranscriptEntity("episode", "rev", "sha", "tiny", "en", state = "ready", durationMs = 60000, processedMs = 60000))
        dao.putSegments(listOf(SegmentEntity("episode", "rev", 0, 60000, "A useful source excerpt.")))
        dao.putCoverage(CoverageEntity("episode", "rev", 0, 60000))
        dao.putDistillation(DistillationEntity("moment", "episode", 0, 10000, "A useful source excerpt.", "A useful source excerpt.", "The speaker explains a useful idea.", "rev", "sha", "succeeded"))
        dao.insertQueue(listOf(QueueEntryEntity("episode", 0)))
        dao.putPlayerSession(PlayerSessionEntity(episodeId = "episode", speed = 1.5f))
        dao.putModel(ModelEntity("tiny", "ready", 77691713))
    }
    @Test fun `round trip restores source relationships and playback while excluding device files`() = runBlocking {
        val source = database(); val target = database()
        try {
            seed(source)
            val bytes = LibraryBackup(source).export()
            assertFalse(bytes.toString(Charsets.UTF_8).contains("/private/device-only"))
            val backup = LibraryBackup(target)
            val preview = backup.preview(bytes.inputStream())
            assertEquals(1, preview.episodes); assertEquals(1, preview.moments); assertEquals(0, preview.conflicts)
            assertEquals(8, backup.merge(preview))
            val dao = target.library()
            assertEquals(23000L, dao.episode("episode")!!.positionMs)
            assertNull(dao.episode("episode")!!.localFile)
            assertEquals("none", dao.episode("episode")!!.downloadState)
            assertEquals("succeeded", dao.distillation("moment")!!.state)
            assertEquals("A useful source excerpt.", dao.segments("episode", "rev").single().text)
            assertEquals(1.5f, dao.playerSession()!!.speed)
            assertNull(dao.model("tiny"))
            assertEquals(0, backup.merge(backup.preview(bytes.inputStream())))
            assertEquals(1, dao.queue().size)
        } finally { source.close(); target.close() }
    }
    @Test fun `merge preserves newer local progress and interruptions require manual retry`() = runBlocking {
        val source = database(); val target = database()
        try {
            seed(source); seed(target)
            source.library().putDistillation(source.library().distillation("moment")!!.copy(state = "running"))
            target.library().deleteDistillation("moment")
            target.library().progress("episode", 40000, 60000, false, 0)
            val backup = LibraryBackup(target)
            val preview = backup.preview(LibraryBackup(source).export().inputStream())
            assertEquals(7, preview.conflicts)
            assertEquals(1, backup.merge(preview))
            assertEquals(40000L, target.library().episode("episode")!!.positionMs)
            assertEquals("failed", target.library().distillation("moment")!!.state)
            assertTrue(target.library().distillation("moment")!!.excerpt.isNotBlank())
        } finally { source.close(); target.close() }
    }
    @Test fun `corruption is rejected and invalid relationships roll back all inserted rows`() = runBlocking {
        val source = database(); val target = database()
        try {
            seed(source)
            val root = JSONObject(LibraryBackup(source).export().toString(Charsets.UTF_8))
            val payload = JSONObject(root.getString("payload"))
            payload.getJSONArray("episodes").getJSONObject(0).put("podcastId", "missing-parent")
            root.put("payload", payload.toString())
            val backup = LibraryBackup(target)
            try { backup.preview(root.toString().byteInputStream()); fail("Corrupt hash accepted") } catch (_: IllegalArgumentException) { }
            root.put("sha256", MessageDigest.getInstance("SHA-256").digest(payload.toString().toByteArray()).joinToString("") { "%02x".format(it) })
            val preview = backup.preview(root.toString().byteInputStream())
            try { backup.merge(preview); fail("Orphan row accepted") } catch (_: android.database.sqlite.SQLiteConstraintException) { }
            assertTrue(target.library().podcasts().isEmpty())
            assertTrue(target.library().episodes().isEmpty())
        } finally { source.close(); target.close() }
    }
    @Test fun `matching feeds and scoped GUIDs map restored references onto existing records`() = runBlocking {
        val source = database(); val target = database()
        try {
            seed(source)
            target.library().putPodcast(PodcastEntity("local-show", "https://example.test/feed", "Existing show"))
            target.library().putEpisode(EpisodeEntity("local-episode", "local-show", "guid", "Existing episode", "https://example.test/audio", positionMs = 40000, mediaFingerprint = "new-source"))
            val backup = LibraryBackup(target)
            val bytes = LibraryBackup(source).export()
            val preview = backup.preview(bytes.inputStream())
            assertEquals(2, preview.conflicts)
            backup.merge(preview)
            assertEquals(1, target.library().podcasts().size)
            assertEquals(1, target.library().episodes().size)
            assertEquals("local-episode", target.library().distillation("moment")!!.episodeId)
            assertEquals(40000L, target.library().episode("local-episode")!!.positionMs)
            assertEquals("stale", target.library().transcript("local-episode")!!.state)
            assertEquals(listOf("local-episode"), target.library().queue().map { it.episodeId })
            assertEquals(0, backup.merge(backup.preview(bytes.inputStream())))
        } finally { source.close(); target.close() }
    }
    @Test fun `schema one backup is upgraded before preview and merge`() = runBlocking {
        val source = database(); val target = database()
        try {
            seed(source)
            val root = JSONObject(LibraryBackup(source).export().toString(Charsets.UTF_8))
            val tables = JSONObject(root.getString("payload"))
            val moments = tables.getJSONArray("distillations")
            repeat(moments.length()) { moments.getJSONObject(it).remove("attempt") }
            root.put("payload", tables.toString()).put("databaseSchema", 1)
            root.put("sha256", MessageDigest.getInstance("SHA-256").digest(tables.toString().toByteArray()).joinToString("") { "%02x".format(it) })
            val backup = LibraryBackup(target)
            backup.merge(backup.preview(root.toString().byteInputStream()))
            assertEquals("", target.library().distillation("moment")!!.attempt)
            assertEquals("succeeded", target.library().distillation("moment")!!.state)
        } finally { source.close(); target.close() }
    }
}
