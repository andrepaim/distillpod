package dev.distillpod.android.sync

/**
 * This build has no library sync (distillpodSyncEnabled=false, the Play preset): no Google sign-in, no Firebase
 * and no cloud account, so nothing of the library leaves the phone through sync. See docs/android/subscription-sync.md.
 */
internal object SyncBuild {
    const val ENABLED = false

    @Suppress("UNUSED_PARAMETER")
    fun transportUnavailable(error: Exception): Boolean = false
}
