package dev.distillpod.android.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dev.distillpod.core.FirstRunSetup
import dev.distillpod.core.FirstRunStatus
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import org.json.JSONArray

internal fun searchHistory(raw: String): List<String> = runCatching {
    val items = JSONArray(raw)
    (0 until items.length()).map { items.getString(it).trim() }.filter { it.length in 2..200 && !it.startsWith("http://", true) && !it.startsWith("https://", true) }
        .distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(6)
}.getOrDefault(emptyList())

private val Context.settingsDataStore by preferencesDataStore("settings")
private val firstRunStatusKey = stringPreferencesKey("firstRunStatus")
private val setupCardDismissedKey = booleanPreferencesKey("setupCardDismissed")

private fun androidx.datastore.preferences.core.Preferences.firstRunSetup(): FirstRunSetup? =
    when (this[firstRunStatusKey]) {
        null -> null
        "pending" -> FirstRunSetup(FirstRunStatus.PENDING, this[setupCardDismissedKey] ?: false)
        "done" -> FirstRunSetup(FirstRunStatus.DONE, this[setupCardDismissedKey] ?: false)
        else -> FirstRunSetup.ExistingInstall
    }

data class Preferences(
    val model: String = "tiny", val language: String = "auto", val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false, val refresh: Boolean = true, val skipAds: Boolean = false,
    val storageLimitBytes: Long = 2L * 1024 * 1024 * 1024, val selectedAiModel: String = "",
    val recommendations: Boolean = false, val onlineTranscripts: Boolean = false,
    val transcribeOnPlayback: Boolean = true, val distillSound: Boolean = true,
    val headphoneDistill: Boolean = false,
    val headphoneVoice: Boolean = false,
    val refreshHours: Int = 6,
    val skipSilence: Boolean = false,
    val volumeBoost: Boolean = false,
)

/** Stored per-show speeds, keyed by podcast id; unsupported entries are ignored. */
internal fun showSpeeds(raw: String?): Map<String, Float> = runCatching {
    val json = org.json.JSONObject(raw ?: return emptyMap())
    json.keys().asSequence().mapNotNull { key ->
        val value = json.opt(key) as? Number ?: return@mapNotNull null
        dev.distillpod.core.PlaybackSpeeds.normalize(value.toFloat())?.let { key to it }
    }.toMap()
}.getOrDefault(emptyMap())

class AppSettings(context: Context, private val store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> = context.applicationContext.settingsDataStore, private val db:AppDatabase?=null, private val portableChanged:()->Unit={}) {
    // First-run state stays device-local and is never part of PortablePreferences.
    internal val firstRunSetup = store.data.map { it.firstRunSetup() }

    internal suspend fun initializeFirstRunSetup(initial: FirstRunSetup): FirstRunSetup =
        checkNotNull(store.edit { prefs ->
            if (prefs[firstRunStatusKey] == null) {
                prefs[firstRunStatusKey] = if (initial.status == FirstRunStatus.PENDING) "pending" else "done"
                prefs[setupCardDismissedKey] = initial.cardDismissed
            }
        }.firstRunSetup())

    internal suspend fun completeFirstRunSetup(): FirstRunSetup = checkNotNull(store.edit { prefs ->
        if (prefs[firstRunStatusKey] == "pending") prefs[firstRunStatusKey] = "done"
    }.firstRunSetup())

    internal suspend fun dismissSetupCard(): FirstRunSetup = checkNotNull(store.edit { prefs ->
        if (prefs[firstRunStatusKey] == "done") prefs[setupCardDismissedKey] = true
    }.firstRunSetup())

