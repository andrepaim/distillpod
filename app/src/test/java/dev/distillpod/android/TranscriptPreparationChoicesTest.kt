package dev.distillpod.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Preparation choices that must never discard saved transcript work: Resume keeps a revision's own
 * model and language (#228), a waiting transcript can go on once a selected model is ready (#225),
 * and "Transcribe locally again" keeps the current text readable until its replacement is ready (#229).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptPreparationChoicesTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val episode = EpisodeEntity("choice-episode", "choice-podcast", "guid", "Episode", "https://example.test/audio.mp3",
        mediaFingerprint = "current-audio", durationMs = 600_000)
    private val createdModels = mutableListOf<File>()

    private suspend fun isolatedGraph(): AppGraph {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        // Explicit repository calls only: graph jobs never start in this cancelled scope.
        val graph = AppGraph(context, CoroutineScope(SupervisorJob().apply { cancel() } + Dispatchers.Main.immediate))
        graph.settings.flag("transcribeOnPlayback", true)
        graph.settings.flag("onlineTranscripts", false)
        graph.db.library().putPodcast(PodcastEntity(episode.podcastId, "https://example.test/feed", "Show"))
        graph.db.library().putEpisode(episode)
        return graph
    }

    private suspend fun AppGraph.modelReady(id: String) {
        File(media.modelsDirectory, "$id.bin").also { it.writeText("synthetic model"); createdModels += it }
        db.library().putModel(ModelEntity(id, "ready"))
    }

    private fun cleanup(graph: AppGraph) { graph.db.close(); createdModels.forEach { it.delete() }; createdModels.clear() }

    /** A Whisper revision with five minutes of saved chunks. */
    private suspend fun AppGraph.saved(state: String, model: String = "tiny", language: String = "pt"): TranscriptEntity {
        val dao = db.library()
        val row = TranscriptEntity(episode.id, "saved-$state", episode.mediaFingerprint, model, language, state = state,
            durationMs = episode.durationMs, processedMs = 300_000, localCreatedAt = 1000)
        dao.putTranscript(row, createdAt = 1000)
        dao.putSegments(listOf(SegmentEntity(episode.id, row.revision, 0, 120_000, "Saved words", 0)))
        dao.putCoverage(CoverageEntity(episode.id, row.revision, 0, 120_000))
        dao.putCoverage(CoverageEntity(episode.id, row.revision, 120_000, 300_000))
        return row
    }

    // #228

    @Test fun `resume after a model and language change continues the saved revision with its own settings`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            graph.settings.text("model", "base")
            graph.settings.text("language", "en")
            for (state in listOf("paused", "failed", "queued", "waiting_model")) {
                val row = graph.saved(state)
                graph.transcription.prepare(episode.id)
                val current = dao.transcript(episode.id)!!
                assertEquals(state, row.revision, current.revision)
                assertEquals(state, "tiny", current.model)
                assertEquals(state, "pt", current.language)
                assertEquals(state, 300_000L, current.processedMs)
                assertEquals(state, listOf("Saved words"), dao.segments(episode.id, row.revision).map { it.text })
                assertEquals(state, 2, dao.coverage(episode.id, row.revision).size)
                // Without its model the revision waits for it; with Tiny it is queued.
                assertTrue(state, current.state in listOf("queued", "waiting_model"))
            }
        } finally { cleanup(graph) }
    }

    @Test fun `a capture or show automation preparation also keeps saved chunks after a settings change`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = graph.saved("paused")
            graph.settings.text("model", "base")
            graph.transcription.prepare(episode.id, allowOnline = false)
            assertEquals(row.revision, dao.transcript(episode.id)!!.revision)
            assertEquals("tiny", dao.transcript(episode.id)!!.model)
            assertEquals(2, dao.coverage(episode.id, row.revision).size)
        } finally { cleanup(graph) }
    }

    @Test fun `a transcript without saved progress uses the current settings when it starts`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = TranscriptEntity(episode.id, "nothing-saved", episode.mediaFingerprint, "tiny", "pt", state = "paused")
            dao.putTranscript(row)
            graph.settings.text("language", "en")
            graph.transcription.prepare(episode.id)
            val current = dao.transcript(episode.id)!!
            assertEquals(row.revision, current.revision)
            assertEquals("tiny", current.model)
            assertEquals("en", current.language)
            assertEquals("queued", current.state)
        } finally { cleanup(graph) }
    }

    // #225

    @Test fun `a waiting transcript without progress starts once the newly ready model is the selected one`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = TranscriptEntity(episode.id, "waits-for-tiny", episode.mediaFingerprint, "tiny", "auto", state = "waiting_model",
                error = "Download the selected transcription model in Settings.")
            dao.putTranscript(row)
            graph.settings.text("model", "base")
            graph.modelReady("base")
            graph.transcription.resumeModel("base")
            val current = dao.transcript(episode.id)!!
            assertEquals(row.revision, current.revision)
            assertEquals("base", current.model)
            assertEquals("queued", current.state)
            assertNull(current.error)
        } finally { cleanup(graph) }
    }

    @Test fun `a ready model that is not selected does not take over a waiting transcript`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = TranscriptEntity(episode.id, "waits-for-tiny", episode.mediaFingerprint, "tiny", "auto", state = "waiting_model")
            dao.putTranscript(row)
            graph.modelReady("base") // Tiny stays selected.
            graph.transcription.resumeModel("base")
            assertEquals(row, dao.transcript(episode.id)!!.copy(localCreatedAt = row.localCreatedAt))
        } finally { cleanup(graph) }
    }

    @Test fun `playback prepares a waiting transcript once the selected model is ready`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = TranscriptEntity(episode.id, "waits-for-tiny", episode.mediaFingerprint, "tiny", "auto", state = "waiting_model")
            dao.putTranscript(row)
            graph.settings.text("model", "base")
            graph.transcription.prepareAutomatically(episode.id)
            assertEquals("Base is not ready yet", "waiting_model", dao.transcript(episode.id)!!.state)
            graph.modelReady("base")
            graph.transcription.prepareAutomatically(episode.id)
            assertEquals("queued", dao.transcript(episode.id)!!.state)
            assertEquals("base", dao.transcript(episode.id)!!.model)
            assertEquals(row.revision, dao.transcript(episode.id)!!.revision)
        } finally { cleanup(graph) }
    }

    @Test fun `a waiting transcript with saved chunks keeps its own model until that model is ready`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val row = graph.saved("waiting_model")
            graph.settings.text("model", "base")
            graph.modelReady("base")
            graph.transcription.resumeModel("base")
            graph.transcription.prepareAutomatically(episode.id)
            assertEquals("waiting_model", dao.transcript(episode.id)!!.state)
            assertEquals("tiny", dao.transcript(episode.id)!!.model)
            graph.modelReady("tiny")
            graph.transcription.resumeModel("tiny")
            val current = dao.transcript(episode.id)!!
            assertEquals(row.revision, current.revision)
            assertEquals("tiny", current.model)
            assertEquals("queued", current.state)
            assertEquals(2, dao.coverage(episode.id, row.revision).size)
        } finally { cleanup(graph) }
    }

    // #229

    @Test fun `transcribing locally again keeps the current text readable until the replacement is ready`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val ready = TranscriptEntity(episode.id, "publisher-text", episode.mediaFingerprint, "publisher", "auto", "publisher",
                state = "ready", durationMs = episode.durationMs, processedMs = episode.durationMs)
            dao.putTranscript(ready)
            dao.putSegments(listOf(SegmentEntity(episode.id, ready.revision, 0, 5000, "Original words about rivers", 0)))
            dao.putCoverage(CoverageEntity(episode.id, ready.revision, 0, episode.durationMs))
            dao.putAnalysis(AnalysisEntity(episode.id, ready.revision, state = "succeeded", summary = "About rivers"))

            graph.transcription.prepare(episode.id, forceLocal = true)
            val replacement = dao.transcript(episode.id)!!
            assertNotEquals(ready.revision, replacement.revision)
            // No model is downloaded: the replacement waits, and nothing it replaces is lost.
            graph.transcription.process(episode.id, replacement.revision, TranscriptExecution({ false }, {}, false))
            assertEquals("waiting_model", dao.transcript(episode.id)!!.state)

            val text = EpisodeTextResolver(graph.db)
            val readable = text.complete(episode.id)!!
            assertEquals(ready.revision, readable.revision)
            assertEquals("publisher", readable.source)
            assertEquals("ready", readable.state)
            assertEquals(listOf("Original words about rivers"), text.segments(episode.id, ready.revision).map { it.text })
            assertEquals(ready.revision, effectiveTranscript(episode, dao.transcript(episode.id), dao.previousTranscript(episode.id), null)!!.revision)
            assertEquals(listOf(episode.id), TranscriptSearch(graph.db).find(listOf("rivers")).map { it.episodeId })
            assertEquals("succeeded", dao.analysis(episode.id)!!.state)

            // A second request while the replacement waits still keeps the original text.
            dao.putTranscript(dao.transcript(episode.id)!!.copy(state = "failed", processedMs = 120_000))
            dao.putSegments(listOf(SegmentEntity(episode.id, replacement.revision, 0, 5000, "Partial replacement", 0)))
            graph.transcription.prepare(episode.id, forceLocal = true)
            val second = dao.transcript(episode.id)!!
            assertNotEquals(replacement.revision, second.revision)
            assertEquals(ready.revision, text.complete(episode.id)!!.revision)
            assertTrue(dao.segments(episode.id, replacement.revision).isEmpty())

            // The replacement completes: it becomes current and the replaced text is removed.
            dao.putSegments(listOf(SegmentEntity(episode.id, second.revision, 0, 5000, "Fresh words about lakes", 0)))
            dao.putCoverage(CoverageEntity(episode.id, second.revision, 0, episode.durationMs))
            graph.transcription.publishCompleted(episode.id, second.revision, episode.durationMs)
            assertEquals(second.revision, text.complete(episode.id)!!.revision)
            assertNull(dao.previousTranscript(episode.id))
            assertTrue(dao.segments(episode.id, ready.revision).isEmpty())
            assertTrue(dao.coverage(episode.id, ready.revision).isEmpty())
            assertTrue(TranscriptSearch(graph.db).find(listOf("rivers")).isEmpty())
            assertEquals(listOf(episode.id), TranscriptSearch(graph.db).find(listOf("lakes")).map { it.episodeId })
            assertEquals("stale", dao.analysis(episode.id)!!.state)
        } finally { cleanup(graph) }
    }

    @Test fun `a new audio source discards the kept transcript with the rest`() = runBlocking<Unit> {
        val graph = isolatedGraph()
        try {
            val dao = graph.db.library()
            val ready = TranscriptEntity(episode.id, "old-text", episode.mediaFingerprint, "tiny", "auto",
                state = "ready", durationMs = episode.durationMs, processedMs = episode.durationMs)
            dao.putTranscript(ready)
            dao.putSegments(listOf(SegmentEntity(episode.id, ready.revision, 0, 5000, "Old words", 0)))
            graph.transcription.prepare(episode.id, forceLocal = true)
            assertNotNull(dao.previousTranscript(episode.id))
            dao.putEpisode(episode.copy(mediaFingerprint = "new-audio"))
            // Text for other audio is not readable, and a new revision for the new audio removes it.
            assertNull(EpisodeTextResolver(graph.db).complete(episode.id))
            dao.putTranscript(dao.transcript(episode.id)!!.copy(state = "stale"))
            graph.transcription.prepare(episode.id)
            assertNull(dao.previousTranscript(episode.id))
            assertTrue(dao.segments(episode.id, ready.revision).isEmpty())
        } finally { cleanup(graph) }
    }
}
