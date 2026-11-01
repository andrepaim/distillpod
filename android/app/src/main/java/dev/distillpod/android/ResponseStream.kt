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
            "response.output_text.delta" -> { check(!completed); text.append(event.getString("delta")) }
            "response.completed" -> {
                check(event.getJSONObject("response").getString("status") == "completed")
                completed = true
            }
            "response.failed", "response.incomplete", "error" -> { failed = true; error("Response did not complete") }
        }
    }
    fun result(): String { check(completed && !failed) { "Response stream interrupted" }; return text.toString() }
}
