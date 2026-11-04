package dev.distillpod.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import dev.distillpod.android.features.AppViewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.workDataOf
import dev.distillpod.android.work.TranscriptionService
import dev.distillpod.android.data.*
import dev.distillpod.core.PcmWaveWriter
import dev.distillpod.core.readWhisperWave
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativePipelineDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun backgroundChunksCheckpointPauseAndResumeOnOriginalTimeline() = runBlocking {
        val graph = compose.activity.applicationContext.appGraph
        val dao = graph.db.library()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val preferences = graph.settings.flow.first()
        val id = "native-device-${UUID.randomUUID()}"
        val source = File.createTempFile("pipeline-", ".wav", graph.context.cacheDir)
        try {
            // Leave this verified model installed as a usable starting point, preserving any existing file.
            val model = File(graph.media.modelsDirectory, "tiny.bin")
            if (!model.exists()) assets.open("ggml-tiny.bin").use { input -> model.outputStream().use(input::copyTo) }
            val expected = whisperModels.first { it.id == "tiny" }
            assertEquals(expected.bytes, model.length())
            val hash = MessageDigest.getInstance("SHA-256")
            model.inputStream().use { input -> val block = ByteArray(65536); while (true) { val n = input.read(block); if (n < 0) break; hash.update(block, 0, n) } }
            assertEquals(expected.sha256, hash.digest().joinToString("") { "%02x".format(it) })
            dao.putModel(ModelEntity("tiny", "ready", expected.bytes))
            graph.settings.text("model", "tiny"); graph.settings.text("language", "en")
            val samples = readWhisperWave(assets.open("jfk.wav").use { it.readBytes() })
            val wave = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { wave.putShort((it * 32768).toInt().coerceIn(-32768, 32767).toShort()) }
            PcmWaveWriter(source, 16000, 1).use { writer -> repeat(30) { writer.append(ByteBuffer.wrap(wave.array())) }; writer.finish() }
            dao.putPodcast(PodcastEntity(id, "https://example.test/$id", "Native pipeline fixture"))
            dao.putEpisode(EpisodeEntity(id, id, id, "Native pipeline fixture", "https://example.test/audio", durationMs = 330000,
                localFile = source.absolutePath, downloadState = "downloaded", downloadedBytes = source.length(), totalBytes = source.length(), mediaFingerprint = id))
            graph.transcription.prepare(id, true)
            await { graph.transcription.progress.value[id]?.let {
                it.stage == TranscriptStage.TRANSCRIBING && it.workedMs in 1 until 120000
            } == true }
            val liveRun = graph.transcription.progress.value[id]!!.run
            assertTrue("User-started inference owns a direct foreground service", TranscriptionService.isRunning)
            graph.transcription.prepare(id)
            assertEquals("Joining active preparation keeps live progress", liveRun, graph.transcription.progress.value[id]!!.run)
            assertEquals("Live progress must not publish unfinished coverage", 0L, dao.transcript(id)!!.processedMs)
            assertTrue(dao.coverage(id, dao.transcript(id)!!.revision).isEmpty())
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java].open("episode:$id")
            }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Transcribing ·", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5000) {
                compose.onNodeWithContentDescription("Transcript preparation progress").fetchSemanticsNode()
                    .config[SemanticsProperties.ProgressBarRangeInfo].current > 0f
            }
            val firstLive = graph.transcription.progress.value[id]!!.workedMs
            await { (graph.transcription.progress.value[id]?.workedMs ?: 0) > firstLive }
            val manager = WorkManager.getInstance(graph.context)
            val recovery = OneTimeWorkRequestBuilder<TranscriptWorker>()
                .setInputData(workDataOf("episode" to id, "revision" to dao.transcript(id)!!.revision))
                .addTag("transcript:$id").build()
            manager.enqueue(recovery).result.get()
            await {
                val info = manager.getWorkInfoById(recovery.id).get()
                info != null && info.runAttemptCount > 0 && info.state == WorkInfo.State.ENQUEUED
            }
            assertFalse("Recovery remains scheduled while the foreground session owns inference", manager.getWorkInfoById(recovery.id).get()!!.state.isFinished)
            await { (dao.transcript(id)?.processedMs ?: 0) >= 120000 || dao.transcript(id)?.state == "failed" }
            assertNotEquals("failed", dao.transcript(id)?.state)
            graph.transcription.pause(id)
            val checkpoint = dao.transcript(id)!!
            assertEquals("paused", checkpoint.state)
            assertNull("Pause clears the unfinished live estimate", graph.transcription.progress.value[id])
            val firstCoverage = dao.coverage(id, checkpoint.revision).first()
            assertEquals(0L, firstCoverage.startMs); assertEquals(120000L, firstCoverage.endMs)
            graph.transcription.prepare(id)
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            try {
                // Removing every scheduled transcript job must not interrupt the direct session.
                manager.cancelAllWorkByTag("transcript:$id").result.get()
                device.pressHome()
                device.sleep()
                assertFalse(graph.context.getSystemService(android.os.PowerManager::class.java).isInteractive)
                await { dao.transcript(id)?.state in listOf("ready", "failed") }
                assertFalse("Completion must happen before the screen is woken", graph.context.getSystemService(android.os.PowerManager::class.java).isInteractive)
            } finally { device.wakeUp() }
            val completed = dao.transcript(id)!!
            assertEquals(completed.error, "ready", completed.state)
            assertEquals(checkpoint.revision, completed.revision)
            assertEquals(330000L, completed.processedMs)
            assertEquals(listOf(0L, 120000L, 240000L), dao.coverage(id, completed.revision).map { it.startMs })
            assertTrue(dao.segments(id, completed.revision).any { it.text.contains("country", true) })
        } finally {
            graph.transcription.pause(id)
            await { !graph.activeFiles.busy(source.absolutePath) }
            dao.deletePodcast(id); source.delete()
            graph.settings.text("model", preferences.model); graph.settings.text("language", preferences.language)
        }
    }
    private suspend fun await(predicate: suspend () -> Boolean) = withTimeout(240000) { while (!predicate()) delay(100) }
}
