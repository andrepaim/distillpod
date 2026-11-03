package dev.distillpod.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.core.readWhisperWave
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeInferenceTest {
    @Test fun realModelTranscribesJfkUsingPackagedJni() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val model = File(context.cacheDir, "device-test-model.bin")
        try {
            instrumentation.context.assets.open("ggml-tiny.en.bin").use { input -> model.outputStream().use(input::copyTo) }
            val samples = instrumentation.context.assets.open("jfk.wav").use { readWhisperWave(it.readBytes()) }
            val begin = System.nanoTime()
            val raw = NativeWhisper().transcribe(model.absolutePath, samples, "en")
            val segments = JSONArray(raw)
            assertTrue(segments.length() > 0)
            val text = (0 until segments.length()).joinToString(" ") { segments.getJSONObject(it).getString("text") }
            assertTrue(text.lowercase().contains("country"))
            for(i in 0 until segments.length()) {
                val segment = segments.getJSONObject(i)
                assertTrue(segment.getLong("startMs") >= 0)
                assertTrue(segment.getLong("endMs") >= segment.getLong("startMs"))
            }
            println("DISTILLPOD_NATIVE_INFERENCE_MS=" + (System.nanoTime() - begin) / 1_000_000)
            File(context.filesDir, "device-test-transcript.json").writeText(raw)
        } finally { model.delete() }
    }

    @Test fun invalidModelRaisesJavaExceptionInsteadOfNativeCrash() {
        assertThrows(IllegalStateException::class.java) { NativeWhisper().transcribe("/missing/model.bin", FloatArray(16000), "en") }
    }

    @Test fun cancellationBeforeStartDoesNotLoadTheModel() {
        val whisper = NativeWhisper()
        whisper.cancel()
        assertThrows(CancellationException::class.java) { whisper.transcribe("/missing/model.bin", FloatArray(16000), "en") }
    }

    @Test fun activeNativeCallCancelsAndAnotherOperationStillSucceeds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val model = File.createTempFile("cancellation-model-", ".bin", context.cacheDir)
        val whisper = NativeWhisper()
        var thread: Thread? = null
        try {
            instrumentation.context.assets.open("ggml-tiny.en.bin").use { input -> model.outputStream().use(input::copyTo) }
            val samples = instrumentation.context.assets.open("jfk.wav").use { readWhisperWave(it.readBytes()) }
            val longClip = FloatArray(16000 * 120) { samples[it % samples.size] }
            val started = CountDownLatch(1)
            val task = FutureTask {
                started.countDown()
                try {
                    whisper.transcribe(model.absolutePath, longClip, "en")
                    false
                } catch (_: CancellationException) { true }
            }
            thread = Thread(task, "cancellation-test").also { it.start() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            // Allow model loading and encoder entry before cancelling a long clip.
            Thread.sleep(500)
            val begin = System.nanoTime()
            whisper.cancel()
            assertTrue("Cancelled inference must not publish a completed result", task.get(10, TimeUnit.SECONDS))
            println("DISTILLPOD_NATIVE_CANCELLATION_MS=" + (System.nanoTime() - begin) / 1_000_000)
            val result = NativeWhisper().transcribe(model.absolutePath, samples, "en")
            assertTrue(result.lowercase().contains("country"))
        } finally {
            whisper.cancel()
            thread?.join(10000)
            model.delete()
        }
    }
}
