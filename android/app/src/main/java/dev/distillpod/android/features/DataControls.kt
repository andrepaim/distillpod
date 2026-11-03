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
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable fun DataControls(vm: AppViewModel) {
    val context = LocalContext.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    val preview by vm.backupPreview.collectAsStateWithLifecycle()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::exportBackup) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::previewBackup) }
    val exportOpml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri -> uri?.let(vm::exportOpml) }
    val importOpml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importOpml) }
    var notifications by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications = it }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Your library", style = MaterialTheme.typography.titleLarge)
        Text("Back up subscriptions, progress, queue, transcripts and saved text. Audio, model files and account credentials are excluded. The JSON file contains your library text; choose where to keep it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { export.launch("distillpod-${LocalDate.now()}.json") }, enabled = "backup" !in busy) { Text("Save backup") }
            OutlinedButton(onClick = { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = "backup" !in busy) { Text("Restore backup") }
        }
        if ("backup" in busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Podcast subscriptions", style = MaterialTheme.typography.titleMedium)
        Text("OPML shares feed subscriptions with other podcast apps. It excludes listening history and saved moments.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exportOpml.launch("distillpod-subscriptions.opml") }, enabled = "opml" !in busy) { Text("Export OPML") }
            OutlinedButton(onClick = { importOpml.launch(arrayOf("text/*", "application/xml", "application/octet-stream")) }, enabled = "opml" !in busy) { Text("Import OPML") }
        }
        Text("Background progress", style = MaterialTheme.typography.titleMedium)
        Text(if (notifications) "Notifications are enabled for playback and processing." else "Enable notifications to see playback controls and download or transcript progress outside the app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!notifications) OutlinedButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
        }) { Text("Enable notifications") }
    }
    preview?.let { item -> AlertDialog(onDismissRequest = { if ("backup" !in busy) vm.backupPreview.value = null }, title = { Text("Restore library backup?") },
        text = { Text("${item.episodes} episodes and ${item.moments} saved moments. ${item.conflicts} existing records will be kept. New items are merged into this library. Downloads must be fetched again; interrupted AI work requires an explicit retry.") },
        confirmButton = { TextButton(onClick = vm::restoreBackup, enabled = "backup" !in busy) { Text("Merge library") } },
        dismissButton = { TextButton(onClick = { vm.backupPreview.value = null }, enabled = "backup" !in busy) { Text("Cancel") } }) }
}
