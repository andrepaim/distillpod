package dev.distillpod.android

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/** Register once during creation; onResume can precede the RESUMED lifecycle event. */
internal fun LifecycleOwner.observeTranscriptionRecovery(
    ready: suspend () -> Unit,
    recover: suspend () -> Unit,
) = lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.RESUMED) {
        ready()
        recover()
    }
}
