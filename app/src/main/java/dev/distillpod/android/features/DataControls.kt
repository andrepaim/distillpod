package dev.distillpod.android.features

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalLayoutApi::class)
@Composable fun DataControls(vm: AppViewModel) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val exportOpml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri -> uri?.let(vm::exportOpml) }
    val importOpml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importOpml) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = { vm.open("settings:sync") }) { Text("Library sync") }
        Text("Keep a portable copy in Google Drive or another location. Restore into this library without replacing existing items.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { vm.open("backup") }) { Text("Backup & Restore") }
        Text("Podcast subscriptions", style = MaterialTheme.typography.titleMedium)
        Text("OPML shares feed subscriptions with other podcast apps. It excludes listening history and saved moments.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exportOpml.launch("distillpod-subscriptions.opml") }, enabled = "opml" !in busy) { Text("Export OPML") }
            OutlinedButton(onClick = { importOpml.launch(arrayOf("text/*", "application/xml", "application/octet-stream")) }, enabled = "opml" !in busy) { Text("Import OPML") }
        }
    }
}

@Composable fun NotificationControls() {
    val context = LocalContext.current
    var notifications by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications = it }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Background progress", style = MaterialTheme.typography.titleMedium)
        Text(if (notifications) "Notifications are enabled for playback and processing." else "Enable notifications to see playback controls and download or transcript progress outside the app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!notifications) OutlinedButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
        }) { Text("Enable notifications") }
    }
}
