package dev.distillpod.android.features

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val IN_CONTEXT_OFFERED = "in_context_offered"

/**
 * One in-context offer of notification access (#193), made after the first download, model download
 * or transcript preparation the user started has been queued. Android stays the source of truth:
 * nothing is offered when notifications are on, before Android 13 (no runtime request exists), or
 * once the runtime request was used here or in Settings → Notification access (#20's marker).
 * An answer, including "Not now", is never asked again. Work never waits for it.
 */
internal class NotificationPrompt(private val context: Context) {
    private val prefs by lazy { context.getSharedPreferences(NOTIFICATION_ACCESS_PREFS, Context.MODE_PRIVATE) }
    private val pending = MutableStateFlow(false)
    val offered: StateFlow<Boolean> = pending.asStateFlow()

    fun workStarted() {
        if (pending.value || Build.VERSION.SDK_INT < 33 || notificationRuntimeGranted(context) ||
            NotificationManagerCompat.from(context).areNotificationsEnabled() ||
            prefs.getBoolean(RUNTIME_REQUESTED, false) || prefs.getBoolean(IN_CONTEXT_OFFERED, false)) return
        pending.value = true
    }
    /** Just before Android's dialog; Settings then offers recovery instead of a second request. */
    fun requesting() { prefs.edit().putBoolean(RUNTIME_REQUESTED, true).apply(); declined() }
    fun declined() { prefs.edit().putBoolean(IN_CONTEXT_OFFERED, true).apply(); pending.value = false }
}

@Composable
internal fun NotificationPromptHost(prompt: NotificationPrompt, snackbar: SnackbarHostState, onOpenSettings: () -> Unit) {
    val offered by prompt.offered.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val openSettings by rememberUpdatedState(onOpenSettings)
    // Shown once, right after the answer; Settings → Notification access remains the way back.
    val quietLink = {
        scope.launch {
            if (snackbar.showSnackbar("Notifications are off. Progress still shows here; turn them on any time in Settings.", "Settings",
                    duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) openSettings()
        }
        Unit
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) quietLink()
    }
    if (!offered) return
    val notNow = { prompt.declined(); quietLink() }
    AlertDialog(onDismissRequest = notNow,
        title = { Text("Show progress outside the app?") },
        text = {
            Text("Notifications let you follow downloads and transcript preparation while you use other apps. " +
                "They continue either way.")
        },
        confirmButton = {
            TextButton(onClick = { prompt.requesting(); permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text("Allow notifications")
            }
        },
        dismissButton = { TextButton(onClick = notNow) { Text("Not now") } })
}
