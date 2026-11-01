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

class QuotaExceeded : IllegalStateException("ChatGPT usage limit reached. Manage usage or try later.")
class ReauthorizationRequired : IllegalStateException("Reconnect your ChatGPT account")
class SubscriptionProvider(private val baseUrl: HttpUrl = "https://api.openai.com/v1/".toHttpUrl(), private val client: OkHttpClient = OkHttpClient.Builder().readTimeout(180, TimeUnit.SECONDS).build()) {
    fun models(token: String): List<String> {
        val request = Request.Builder().url(baseUrl.resolve("models")!!).header("Authorization", "Bearer $token").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Unable to discover account models (${response.code})" }
            val body = JSONObject(response.body!!.string())
            val models = body.optJSONArray("models") ?: error("No account model catalog")
            return (0 until models.length()).map { models.getJSONObject(it) }.filter { it.optString("visibility") == "list" }.map { it.getString("slug") }
        }
    }
    fun respond(token: String, model: String, instructions: String, input: String): String {
        val body = JSONObject().put("model", model).put("instructions", instructions).put("store", false).put("stream", true)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", input)))
        val request = Request.Builder().url(baseUrl.resolve("responses")!!).header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 429) throw QuotaExceeded()
            if (response.code == 401 || response.code == 403) throw ReauthorizationRequired()
            check(response.isSuccessful) { "AI request failed (${response.code})" }
            val stream = ResponseStream()
            val data = mutableListOf<String>()
            fun dispatch() { if (data.isNotEmpty()) { val event = data.joinToString("\n"); if(event != "[DONE]") stream.accept(event); data.clear() } }
            response.body!!.charStream().buffered().useLines { lines -> lines.forEach { line ->
                if (line.isEmpty()) dispatch() else if(line.startsWith("data:")) data.add(line.substring(5).removePrefix(" "))
            } }
            dispatch()
            return stream.result()
        }
    }
}
