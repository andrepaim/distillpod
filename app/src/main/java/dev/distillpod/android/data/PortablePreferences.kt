package dev.distillpod.android.data

import org.json.JSONObject

/** Deliberately excludes credentials, jobs, network constraints and device storage settings. */
data class PortablePreferences(val model: String, val language: String, val aiModel: String) {
    internal fun json() = JSONObject().put("model", model).put("language", language).put("aiModel", aiModel)
    companion object {
        fun from(preferences: Preferences) = PortablePreferences(preferences.model, preferences.language, preferences.selectedAiModel)
        internal fun parse(value: JSONObject): PortablePreferences {
            require(value.keys().asSequence().toSet() == setOf("model", "language", "aiModel")) { "Unsupported backup preferences" }
            val model = value.get("model"); val language = value.get("language"); val aiModel = value.get("aiModel")
            require(model is String && model in setOf("tiny", "base") && language is String && language in setOf("auto", "en", "pt") &&
                aiModel is String && aiModel.length <= 200 && aiModel.none { it.isISOControl() }) { "Invalid backup preferences" }
            return PortablePreferences(model, language, aiModel)
        }
    }
}
