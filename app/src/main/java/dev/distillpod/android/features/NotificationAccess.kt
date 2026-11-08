package dev.distillpod.android.features

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** The platform is the source of truth, including changes made outside this screen. */
@Composable
internal fun NotificationAccess() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val prefs = remember(context) { context.getSharedPreferences("notification_access", Context.MODE_PRIVATE) }
    var enabled by remember(context) { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var requested by remember(context) { mutableStateOf(prefs.getBoolean("runtime_requested", false)) }
    var runtimeGranted by remember(context) { mutableStateOf(notificationRuntimeGranted(context)) }
    fun refresh() {
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        requested = prefs.getBoolean("runtime_requested", false)
        runtimeGranted = notificationRuntimeGranted(context)
    }
    DisposableEffect(context, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val canRequest = Build.VERSION.SDK_INT >= 33 && !runtimeGranted && !requested
    val openSettings = {
        try {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Background progress", style = MaterialTheme.typography.titleMedium)
        Text(if (enabled) "Notifications are enabled for background progress."
            else "Allow notifications to see download and transcript progress outside the app.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!enabled) {
            if (canRequest) {
                OutlinedButton(onClick = {
                    // Record an attempt, not an inference about Android's denial flags.
                    prefs.edit().putBoolean("runtime_requested", true).apply()
                    requested = true
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Enable notifications") }
                TextButton(onClick = openSettings) { Text("Open notification settings") }
            } else OutlinedButton(onClick = openSettings) { Text("Open notification settings") }
        }
    }
}

private fun notificationRuntimeGranted(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
