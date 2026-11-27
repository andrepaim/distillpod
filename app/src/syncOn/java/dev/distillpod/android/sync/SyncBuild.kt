package dev.distillpod.android.sync

/**
 * This build has library sync (distillpodSyncEnabled, the default): Google sign-in, Firebase Authentication
 * and Firestore. Whether it can connect still depends on the Firebase client configuration it was built with.
 * See docs/android/subscription-sync.md.
 */
internal object SyncBuild {
    const val ENABLED = true

    /** Firestore could not be reached; the run waits for a connection instead of reporting an error. */
    fun transportUnavailable(error: Exception): Boolean = firestoreUnavailable(error)
}
