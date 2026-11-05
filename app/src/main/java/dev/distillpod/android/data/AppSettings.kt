package dev.distillpod.android.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import org.json.JSONArray

internal fun searchHistory(raw: String): List<String> = runCatching {
    val items = JSONArray(raw)
    (0 until items.length()).map { items.getString(it).trim() }.filter { it.length in 2..200 && !it.startsWith("http://", true) && !it.startsWith("https://", true) }
        .distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(6)
}.getOrDefault(emptyList())

private val Context.settingsDataStore by preferencesDataStore("settings")

data class Preferences(
    val model: String = "tiny", val language: String = "auto", val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false, val refresh: Boolean = true, val skipAds: Boolean = false,
    val storageLimitBytes: Long = 2L * 1024 * 1024 * 1024, val selectedAiModel: String = "",
    val recommendations: Boolean = false, val onlineTranscripts: Boolean = false,
    val transcribeOnPlayback: Boolean = true,
)

class AppSettings(context: Context, private val store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> = context.applicationContext.settingsDataStore, private val db:AppDatabase?=null, private val portableChanged:()->Unit={}) {
    private val localFlow = store.data.map { p -> Preferences(
        p[stringPreferencesKey("model")] ?: "tiny", p[stringPreferencesKey("language")] ?: "auto",
        p[booleanPreferencesKey("wifi")] ?: true, p[booleanPreferencesKey("charging")] ?: false,
        p[booleanPreferencesKey("refresh")] ?: true, p[booleanPreferencesKey("skipAds")] ?: false,
        p[longPreferencesKey("storageLimit")] ?: (2L * 1024 * 1024 * 1024), p[stringPreferencesKey("aiModel")] ?: "",
        p[booleanPreferencesKey("recommendations")] ?: false, p[booleanPreferencesKey("onlineTranscripts")] ?: false,
        p[booleanPreferencesKey("transcribeOnPlayback")] ?: true,
    ) }
    val flow = if(db==null)localFlow else combine(localFlow,db.sync().observePreferences()) {local,rows->
        val p=rows.associate {it.name to it.value}
        local.copy(model=p["model"]?:local.model,language=p["language"]?:local.language,selectedAiModel=p["aiModel"]?:local.selectedAiModel)
    }
    suspend fun restorePortable(value: PortablePreferences) {
        PortablePreferences.parse(value.json())
        if(db!=null)for((name,text) in mapOf("model" to value.model,"language" to value.language,"aiModel" to value.aiModel))dev.distillpod.android.sync.CollectionSync(db,changed=portableChanged).preference(name,text)
        store.edit {
            it[stringPreferencesKey("model")] = value.model
            it[stringPreferencesKey("language")] = value.language
            it[stringPreferencesKey("aiModel")] = value.aiModel
        }
    }
    suspend fun text(key: String, value: String) {
        if(db!=null && key in dev.distillpod.core.sync.CollectionRecord.preferences) {
            dev.distillpod.android.sync.CollectionSync(db,changed=portableChanged).preference(key,value);return
        }
        store.edit { it[stringPreferencesKey(key)] = value } }
    suspend fun flag(key: String, value: Boolean) { store.edit { it[booleanPreferencesKey(key)] = value } }
    suspend fun storage(bytes: Long) { require(bytes >= 250_000_000); store.edit { it[longPreferencesKey("storageLimit")] = bytes } }
    fun value(key: String) = store.data.map { it[stringPreferencesKey(key)] ?: "" }
    val recentSearches = value("podcastSearches").map(::searchHistory)
    suspend fun rememberSearch(query: String) {
        val term = query.trim()
        if (term.length !in 2..200 || term.startsWith("http://", true) || term.startsWith("https://", true)) return
        store.edit { prefs ->
            val key = stringPreferencesKey("podcastSearches")
            prefs[key] = JSONArray((listOf(term) + searchHistory(prefs[key] ?: "")).distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(6)).toString()
        }
    }
    suspend fun clearSearches() { store.edit { it.remove(stringPreferencesKey("podcastSearches")) } }
    suspend fun createJob(name: String, request: String): Boolean {
        var accepted = false
        store.edit { prefs ->
            if (prefs[stringPreferencesKey("${name}_state")] !in listOf("queued", "running")) {
                prefs[stringPreferencesKey("${name}_request")] = request
                prefs[stringPreferencesKey("${name}_state")] = "queued"
                prefs[stringPreferencesKey("${name}_error")] = ""; accepted = true
            }
        }
        return accepted
    }
    suspend fun jobState(name: String, request: String, state: String, error: String = "", from: String? = null): Boolean {
        var changed = false
        store.edit { prefs ->
            if (prefs[stringPreferencesKey("${name}_request")] == request && (from == null || prefs[stringPreferencesKey("${name}_state")] == from)) {
                prefs[stringPreferencesKey("${name}_state")] = state; prefs[stringPreferencesKey("${name}_error")] = error; changed = true
            }
        }
        return changed
    }
}
