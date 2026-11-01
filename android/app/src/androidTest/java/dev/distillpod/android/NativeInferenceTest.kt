package dev.distillpod.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.core.readWhisperWave
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
}
