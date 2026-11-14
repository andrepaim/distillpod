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

/**
 * Opt-in visual baseline: seeds a small synthetic library on an empty disposable emulator and screenshots
 * the main screens and sheets, so two builds (for example before and after a dependency upgrade) can be
 * compared image by image. Run with `-e allowVisualBaseline true`; it never contacts a network service.
 */
@RunWith(AndroidJUnit4::class)
class VisualBaselineDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun captureVisualBaseline() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowVisualBaseline") == "true")
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")) {
            "Visual baseline capture requires a disposable emulator"
        }
        val graph = compose.activity.applicationContext.appGraph
        graph.ready.await()
        val dao = graph.db.library()
        check(dao.podcasts().isEmpty() && dao.episodes().isEmpty() && !graph.account.state.value.connected) {
            "Use an empty, signed-out installation"
        }
        val directory = File(graph.context.getExternalFilesDir(null), "visual-baseline").apply { deleteRecursively(); mkdirs() }
        val vm = withContext(Dispatchers.Main) {
            ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java]
        }
        val future = withContext(Dispatchers.Main) {
            MediaController.Builder(graph.context, SessionToken(graph.context,
                ComponentName(graph.context, PlaybackService::class.java))).buildAsync()
        }
        val controller = withContext(Dispatchers.IO) { future.get(15, TimeUnit.SECONDS) }
        val device = UiDevice.getInstance(instrumentation)
        val source = File(graph.context.cacheDir, "visual-baseline-silence.wav")
        val bytes = ByteBuffer.allocate(44 + 16000 * 2 * 60).order(ByteOrder.LITTLE_ENDIAN)
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
        suspend fun root(value: String) { withContext(Dispatchers.Main) { vm.root(value) }; compose.waitForIdle(); pause(1200) }
        suspend fun route(value: String) { withContext(Dispatchers.Main) { vm.open(value) }; compose.waitForIdle(); pause(1200) }
        suspend fun shot(name: String) { pause(600); check(device.takeScreenshot(File(directory, "$name.png"))) }
        suspend fun dismiss() { device.pressBack(); pause(1000) }
        val shows = listOf("visual-a" to "The Learning Notebook", "visual-b" to "Field Notes on Design", "visual-c" to "Slow Science Hour")
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val first = "visual-a-1"
        try {
            graph.settings.flag("transcribeOnPlayback", false)
            graph.settings.flag("distillSound", false)
            graph.settings.flag("headphoneDistill", false)
            for ((id, title) in shows) dao.putPodcast(PodcastEntity(id, "https://example.invalid/$id.xml", title,
                author = "DistillPod demo", description = "A fictional show used for visual comparisons. No real podcast data."))
            val titles = listOf("Keep the idea. Keep the context.", "Make room for a second listen", "Notes that survive a month",
                "Why summaries miss the qualifier", "Reading slowly on purpose")
            var index = 0
            for ((show, _) in shows) for ((n, title) in titles.withIndex()) {
                val id = "$show-${n + 1}"
                val progress = when (id) { first -> 21_000L; "visual-b-2" -> 1_200_000L; "visual-c-1" -> 300_000L; else -> 0L }
                dao.putEpisode(EpisodeEntity(id, show, id, "$title (${show.last().uppercase()})", "https://example.invalid/$id.wav",
                    description = "Synthetic episode for visual comparison. It contains no real podcast or account data.",
                    publishedAt = now - (index++) * day / 2, durationMs = if (id == first) 60_000 else 2_700_000,
                    positionMs = progress, lastPlayedAt = if (progress > 0) now - index * 60_000L else 0,
                    completed = id == "visual-a-5",
                    localFile = if (n < 2) source.path else null, downloadState = if (n < 2) "downloaded" else "none",
                    mediaFingerprint = "visual"))
            }
            dao.putTranscript(TranscriptEntity(first, "visual", "visual", "publisher", "en",
                source = "publisher", state = "ready", durationMs = 60_000, processedMs = 60_000))
            dao.putCoverage(CoverageEntity(first, "visual", 0, 60_000))
            val passages = listOf("Good ideas often arrive while we are doing something else.",
                "A useful note keeps the idea and the context that gave it meaning.",
                "Keep a short passage so you can return to the source, then decide how the idea applies.",
                "Listening again can reveal a qualification that a quick summary missed.",
                "The goal is to remember faithfully, not simply to collect more notes.",
                "A saved moment becomes useful when you revisit it.")
            dao.putSegments(passages.mapIndexed { i, text -> SegmentEntity(first, "visual", i * 10_000L, (i + 1) * 10_000L, text) })
            dao.putDistillation(DistillationEntity("visual-moment-1", first, 10_000, 30_000, passages.slice(1..2).joinToString(" "),
                passages[1], "Save the source with the insight. Returning to the original moment helps you check what the speaker meant.",
                "visual", "visual", "succeeded", createdAt = now - 3_600_000, model = "Visual example"))
            dao.putDistillation(DistillationEntity("visual-moment-2", "visual-b-2", 600_000, 660_000, "Design is a conversation with constraints.",
                "Design is a conversation with constraints.", "Name the constraint before arguing about the solution.",
                "visual", "visual", "succeeded", createdAt = now - 7_200_000, model = "Visual example"))
            dao.putDistillation(DistillationEntity("visual-moment-3", first, 40_000, 50_000, passages[4], passages[4], null,
                "visual", "visual", "waiting_auth", createdAt = now - 60_000))
            withContext(Dispatchers.Main) {
                controller.setMediaItems(listOf(first, "visual-a-2", "visual-b-1", "visual-c-3").map { id ->
                    val show = shows.first { id.startsWith(it.first) }
                    mediaItem(EpisodeEntity(id, show.first, id, titles[id.last().digitToInt() - 1] + " (${show.first.last().uppercase()})",
                        "https://example.invalid/$id.wav", localFile = source.path),
                        PodcastEntity(show.first, "https://example.invalid/${show.first}.xml", show.second))
                }, 0, 21_000)
                controller.volume = 0f
                controller.prepare()
            }
            withTimeout(15_000) { while (withContext(Dispatchers.Main) { controller.playbackState } != Player.STATE_READY) delay(100) }
            compose.waitUntil(15_000) { vm.loaded.value && vm.episodes.value.size == 15 }
            compose.waitForIdle()

            root("library"); shot("01-library")
            compose.onRoot().performTouchInput { swipeUp(startY = centerY + 600f, endY = centerY - 600f) }
            shot("02-library-scrolled")
            route("podcast:visual-a"); shot("03-show")
            route("episode:$first"); shot("04-episode")
            route("player"); shot("05-player")
            compose.onNodeWithContentDescription("Speed and effects", substring = true).performClick(); shot("06-player-speed"); dismiss()
            compose.onNodeWithContentDescription("Sleep timer").performClick(); shot("07-player-sleep"); dismiss()
            compose.onNodeWithContentDescription("Up next, transcript and insights").performClick(); shot("08-player-peek"); dismiss()
            route("transcript:$first"); shot("09-transcript")
            route("analysis:$first"); shot("10-insights-empty")
            root("queue"); shot("11-queue")
            root("search"); shot("12-search")
            root("saved"); shot("13-saved")
            route("capture:visual-moment-1"); shot("14-saved-moment")
            root("settings"); shot("15-settings")
            route("settings:playback"); shot("16-settings-playback")
            root("settings"); route("settings:transcription"); shot("17-settings-transcription")
            compose.onAllNodes(hasContentDescription("Learn more about", substring = true)).onFirst().performScrollTo().performClick()
            shot("18-learn-more"); dismiss()
            root("settings"); route("settings:storage"); shot("19-settings-storage")
            root("shows"); shot("20-shows")
            route("preparation:visual-a"); shot("21-show-preparation")
            root("library"); route("podcast:visual-b"); shot("22-show-b")
        } finally {
            withContext(Dispatchers.Main) { controller.pause(); controller.clearMediaItems(); controller.release() }
            for ((id, _) in shows) dao.deletePodcast(id)
            source.delete()
        }
    }
}
