package dev.distillpod.android
import org.json.JSONObject

class ResponseStream {
    private val text = StringBuilder()
    private var completed = false
    private var failed = false
    fun accept(data: String) {
        check(!failed) { "Response failed" }
        val event = JSONObject(data)
        when(event.optString("type")) {
            "response.output_text.delta" -> { check(!completed); text.append(event.getString("delta")); require(text.length <= 128000) { "Response exceeds the supported size" } }
            "response.completed" -> {
                check(event.getJSONObject("response").getString("status") == "completed")
                completed = true
            }
            "response.failed", "response.incomplete", "error" -> {
                failed = true
                val code = event.optJSONObject("response")?.optJSONObject("error")?.optString("code")
                    ?: event.optJSONObject("error")?.optString("code") ?: event.optString("code")
                if (code in listOf("subscription_sharing_usage_limit_exceeded", "subscription_sharing_usage_unavailable", "insufficient_quota")) throw QuotaExceeded()
                if (code == "rate_limit_exceeded") throw AiRateLimited()
                error("Response did not complete")
            }
        }
    }
    fun result(): String { check(completed && !failed) { "Response stream interrupted" }; return text.toString() }
}
