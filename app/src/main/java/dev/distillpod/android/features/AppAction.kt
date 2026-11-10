package dev.distillpod.android.features

import dev.distillpod.android.data.safeFailure
import dev.distillpod.android.data.AudioDeletionBlocked
import kotlinx.coroutines.CancellationException

internal suspend fun runAppAction(
    block: suspend () -> Unit,
    reportFailure: (Exception) -> Unit,
    showMessage: suspend (String) -> Unit,
) {
    try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (blocked: AudioDeletionBlocked) { showMessage(blocked.reason.feedback) }
    catch (error: Exception) { reportFailure(error); showMessage(safeFailure(error)) }
}
