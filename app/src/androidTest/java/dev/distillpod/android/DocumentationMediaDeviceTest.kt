package dev.distillpod.android

import android.content.ComponentName
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.distillpod.android.data.*
import dev.distillpod.android.features.AppViewModel
import dev.distillpod.android.features.timestamp
import dev.distillpod.android.playback.mediaItem
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Opt-in documentation capture on an empty disposable emulator; never calls an AI provider. */
@RunWith(AndroidJUnit4::class)
class DocumentationMediaDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun captureDocumentation() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("allowDocumentationCapture") == "true")
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")) {
            "Documentation capture requires a disposable emulator"
        }
        val graph = compose.activity.applicationContext.appGraph
        graph.ready.await()
        val dao = graph.db.library()
        check(dao.podcasts().isEmpty() && dao.episodes().isEmpty() && !graph.account.state.value.connected) {
            "Use an empty, signed-out documentation installation"
        }
        val mode = arguments.getString("documentationMode") ?: "screens"
        check(mode in setOf("screens", "headphones", "distill"))
        val directory = File(graph.context.getExternalFilesDir(null), "documentation").apply { mkdirs() }
        val vm = withContext(Dispatchers.Main) {
            ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java]
        }
        val future = withContext(Dispatchers.Main) {
            MediaController.Builder(graph.context, SessionToken(graph.context,
                ComponentName(graph.context, PlaybackService::class.java))).buildAsync()
        }
        val controller = withContext(Dispatchers.IO) { future.get(15, TimeUnit.SECONDS) }
        val device = UiDevice.getInstance(instrumentation)
        val show = "documentation-show"
        val episode = "documentation-episode"
        val quote = "A useful note keeps the idea and the context that gave it meaning."
        val takeaway = "Save the source with the insight. Returning to the original moment helps you check what the speaker meant."
        val source = File(graph.context.cacheDir, "documentation-silence.wav")
        val bytes = ByteBuffer.allocate(44 + 16000 * 2 * 180).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(bytes.capacity() - 8).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(bytes.capacity() - 44)
        source.writeBytes(bytes.array())
        suspend fun pause(milliseconds: Long) {
            val until = android.os.SystemClock.uptimeMillis() + milliseconds
            while (android.os.SystemClock.uptimeMillis() < until) {
                compose.mainClock.advanceTimeBy(100)
                compose.waitForIdle()
                delay(100)
            }
        }
        suspend fun route(value: String) {
            withContext(Dispatchers.Main) { vm.open(value) }
            compose.waitForIdle()
            pause(1000)
        }
        suspend fun root(value: String) {
            withContext(Dispatchers.Main) { vm.root(value) }
            compose.waitForIdle()
            pause(1000)
        }
        fun screenshot(name: String) {
            compose.waitForIdle()
            check(device.takeScreenshot(File(directory, "$name.png")))
        }
        suspend fun await(predicate: suspend () -> Boolean) {
            withTimeout(15000) { while (!predicate()) delay(100) }
        }
        try {
            graph.settings.flag("transcribeOnPlayback", false)
            graph.settings.flag("distillSound", false)
            graph.settings.flag("headphoneDistill", false)
            dao.putPodcast(PodcastEntity(show, "https://example.invalid/documentation.xml", "The Learning Notebook",
                author = "DistillPod demo", description = "A fictional show made for this walkthrough."))
            for ((id, title) in listOf(episode to "Keep the idea. Keep the context.",
                    "documentation-next" to "Make room for a second listen")) {
                dao.putEpisode(EpisodeEntity(id, show, id, title, "https://example.invalid/$id.wav",
                    description = "Synthetic demonstration content. No real podcast or account data.",
                    publishedAt = 1793923200000, durationMs = 180000, positionMs = 75000,
                    localFile = source.path, downloadState = "downloaded", mediaFingerprint = "documentation"))
            }
            dao.putTranscript(TranscriptEntity(episode, "documentation", "documentation", "publisher", "en",
                source = "publisher", state = "ready", durationMs = 180000, processedMs = 180000))
            dao.putCoverage(CoverageEntity(episode, "documentation", 0, 180000))
            val passages = listOf("Good ideas often arrive while we are doing something else.", quote,
                "Keep a short passage so you can return to the source, then decide how the idea applies.",
                "Listening again can reveal a qualification that a quick summary missed.",
                "The goal is to remember faithfully, not simply to collect more notes.",
                "A saved moment becomes useful when you revisit it.")
            dao.putSegments(passages.mapIndexed { index, text ->
                SegmentEntity(episode, "documentation", index * 30000L, (index + 1) * 30000L, text)
            })
            dao.putDistillation(DistillationEntity("documentation-example", episode, 30000, 90000,
                passages.slice(1..2).joinToString(" "), quote, takeaway, "documentation", "documentation", "succeeded",
                model = "Documentation example"))
            withContext(Dispatchers.Main) {
                controller.setMediaItems(listOf(episode, "documentation-next").map { id ->
                    mediaItem(EpisodeEntity(id, show, id,
                        if (id == episode) "Keep the idea. Keep the context." else "Make room for a second listen",
                        "https://example.invalid/$id.wav", localFile = source.path),
                        PodcastEntity(show, "https://example.invalid/documentation.xml", "The Learning Notebook"))
                }, 0, 75000)
                controller.volume = 0f
                controller.prepare()
            }
            await { withContext(Dispatchers.Main) { controller.playbackState == Player.STATE_READY } }
            compose.waitUntil(15000) { vm.loaded.value && vm.episodes.value.size == 2 }
            compose.waitForIdle()
            if (mode == "screens") {
                root("library")
                compose.onNodeWithText("Continue listening").assertIsDisplayed()
                screenshot("library")
                route("player")
                compose.onNodeWithText("Distill last 60 seconds").assertIsDisplayed()
                screenshot("player")
                root("saved"); screenshot("saved")
                route("capture:documentation-example")
                compose.onNodeWithText("Replay 0:30").performScrollTo()
                screenshot("distill-result")
                route("transcript:$episode"); screenshot("transcript")
                root("settings"); screenshot("settings")
                route("settings:playback")
                compose.onNodeWithContentDescription("Distill with previous-track button").assertIsDisplayed()
                screenshot("headphone-settings")
                root("queue"); screenshot("queue")
            } else {
                if (mode == "headphones") route("settings:playback") else route("player")
                File(directory, "ready").writeText(mode)
                withTimeout(60000) { while (!File(directory, "start").exists()) delay(100) }
                pause(2000)
                if (mode == "headphones") {
                    compose.onNodeWithContentDescription("Distill with previous-track button").performClick()
                    pause(3000)
                    route("player")
                }
                withContext(Dispatchers.Main) { controller.play() }
                await { withContext(Dispatchers.Main) { controller.isPlaying } }
                pause(3000)
                if (mode == "headphones") device.executeShellCommand("input keyevent 88")
                else compose.onNodeWithText("Distill last 60 seconds").performScrollTo().performClick()
                var captured: DistillationEntity? = null
                await {
                    captured = dao.distillations().firstOrNull { it.id != "documentation-example" }
                    captured != null && captured!!.state == "waiting_auth"
                }
                check(withContext(Dispatchers.Main) { controller.isPlaying && controller.currentMediaItem?.mediaId == episode })
                pause(2500)
                // Deliberately supplied example output: captions/provenance must not claim live AI generation.
                dao.putDistillation(captured!!.copy(quote = quote, insight = takeaway,
                    state = "succeeded", error = null, model = "Documentation example"))
                if (mode == "headphones") root("saved")
                pause(1500)
                route("capture:${captured!!.id}")
                pause(3000)
                val replay = "Replay ${timestamp(captured!!.startMs)}"
                compose.onNodeWithText(replay).performScrollTo()
                pause(2000)
                compose.onNodeWithText(replay).performClick()
                pause(3000)
                await { withContext(Dispatchers.Main) {
                    controller.isPlaying && controller.currentMediaItem?.mediaId == episode &&
                        controller.currentPosition < captured!!.endMs
                } }
                File(directory, "done").writeText(mode)
                pause(1500)
            }
        } finally {
            withContext(Dispatchers.Main) { controller.pause(); controller.clearMediaItems(); controller.release() }
            dao.deletePodcast(show)
            source.delete()
        }
    }
}
