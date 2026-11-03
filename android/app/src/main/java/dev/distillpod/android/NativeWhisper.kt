package dev.distillpod.android

import java.util.concurrent.CancellationException

/** One cancellable operation. A cancelled instance cannot be restarted. */
class NativeWhisper {
    companion object { init { System.loadLibrary("distillpod_whisper") } }
    private var operation = 0L
    private var cancelled = false
    private var progress: ((Int) -> Unit)? = null

    @Suppress("unused") // Invoked by JNI on the inference thread.
    private fun reportProgress(percent: Int) { progress?.invoke(percent.coerceIn(0, 100)) }

    @Synchronized fun cancel() {
        cancelled = true
        if (operation != 0L) cancelOperation(operation)
    }

    @Synchronized fun checkActive() {
        if (cancelled) throw CancellationException("Transcription cancelled")
    }

    fun transcribe(modelPath: String, samples: FloatArray, language: String, onProgress: ((Int) -> Unit)? = null): String {
        val handle = synchronized(this) {
            checkActive()
            check(operation == 0L) { "Transcription is already running" }
            progress = onProgress
            createOperation().also { operation = it }
        }
        try {
            val result = transcribeOperation(handle, modelPath, samples, language)
            checkActive()
            return result
        } finally {
            synchronized(this) { releaseOperation(handle); operation = 0L; progress = null }
        }
    }

    private external fun createOperation(): Long
    private external fun cancelOperation(handle: Long)
    private external fun releaseOperation(handle: Long)
    private external fun transcribeOperation(handle: Long, modelPath: String, samples: FloatArray, language: String): String
}
