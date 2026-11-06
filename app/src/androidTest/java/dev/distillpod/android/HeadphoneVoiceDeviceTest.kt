package dev.distillpod.android

import android.Manifest
import android.content.ComponentName
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.distillpod.android.data.*
import dev.distillpod.android.voice.HeadphoneVoiceService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Foreground-service eligibility and locked-screen platform keys; no provider or real audio input. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HeadphoneVoiceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun <T> main(block: () -> T): T {
        val result = CompletableFuture<T>()
        instrumentation.runOnMainSync { try { result.complete(block()) } catch (error: Throwable) { result.completeExceptionally(error) } }
        return result.get(15, TimeUnit.SECONDS)
    }
    private fun await(diagnostic: () -> String = { "" }, check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) { if (check()) return; Thread.sleep(50) }
        fail("Expected headphone voice or playback state was not reached. ${diagnostic()}")
    }
    @Test fun armedServiceSurvivesScreenOffAndNextAsksWithoutSkipping() {
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")) { "Use a test emulator" }
        check(context.resources.getBoolean(R.bool.voice_enabled)) { "Build with distillpodVoiceEnabled=true" }
        val graph = context.appGraph
        val prefs = runBlocking { graph.ready.await(); graph.settings.flow.first() }
        check(!graph.voiceApiKey.hasKey()) { "Use an emulator without a real voice credential" }
        val device = UiDevice.getInstance(instrumentation)
        val wasScreenOn = device.isScreenOn
        val id = "headphone-voice-${UUID.randomUUID()}"
        val file = File(context.cacheDir, "$id.wav")
        val bytes = ByteBuffer.allocate(44 + 16000 * 2 * 180).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(bytes.capacity() - 8).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(bytes.capacity() - 44)
        file.writeBytes(bytes.array())
        var controller: MediaController? = null
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            graph.voiceApiKey.save("sk-test-only-" + "0".repeat(28))
            runBlocking {
                graph.settings.flag("transcribeOnPlayback", false)
                graph.settings.flag("headphoneVoice", true)
                graph.db.library().putPodcast(PodcastEntity(id, "https://example.invalid/$id", "Headphone voice fixture"))
                for (suffix in listOf("a", "b")) graph.db.library().putEpisode(EpisodeEntity("$id-$suffix", id, suffix,
                    "Synthetic episode", "https://example.invalid/$suffix", durationMs = 180000,
                    localFile = file.path, downloadState = "downloaded", mediaFingerprint = id))
                // No transcript: the coordinator fails locally before routing audio or opening a provider.
            }
            val component = ComponentName(context, PlaybackService::class.java)
            val future = main { MediaController.Builder(context, SessionToken(context, component)).buildAsync() }
            val player = future.get(15, TimeUnit.SECONDS).also { controller = it }
            val diagnostic = {
                main {
                    "voice=${graph.voice.state.value}, shortcutReady=${graph.headphoneVoice.ready.value}, " +
                        "car=${graph.carConnection.value}, episode=${player.currentMediaItem?.mediaId}, " +
                        "position=${player.currentPosition}, playing=${player.isPlaying}, " +
                        "playWhenReady=${player.playWhenReady}, playbackState=${player.playbackState}"
                }
            }
            // Android 15 requires a visible Activity or an existing FGS before requesting audio focus.
            device.wakeUp()
            instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            main {
                player.setMediaItems(listOf("a", "b").map { MediaItem.Builder().setMediaId("$id-$it").setUri(android.net.Uri.fromFile(file)).build() }, 0, 75000)
                player.prepare(); player.play()
            }
            await(diagnostic) { graph.headphoneVoice.ready.value && main { player.isPlaying } && main {
                graph.carConnection.value == androidx.car.app.connection.CarConnection.CONNECTION_TYPE_NOT_CONNECTED
            } }
            assertTrue(context.getSystemService(android.media.AudioManager::class.java).activeRecordingConfigurations.isEmpty())
            device.sleep(); assertFalse(device.isScreenOn)
            instrumentation.uiAutomation.executeShellCommand("input keyevent 87").use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
            await(diagnostic) {
                graph.voice.state.value.error != null && !graph.voice.state.value.active && main { player.isPlaying }
            }
            assertEquals("$id-a", main { player.currentMediaItem!!.mediaId })
            assertTrue(main { player.currentPosition in 75000..100000 })
            assertTrue(graph.headphoneVoice.ready.value)
            assertFalse(device.isScreenOn)
            assertTrue(context.getSystemService(android.media.AudioManager::class.java).activeRecordingConfigurations.isEmpty())
            runBlocking { graph.settings.flag("headphoneVoice", false) }
            await { !graph.headphoneVoice.ready.value && !graph.preferences.value.headphoneVoice }
            // Legacy notification sessions can omit skip; ordinary modern transport remains usable.
            main { player.seekToNextMediaItem() }
            await { main { player.currentMediaItem?.mediaId == "$id-b" } }
        } finally {
            device.wakeUp()
            main { HeadphoneVoiceService.stop(context); graph.voice.cancel(false); controller?.pause(); controller?.release() }
            scenario?.close()
            graph.voiceApiKey.clear()
            runBlocking {
                graph.settings.flag("headphoneVoice", prefs.headphoneVoice)
                graph.settings.flag("transcribeOnPlayback", prefs.transcribeOnPlayback)
                graph.db.library().deletePodcast(id)
            }
            file.delete()
            if (!wasScreenOn) device.sleep()
        }
    }
}
