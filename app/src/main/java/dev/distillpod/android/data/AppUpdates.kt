package dev.distillpod.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A published DistillPod APK newer than the installed app. Links are project release URLs. */
data class AvailableUpdate(val versionName: String, val versionCode: Long, val pageUrl: String, val apkUrl: String, val summary: String) {
    fun json(): String = JSONObject().put("versionName", versionName).put("versionCode", versionCode)
        .put("pageUrl", pageUrl).put("apkUrl", apkUrl).put("summary", summary).toString()

    companion object {
        fun parse(raw: String?): AvailableUpdate? = runCatching {
            val j = JSONObject(raw ?: return null)
            AvailableUpdate(j.getString("versionName"), j.getLong("versionCode"), j.getString("pageUrl"), j.getString("apkUrl"), j.optString("summary"))
        }.getOrNull()
    }
}

/** What the About screen shows: whether daily checks run, the last result and when it was fetched. */
data class UpdateState(val enabled: Boolean = true, val available: AvailableUpdate? = null, val checkedAt: Long = 0)

/**
 * Reads the project's GitHub Releases, prereleases included, without an account. Only the release list and
 * the small build-info.json of one release are fetched; the APK itself is downloaded by the browser.
 * See docs/android/app-updates.md.
 */
class ReleaseFeed(private val http: OkHttpClient, private val releasesUrl: String = RELEASES_URL,
    private val trusted: (String) -> Boolean = ::isProjectRelease) {

    /**
     * The newest published release with an APK and a matching build record, or null when there is none.
     * Throws [IOException] when GitHub cannot be read, so a failed check never looks like "up to date".
     */
    suspend fun newest(): AvailableUpdate? = withContext(Dispatchers.IO) {
        val releases = try { JSONArray(get(releasesUrl, API_LIMIT)) } catch (e: org.json.JSONException) { throw IOException("Unreadable release list", e) }
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft", true)) continue
            val tag = release.optString("tag_name")
            val version = TAG.matchEntire(tag)?.groupValues?.get(1) ?: continue
            val assets = release.optJSONArray("assets") ?: continue
            fun asset(name: String) = (0 until assets.length()).mapNotNull(assets::optJSONObject)
                .firstOrNull { it.optString("name") == name }?.optString("browser_download_url")?.takeIf(trusted)
            // A release whose assets are still uploading has no APK or build record yet.
            val apk = asset("distillpod-$version.apk") ?: continue
            val info = asset("build-info.json") ?: continue
            val page = release.optString("html_url").takeIf(trusted) ?: continue
            val record = runCatching { JSONObject(get(info, INFO_LIMIT)) }.getOrNull() ?: continue
            if (record.optString("applicationId") != APPLICATION_ID || record.optString("tag") != tag ||
                record.optString("versionName") != version) continue
            val code = record.optLong("versionCode", 0)
            if (code <= 0) continue
            return@withContext AvailableUpdate(version, code, page, apk, summary(release.optString("body")))
        }
        null
    }

    private fun get(url: String, limit: Long): String {
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Release check failed with HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty release answer")
            val source = body.source()
            // Reads one byte past the limit to tell "exactly at the limit" from "too large".
            if (source.request(limit + 1)) throw IOException("Release answer is too large")
            return source.buffer.readUtf8()
        }
    }

    companion object {
        const val APPLICATION_ID = "dev.distillpod.android"
        const val RELEASES_URL = "https://api.github.com/repos/andrepaim/distillpod/releases?per_page=10"
        const val API_LIMIT = 1_000_000L
        const val INFO_LIMIT = 16_000L
        const val SUMMARY_MAX = 280
        private val TAG = Regex("v(\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.]+)?)")

        /** Only this repository's https release pages and downloads are ever opened. */
        fun isProjectRelease(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            return parsed.isHttps && parsed.host == "github.com" && parsed.encodedPath.startsWith("/andrepaim/distillpod/releases/")
        }

        /** The release notes' lead paragraph as plain text: the user-visible result, by the release checklist. */
        fun summary(body: String): String {
            val lead = body.replace("\r\n", "\n").trim().split(Regex("\n[ \t]*\n")).firstOrNull().orEmpty()
            val plain = lead.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1").replace(Regex("\\*\\*|__|`"), "")
                .replace(Regex("(?m)^[ \t]*(#+|[-*+])[ \t]+"), "").replace(Regex("\\s+"), " ").trim()
            if (plain.length <= SUMMARY_MAX) return plain
            val cut = plain.take(SUMMARY_MAX).substringBeforeLast(' ').trimEnd(',', ';', ':', ' ')
            return "$cut…"
        }
    }
}

/** Stores check results and announces each newer version once. */
class UpdateChecker(private val newest: suspend () -> AvailableUpdate?, private val settings: AppSettings,
    private val installedCode: Long, private val now: () -> Long = System::currentTimeMillis) {

    /** Fetches the newest release; keeps it only when it is newer than the installed app. Throws when GitHub cannot be read. */
    suspend fun check(): AvailableUpdate? {
        val update = newest()?.takeIf { it.versionCode > installedCode }
        settings.updateChecked(update, now())
        return update
    }

    /** True the first time [update] is offered for a notification. */
    suspend fun announce(update: AvailableUpdate): Boolean = settings.claimUpdateNotice(update.versionCode)
}
