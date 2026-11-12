package dev.distillpod.android
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class AiRequestRejected(val status: Int) : IllegalStateException("The AI request was rejected ($status). Check the request or model settings.")
class AiServiceUnavailable(val status: Int) : IllegalStateException("The AI service is unavailable ($status). Try again later.")
class AccountEntitlementDenied : IllegalStateException("Your ChatGPT account does not have access to this feature.")
class QuotaExceeded : IllegalStateException("ChatGPT usage limit reached. Manage usage or try later.")
class AiRateLimited : IllegalStateException("The AI service is busy. Try again later.")
class ReauthorizationRequired : IllegalStateException("Reconnect your ChatGPT account")
private val quotaCodes = setOf("subscription_sharing_usage_limit_exceeded", "subscription_sharing_usage_unavailable", "insufficient_quota")

/** Retry only rejected HTTP requests, before consuming a response stream. */
fun executeAiRequest(client: OkHttpClient, request: Request, onCall: (okhttp3.Call) -> Unit = {}): okhttp3.Response {
    repeat(3) { attempt ->
        val call = client.newCall(request).also(onCall)
        val response = call.execute()
        if (response.code != 429) return response
        val code = runCatching { JSONObject(response.peekBody(8192).string()).optJSONObject("error")?.optString("code") }.getOrNull()
        if (code in quotaCodes) { response.close(); throw QuotaExceeded() }
        val header = response.header("Retry-After")
        val seconds = header?.toLongOrNull()?.coerceAtLeast(0)
        val date = if (header != null && seconds == null) runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(header)?.time
        }.getOrNull() else null
        val delay = seconds?.let { if (it > 300) 300001L else it * 1000 } ?: date?.let { (it - System.currentTimeMillis()).coerceAtLeast(0) } ?: (1000L shl attempt)
        response.close()
        if (attempt == 2 || delay > 300000) throw AiRateLimited()
        var remaining = delay
        while (remaining > 0) {
            if (call.isCanceled()) throw IOException("Canceled")
            val step = minOf(remaining, 100L)
            try { Thread.sleep(step) } catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Canceled", error) }
            remaining -= step
        }
        if (call.isCanceled()) throw IOException("Canceled")
    }
    throw AiRateLimited()
}
fun classifyAiResponse(code: Int) {
    when (code) {
        in 200..299 -> Unit
        401 -> throw ReauthorizationRequired()
        403 -> throw AccountEntitlementDenied()
        in 400..499 -> throw AiRequestRejected(code)
        else -> throw AiServiceUnavailable(code)
    }
}
class SubscriptionProvider(private val baseUrl: HttpUrl = "https://api.openai.com/v1/".toHttpUrl(), private val client: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(180, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()) {
    fun models(token: String, onCall: (okhttp3.Call) -> Unit = {}): List<String> {
        val request = Request.Builder().url(baseUrl.resolve("models")!!).header("Authorization", "Bearer $token").build()
        executeAiRequest(client, request, onCall).use { response ->
            classifyAiResponse(response.code)
            val body = JSONObject(response.body!!.string())
            val models = body.optJSONArray("models") ?: error("No account model catalog")
            return (0 until models.length()).map { models.getJSONObject(it) }.filter { it.optString("visibility") == "list" }.map { it.getString("slug") }
        }
    }
    fun respond(token: String, model: String, instructions: String, input: String, onCall: (okhttp3.Call) -> Unit = {}, onDelta: (String) -> Unit = {}): String {
        val body = JSONObject().put("model", model).put("instructions", instructions).put("store", false).put("stream", true)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", input)))
        val request = Request.Builder().url(baseUrl.resolve("responses")!!).header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        executeAiRequest(client, request, onCall).use { response ->
            classifyAiResponse(response.code)
            val stream = ResponseStream()
            val data = mutableListOf<String>()
            fun dispatch() { if (data.isNotEmpty()) { val event = data.joinToString("\n"); if(event != "[DONE]") { stream.accept(event); val parsed = JSONObject(event); if (parsed.optString("type") == "response.output_text.delta") onDelta(parsed.getString("delta")) }; data.clear() } }
            response.body!!.charStream().buffered().useLines { lines -> lines.forEach { line ->
                if (line.isEmpty()) dispatch() else if(line.startsWith("data:")) data.add(line.substring(5).removePrefix(" "))
            } }
            dispatch()
            return stream.result()
        }
    }
}
