package dev.distillpod.android.data

import org.json.JSONObject

// Stored update-check results. Only a build with the update check (the default; see
// docs/android/app-updates.md) writes them: ReleaseFeed and UpdateChecker live in src/updateCheckOn (#320).

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

/** The daily check's unique WorkManager name; a build without the check still cancels it. */
internal const val UPDATE_CHECK_WORK = "update-check"

/** Extra on MainActivity intents that opens Settings › About. */
const val OPEN_ABOUT_EXTRA = "openAbout"
