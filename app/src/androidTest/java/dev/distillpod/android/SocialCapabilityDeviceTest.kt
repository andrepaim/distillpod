package dev.distillpod.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit

/** Explicit live-account probe, public prompts only. Never writes library data or logs credentials. */
class SocialCapabilityDeviceTest {
    @Test fun probeSearchAndImageTools() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowLiveAccount") == "true")
        withContext(Dispatchers.IO) {
            val graph = InstrumentationRegistry.getInstrumentation().targetContext.appGraph
            graph.ready.await()
            val token = graph.account.session.accessToken()
            val models = SubscriptionProvider().models(token)
            val selected = graph.settings.flow.first().selectedAiModel
            val model = selected.takeIf { it in models } ?: models.first()
            val report = JSONObject().put("model", model).put("ownerHistorySent", false)
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(300, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()
            for ((tool, prompt) in listOf("web_search" to "Search the web for official Android documentation about sharing files. Open the source and briefly explain content URI permissions with a citation.",
                "image_generation" to "Create a simple editorial illustration of a seed growing into a leafy plant, on a warm cream background. No text.")) {
                val body = JSONObject().put("model", model).put("store", false).put("stream", true)
                    .put("instructions", "Follow the public test request using the specified tool.").put("input", JSONArray().put(JSONObject().put("role", "user").put("content", prompt))).put("tools", JSONArray().put(JSONObject().put("type", tool)))
                    .put("tool_choice", JSONObject().put("type", tool))
                if (tool == "web_search") body.put("include", JSONArray().put("web_search_call.action.sources"))
                val status = JSONObject()
                try {
                    val request = Request.Builder().url("https://api.openai.com/v1/responses").header("Authorization", "Bearer $token")
                        .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                    client.newCall(request).execute().use { response ->
                        status.put("http", response.code)
                        if (!response.isSuccessful) {
                            val raw = response.body!!.string()
                            val error = runCatching { JSONObject(raw).optJSONObject("error") }.getOrNull()
                            status.put("detail", error?.optString("message")?.take(500) ?: raw.take(500))
                            status.put("code", error?.optString("code")).put("parameter", error?.optString("param"))
                        } else {
                            var completed: JSONObject? = null
                            val types = mutableSetOf<String>()
                            response.body!!.charStream().buffered().useLines { lines -> lines.forEach { line ->
                                if (line.startsWith("data: ") && line != "data: [DONE]") {
                                    val event = JSONObject(line.substring(6)); types += event.optString("type")
                                    if (event.optString("type") == "response.completed") completed = event.getJSONObject("response")
                                    if (event.optString("type") in listOf("response.failed", "error")) status.put("code", event.optJSONObject("response")?.optJSONObject("error")?.optString("code"))
                                }
                            } }
                            status.put("completed", completed?.optString("status") == "completed").put("eventTypes", JSONArray(types.toList()))
                            val outputs = completed?.optJSONArray("output") ?: JSONArray()
                            status.put("outputTypes", JSONArray((0 until outputs.length()).map { outputs.getJSONObject(it).optString("type") }))
                            // Public test output, retained privately for visual verification.
                            for (i in 0 until outputs.length()) {
                                val item = outputs.getJSONObject(i)
                                if (item.optString("type") == "image_generation_call" && item.optString("result").isNotBlank()) {
                                    File(graph.context.getExternalFilesDir(null), "social-probe-image.png").writeBytes(android.util.Base64.decode(item.getString("result"), android.util.Base64.DEFAULT))
                                }
                            }
                        }
                    }
                } catch (e: Exception) { status.put("exception", e.javaClass.simpleName) }
                report.put(tool, status)
                File(graph.context.getExternalFilesDir(null), "social-capability-probe.json").writeText(report.toString(2))
            }
        }
    }
}