    val shows = store.data.map { p -> ShowPreferences(
        format = ShowFormat.entries.firstOrNull { it.name == p[stringPreferencesKey("showsFormat")] } ?: ShowFormat.ALL,
        sort = ShowSort.entries.firstOrNull { it.name == p[stringPreferencesKey("showsSort")] } ?: ShowSort.TITLE,
        pinnedFeeds = p[stringSetPreferencesKey("pinnedShows")].orEmpty().toSet(),
    ) }
    suspend fun showFormat(format: ShowFormat) { store.edit { it[stringPreferencesKey("showsFormat")] = format.name } }
    suspend fun showSort(sort: ShowSort) { store.edit { it[stringPreferencesKey("showsSort")] = sort.name } }
    suspend fun pinShow(feed: String, pinned: Boolean) {
        require(feed.isNotBlank())
        store.edit { p ->
            val key = stringSetPreferencesKey("pinnedShows")
            val existing = p[key].orEmpty()
            p[key] = if (pinned) existing + feed else existing - feed
        }
    }
    private val localFlow = store.data.map { p -> Preferences(
        p[stringPreferencesKey("model")] ?: "tiny", p[stringPreferencesKey("language")] ?: "auto",
        p[booleanPreferencesKey("wifi")] ?: true, p[booleanPreferencesKey("charging")] ?: false,
        p[booleanPreferencesKey("refresh")] ?: true, p[booleanPreferencesKey("skipAds")] ?: false,
        p[longPreferencesKey("storageLimit")] ?: (2L * 1024 * 1024 * 1024), p[stringPreferencesKey("aiModel")] ?: "",
        p[booleanPreferencesKey("recommendations")] ?: false, p[booleanPreferencesKey("onlineTranscripts")] ?: false,
        p[booleanPreferencesKey("transcribeOnPlayback")] ?: true,
        p[booleanPreferencesKey("distillSound")] ?: true,
        p[booleanPreferencesKey("headphoneDistill")] ?: false,
        p[booleanPreferencesKey("headphoneVoice")] ?: false,
        (p[intPreferencesKey("refreshHours")] ?: 6).coerceIn(1, 168),
        p[booleanPreferencesKey("skipSilence")] ?: false,
        p[booleanPreferencesKey("volumeBoost")] ?: false,
    ) }
    private fun speeds(p: androidx.datastore.preferences.core.Preferences) = dev.distillpod.core.SpeedPreferences(
        p[floatPreferencesKey("playbackSpeed")]?.let(dev.distillpod.core.PlaybackSpeeds::normalize),
        showSpeeds(p[stringPreferencesKey("showSpeeds")]))
    /** Device-local playback speeds (#183): not part of backup or sync, like show pins. */
    val speeds = store.data.map(::speeds)
    suspend fun updateSpeeds(transform: (dev.distillpod.core.SpeedPreferences) -> dev.distillpod.core.SpeedPreferences) {
        store.edit { p ->
            val next = transform(speeds(p))
            next.global?.let { p[floatPreferencesKey("playbackSpeed")] = it } ?: p.remove(floatPreferencesKey("playbackSpeed"))
            p[stringPreferencesKey("showSpeeds")] = org.json.JSONObject().apply { next.shows.forEach { (show, speed) -> put(show, speed.toDouble()) } }.toString()
        }
    }
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
    suspend fun refreshHours(hours: Int) {
        require(hours in 1..168) { "Choose an interval from 1 to 168 hours" }
        store.edit { it[intPreferencesKey("refreshHours")] = hours }
    }
    /**
     * Library refresh warnings the owner dismissed (#264), as [dev.distillpod.android.features.feedProblemKey]s.
     * Device-local, like show pins: not part of backup or sync. Each dismissal replaces the whole set with the
     * warning's current keys, so shows that have since refreshed drop out of it.
     */
    val dismissedFeedProblems = store.data.map { it[stringSetPreferencesKey("dismissedFeedProblems")].orEmpty().toSet() }
    suspend fun dismissFeedProblems(keys: Set<String>) { store.edit { it[stringSetPreferencesKey("dismissedFeedProblems")] = keys } }
    /** Device-local, like show pins: not part of backup or sync. See docs/android/app-updates.md. */
    val updates = store.data.map { p -> UpdateState(p[booleanPreferencesKey("updateChecks")] ?: true,
        AvailableUpdate.parse(p[stringPreferencesKey("updateAvailable")]), p[longPreferencesKey("updateCheckedAt")] ?: 0) }
    suspend fun updateChecked(update: AvailableUpdate?, at: Long) { store.edit { p ->
        if (update == null) p.remove(stringPreferencesKey("updateAvailable")) else p[stringPreferencesKey("updateAvailable")] = update.json()
        p[longPreferencesKey("updateCheckedAt")] = at
    } }
    /** True when no version this new was announced before; records it. */
    suspend fun claimUpdateNotice(versionCode: Long): Boolean {
        var claimed = false
        store.edit { p ->
            val key = longPreferencesKey("updateNotified")
            if ((p[key] ?: 0) < versionCode) { p[key] = versionCode; claimed = true }
        }
        return claimed
    }
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
