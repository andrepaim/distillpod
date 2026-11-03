package dev.distillpod.android

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import dev.distillpod.core.readWhisperWave

@RunWith(AndroidJUnit4::class)
class PodcastBenchmarkDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun realEnglishAndPortugueseClipsOnTinyAndBase() = runBlocking {
        assumeTrue("Manual public-podcast benchmark only", InstrumentationRegistry.getArguments().getString("allowBenchmarks") == "true")
        val graph = compose.activity.applicationContext.appGraph
        graph.ready.await()
        val network = graph.context.getSystemService(ConnectivityManager::class.java)
        val base = File(graph.media.modelsDirectory, "base.bin")
        if (!base.exists()) {
            assertTrue("Use an unmetered network for the Base model", network.getNetworkCapabilities(network.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true)
            graph.media.downloadModel("base")
        }
        val results = JSONArray()
        for (modelId in listOf("tiny", "base")) {
            val model = graph.media.verifiedModel(modelId)
            graph.activeFiles.acquire(model)
            try { for (language in listOf("en", "pt")) {
                val samples = readWhisperWave(InstrumentationRegistry.getInstrumentation().context.assets.open("$language-90s.wav").use { it.readBytes() })
                val power = graph.context.getSystemService(PowerManager::class.java)
                val beforeThermal = power.currentThermalStatus
                var pss = Debug.getPss()
                var heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                var native = Debug.getNativeHeapAllocatedSize()
                val sampler = launch(Dispatchers.IO) { while (isActive) {
                    pss = maxOf(pss, Debug.getPss()); heap = maxOf(heap, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                    native = maxOf(native, Debug.getNativeHeapAllocatedSize()); delay(200)
                } }
                val start = SystemClock.elapsedRealtime()
                val output = try { withContext(Dispatchers.Default) { NativeWhisper().transcribe(model.absolutePath, samples, language) } } finally { sampler.cancelAndJoin() }
                val elapsed = SystemClock.elapsedRealtime() - start
                val segments = JSONArray(output)
                assertTrue("Real speech should produce timed segments", segments.length() > 0)
                val text = (0 until segments.length()).joinToString(" ") { segments.getJSONObject(it).getString("text") }
                assertTrue(text.length > 20)
                File(graph.context.cacheDir, "benchmark-$language-$modelId.json").writeText(output)
                results.put(JSONObject().put("model", modelId).put("language", language).put("audioMs", samples.size * 1000L / 16000)
                    .put("elapsedMs", elapsed).put("realTimeFactor", elapsed * 16.0 / samples.size).put("segments", segments.length())
                    .put("characters", text.length).put("sampledPeakPssKiB", pss).put("sampledPeakManagedBytes", heap).put("sampledPeakNativeBytes", native)
                    .put("thermalBefore", beforeThermal).put("thermalAfter", power.currentThermalStatus))
            } } finally { graph.activeFiles.release(model) }
        }
        File(graph.context.cacheDir, "podcast-benchmark.json").writeText(JSONObject().put("device", "Samsung S22 Ultra SM-S908E")
            .put("date", "2026-11-02").put("memorySamplingMs", 200).put("results", results).toString(2))
    }
}
