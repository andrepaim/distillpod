package dev.distillpod.android.playback

import kotlinx.coroutines.CancellationException

/**
 * Runs the playback service's startup restore: [load] reads the persisted queue, [apply] puts it in
 * the player and [release] leaves the restoring state and completes playback readiness.
 *
 * [release] always runs, so a failed restore (for example an episode deleted mid-start or an
 * unreadable library) cannot leave playback restoring with Android Auto and queue writers waiting
 * forever (#148). A failure is reported through [onFailure] instead of escaping the service scope;
 * cancellation propagates. Returns whether the restore was applied.
 */
internal suspend fun <T> runStartupRestore(load: suspend () -> T, apply: (T) -> Unit,
    onFailure: (Exception) -> Unit, release: () -> Unit): Boolean = try {
    apply(load())
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    onFailure(error)
    false
} finally {
    release()
}
