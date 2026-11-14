package dev.distillpod.android.ai

import dev.distillpod.android.data.publicHttpUrl
import org.json.JSONArray
import org.json.JSONObject

/** A web source cited by an earlier infographic result. New posts make no web research. */
data class EvidenceSource(val id: Int, val title: String, val url: String) {
    fun json() = JSONObject().put("id", id).put("title", title).put("url", url)
    companion object {
        fun parse(j: JSONObject) = EvidenceSource(j.getInt("id"), j.getString("title"), j.getString("url")).also {
            require(it.id in 1..12 && it.title.length in 1..500 && it.url.length <= 4096)
            publicHttpUrl(it.url)
        }
        fun list(raw: String): List<EvidenceSource> {
            val j = JSONArray(raw); require(j.length() <= 12)
            return (0 until j.length()).map { parse(j.getJSONObject(it)) }.also { require(it.map(EvidenceSource::id).distinct().size == it.size) }
        }
    }
}
