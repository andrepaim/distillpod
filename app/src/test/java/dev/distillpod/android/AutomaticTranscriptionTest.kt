package dev.distillpod.android

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomaticTranscriptionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val episode = EpisodeEntity("auto-episode", "auto-podcast", "guid", "Episode", "https://example.test/audio.mp3",
        mediaFingerprint = "current-audio", durationMs = 10000)

    private suspend fun isolatedGraph(ctx: Context = context): AppGraph {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val graph = AppGraph(ctx)
        graph.scope.cancel()
        withTimeout(5000) {
            while (!graph.scope.coroutineContext[Job]!!.isCompleted) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        graph.settings.flag("transcribeOnPlayback", true)
        graph.settings.flag("onlineTranscripts", false)
        graph.db.library().putPodcast(PodcastEntity(episode.podcastId, "https://example.test/feed", "Show"))
        graph.db.library().putEpisode(episode)
        return graph
    }

    @Test fun `setting defaults on and disabled value survives reopening storage`() = runBlocking<Unit> {
        val folder = Files.createTempDirectory("automatic-transcription-").toFile()
        val file = File(folder, "test.preferences_pb")
        suspend fun settingsSession(block: suspend (AppSettings) -> Unit) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try { block(AppSettings(context, PreferenceDataStoreFactory.create(scope = scope) { file })) }
            finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
        try {
            settingsSession {
                assertTrue(it.flow.first().transcribeOnPlayback)
                it.flag("transcribeOnPlayback", false)
            }
            settingsSession { assertFalse(it.flow.first().transcribeOnPlayback) }
        } finally { folder.deleteRecursively() }
    }

    @Test fun `disabled setting and diagnostic clips never create work but manual preparation still works`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            graph.settings.flag("transcribeOnPlayback", false)
            graph.transcription.prepareAutomatically(episode.id)
            assertNull(graph.db.library().transcript(episode.id))
            graph.settings.flag("transcribeOnPlayback", true)
            graph.transcription.prepareAutomatically("diagnostic-clip")
            assertTrue(graph.db.library().transcripts().isEmpty())
            graph.settings.flag("transcribeOnPlayback", false)
            graph.transcription.prepare(episode.id)
            assertEquals("queued", graph.db.library().transcript(episode.id)!!.state)
        } finally { graph.db.close() }
    }

    @Test fun `automatic requests reuse existing states despite a model or language change`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            for (state in listOf("ready", "queued", "processing", "waiting_model", "paused", "failed", "partial")) {
                val old = TranscriptEntity(episode.id, "saved-$state", episode.mediaFingerprint, "base", "pt",
                    state = state, processedMs = 5000, error = "Keep this state")
                dao.putTranscript(old)
                dao.putSegments(listOf(SegmentEntity(episode.id, old.revision, 0, 5000, "Saved text", 0)))
                graph.transcription.prepareAutomatically(episode.id)
                assertEquals(state, old, dao.transcript(episode.id))
                assertEquals("Saved text", dao.window(episode.id, old.revision, 0, 5000).single().text)
            }
        } finally { graph.db.close() }
    }

    @Test fun `changed audio and explicit stale state get new revisions while pause remains respected`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            for (old in listOf(
                TranscriptEntity(episode.id, "old-audio", "obsolete-audio", "tiny", "auto", state = "ready"),
                TranscriptEntity(episode.id, "stale-text", episode.mediaFingerprint, "tiny", "auto", state = "stale"),
            )) {
                dao.putTranscript(old)
                dao.putSegments(listOf(SegmentEntity(episode.id, old.revision, 0, 5000, "Old text", 0)))
                graph.transcription.prepareAutomatically(episode.id)
                val current = dao.transcript(episode.id)!!
                assertNotEquals(old.revision, current.revision)
                assertEquals("queued", current.state)
                assertEquals(episode.mediaFingerprint, current.fingerprint)
                assertTrue(dao.window(episode.id, old.revision, 0, 5000).isEmpty())
            }
            val paused = dao.transcript(episode.id)!!.copy(state = "paused", fingerprint = "previous-audio")
            dao.putTranscript(paused)
            graph.transcription.prepareAutomatically(episode.id)
            assertEquals(paused, dao.transcript(episode.id))
        } finally { graph.db.close() }
    }

    @Test fun `concurrent manual and playback requests share a revision and queued work`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            coroutineScope {
                repeat(20) { launch(Dispatchers.IO) { graph.transcription.prepareAutomatically(episode.id) } }
                launch(Dispatchers.IO) { graph.transcription.prepare(episode.id) }
            }
            val current = graph.db.library().transcript(episode.id)!!
            repeat(5) { graph.transcription.prepareAutomatically(episode.id) }
            assertEquals(current, graph.db.library().transcript(episode.id))
            graph.settings.flag("transcribeOnPlayback", false)
            graph.transcription.prepareAutomatically(episode.id)
            assertEquals(current, graph.db.library().transcript(episode.id))
        } finally { graph.db.close() }
    }

    @Test fun `missing model waits and model availability resumes without restarting the revision`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            graph.transcription.prepareAutomatically(episode.id)
            val row = graph.db.library().transcript(episode.id)!!
            graph.transcription.process(episode.id, row.revision, TranscriptExecution({ false }, {}, false))
            assertEquals("waiting_model", graph.db.library().transcript(episode.id)!!.state)
            graph.transcription.prepareAutomatically(episode.id)
            assertEquals("waiting_model", graph.db.library().transcript(episode.id)!!.state)
            graph.transcription.resumeModel(row.model)
            assertEquals("queued", graph.db.library().transcript(episode.id)!!.state)
            assertEquals(row.revision, graph.db.library().transcript(episode.id)!!.revision)
        } finally { graph.db.close() }
    }

    @Test fun `an active pipeline keeps its revision while the downloaded audio identity changes`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = TranscriptEntity(episode.id, "active-download", episode.mediaFingerprint, "tiny", "auto", lookupOnline = true)
            dao.putTranscript(row)
            lateinit var repository: TranscriptionRepository
            repository = TranscriptionRepository(graph, onlineLookup = { _, _ ->
                // Reproduce the window between downloadComplete and the chunk adopting the file hash.
                dao.putEpisode(episode.copy(mediaFingerprint = "downloaded-file-hash"))
                val transitional = row.copy(state = "stale")
                dao.putTranscript(transitional)
                repository.prepareAutomatically(episode.id)
                assertEquals(transitional, dao.transcript(episode.id))
                currentCoroutineContext().ensureActive()
                true
            })
            repository.process(episode.id, row.revision, TranscriptExecution({ false }, {}, false))
        } finally { graph.db.close() }
    }

    @Test fun `foreground rejection retains one constrained recovery job`() = runBlocking<Unit> {
        val rejected = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? = throw IllegalStateException("Background start rejected")
        }
        val graph = isolatedGraph(rejected)
        try {
            repeat(3) { graph.transcription.prepareAutomatically(episode.id) }
            val row = graph.db.library().transcript(episode.id)!!
            assertEquals("queued", row.state)
            val jobs = WorkManager.getInstance(context).getWorkInfosForUniqueWork("transcript:${episode.id}:${row.revision}:0").get(5, TimeUnit.SECONDS)
            assertEquals(1, jobs.size)
            assertFalse(jobs.single().state.isFinished)
        } finally { graph.db.close() }
    }
}
