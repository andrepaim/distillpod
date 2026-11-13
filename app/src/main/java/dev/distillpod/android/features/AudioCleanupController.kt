package dev.distillpod.android.features

import dev.distillpod.android.data.AudioCleanupPreview
import dev.distillpod.android.data.AudioCleanupResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class AudioCleanupPhase { IDLE, REVIEWING, READY, RUNNING, CANCELLING, FINISHED, FAILED }
internal data class AudioCleanupState(
    val phase: AudioCleanupPhase = AudioCleanupPhase.IDLE,
    val preview: AudioCleanupPreview? = null,
    val result: AudioCleanupResult? = null,
    val cancelled: Boolean = false,
    val error: String? = null,
    val refreshVersion: Long = 0,
) {
    val running get() = phase == AudioCleanupPhase.RUNNING || phase == AudioCleanupPhase.CANCELLING
}

/** In-memory intent only. Dismissing a review invalidates late responses, never a new review. */
internal class AudioCleanupController(
    private val scope: CoroutineScope,
    private val review: suspend () -> AudioCleanupPreview,
    private val apply: suspend (AudioCleanupPreview, (AudioCleanupResult) -> Unit) -> AudioCleanupResult,
    private val report: (Throwable) -> Unit,
    private val finished: (AudioCleanupState) -> Unit = {},
) {
    private val mutable = MutableStateFlow(AudioCleanupState())
    val state = mutable.asStateFlow()
    @Volatile private var generation = 0L
    private var job: Job? = null

    fun review() {
        if (mutable.value.running) return
        val request = ++generation
        job?.cancel()
        mutable.value = AudioCleanupState(phase = AudioCleanupPhase.REVIEWING,
            refreshVersion = mutable.value.refreshVersion)
        val pending = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val preview = review.invoke()
                if (generation == request) mutable.update { it.copy(phase = AudioCleanupPhase.READY, preview = preview) }
            } catch (cancelled: CancellationException) {
                if (generation == request) mutable.update { it.copy(phase = AudioCleanupPhase.IDLE, preview = null) }
                throw cancelled
            } catch (failure: Exception) {
                if (generation == request) {
                    mutable.update { it.copy(phase = AudioCleanupPhase.FAILED, error = "Audio could not be reviewed. Try again.") }
                    report(failure)
                }
            } finally {
                if (generation == request) job = null
            }
        }
        job = pending
        pending.start()
    }

    fun dismissReview() {
        if (mutable.value.running) return
        ++generation
        job?.cancel()
        job = null
        mutable.update { it.copy(phase = AudioCleanupPhase.IDLE, preview = null, error = null) }
    }

    fun confirm() {
        val current = mutable.value
        val preview = current.preview ?: return
        if (current.phase != AudioCleanupPhase.READY || !preview.canApply) return
        val request = ++generation
        mutable.value = current.copy(phase = AudioCleanupPhase.RUNNING, result = null, error = null)
        // Publish the job before it runs: synchronous progress callbacks may cancel it.
        val pending = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = apply(preview) { progress ->
                    if (generation == request) mutable.update { it.copy(result = progress) }
                }
                if (generation == request) complete(result, result.cancelled)
            } catch (cancelled: CancellationException) {
                if (generation == request) complete(preview.latestResult, true)
                throw cancelled
            } catch (failure: Exception) {
                if (generation == request) {
                    val progress = preview.latestResult
                    mutable.update { it.copy(phase = AudioCleanupPhase.FAILED, preview = null, result = progress,
                        error = "Audio cleanup could not finish. Review again to retry.", refreshVersion = it.refreshVersion + 1) }
                    report(failure)
                    finished(mutable.value)
                }
            } finally {
                if (generation == request) job = null
            }
        }
        job = pending
        pending.start()
    }

    fun cancel() {
        if (!mutable.value.running) { dismissReview(); return }
        if (mutable.value.phase == AudioCleanupPhase.CANCELLING) return
        mutable.update { it.copy(phase = AudioCleanupPhase.CANCELLING) }
        job?.cancel()
    }

    fun leaveScreen() {
        if (mutable.value.phase in setOf(AudioCleanupPhase.REVIEWING, AudioCleanupPhase.READY)) dismissReview()
    }

    private fun complete(result: AudioCleanupResult?, cancelled: Boolean) {
        mutable.update { it.copy(phase = AudioCleanupPhase.FINISHED, preview = null, result = result,
            cancelled = cancelled, refreshVersion = it.refreshVersion + 1) }
        result?.errors?.firstOrNull()?.let(report)
        finished(mutable.value)
    }
}
