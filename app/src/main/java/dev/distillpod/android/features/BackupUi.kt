package dev.distillpod.android.features

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.receiveAsFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import dev.distillpod.android.ui.theme.extras

@Composable internal fun BackupDocumentLaunchers(vm: AppViewModel) {
    BackupExportLauncher(vm.backupDocuments)
    ManagedBackupLaunchers(vm.managedBackups)
}

@Composable internal fun BackupExportLauncher(
    documents: dev.distillpod.android.data.BackupDocuments,
    onDestination: (android.net.Uri?) -> Unit = documents::save,
) {
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), onDestination)
    LaunchedEffect(documents) {
        documents.destinations.receiveAsFlow().collect { name ->
            try { export.launch(name) }
            catch (_: android.content.ActivityNotFoundException) { documents.pickerUnavailable() }
        }
    }
}

private fun backupDate(at: Long): String = if (at <= 0) "Date unavailable" else DateTimeFormatter
    .ofPattern("d MMM yyyy · HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))
private fun backupSize(bytes: Long): String = if (bytes < 1_000_000) "%.1f KB".format(bytes / 1000.0) else "%.1f MB".format(bytes / 1_000_000.0)

@Composable internal fun BackupPage(vm: AppViewModel, onOpenSync: () -> Unit) {
    val sync by vm.graph.syncAccount.state.collectAsStateWithLifecycle()
    val fullScope by vm.graph.syncAccount.fullScope.collectAsStateWithLifecycle(initialValue = null)
    ManagedBackupScreen(vm.managedBackups, vm.backupDocuments, sync.configured, vm.graph.syncAccount.contentConfigured, fullScope != null, onOpenSync)
}

@Composable internal fun BackupScreen(documents: dev.distillpod.android.data.BackupDocuments) {
    val state by documents.state.collectAsStateWithLifecycle()
    val saved by documents.lastSaved.collectAsStateWithLifecycle()
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), documents::preview)
    val enabled = !state.working && !state.awaitingDestination && state.preview == null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Icon(Icons.Default.CloudUpload, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Your listening, kept safe", style = MaterialTheme.typography.headlineMedium)
        Text("Save a portable copy of your library. Bring it back to this phone or import it on another one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Save a backup", style = MaterialTheme.typography.titleLarge)
                Text("Choose Google Drive in Android’s file picker, then select a folder. You can also save to your device or another file provider.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = documents::prepare, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CloudUpload, null); Spacer(Modifier.width(8.dp)); Text("Save backup to Google Drive")
                }
                Text("Each save creates a new file. Previous backups are kept.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.phase.isNotEmpty()) Card(colors = CardDefaults.cardColors(containerColor = if (state.error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.phase, color = if (state.error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        saved?.let { last ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.TaskAlt, null, tint = MaterialTheme.extras.success)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Last saved file", style = MaterialTheme.typography.titleMedium)
                    Text(backupDate(last.at), color = MaterialTheme.extras.success)
                    Text(last.name, style = MaterialTheme.typography.bodySmall)
                    Text("${backupSize(last.bytes)} · ${last.podcasts} shows · ${last.episodes} episodes · ${last.moments} moments", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("File saved and checked. Cloud sync is managed by your file provider.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider()
        Text("Restore your library", style = MaterialTheme.typography.titleLarge)
        Text("Choose a DistillPod backup from Drive or your device. Review its contents before merging. Existing records and listening progress are kept.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = {
            try { import.launch(arrayOf("*/*")) }
            catch (_: android.content.ActivityNotFoundException) { documents.pickerUnavailable() }
        }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Restore, null); Spacer(Modifier.width(8.dp)); Text("Import backup")
        }
        HorizontalDivider()
        Text("What’s included", style = MaterialTheme.typography.titleMedium)
        Text("Shows and episodes, listening progress, queue, transcripts, saved moments, insights, chat history and language/model preferences.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Audio downloads, model files and sign-in credentials stay off the backup. On another phone, download audio/models and connect ChatGPT separately.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Backups are readable JSON, with personal text and feed addresses that may contain private access links. Keep them in a private location. This is a manual backup; save again after changes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    BackupReview(documents)
}

@Composable internal fun BackupReview(documents: dev.distillpod.android.data.BackupDocuments) {
    val state by documents.state.collectAsStateWithLifecycle()
    state.preview?.let { preview ->
        var restorePreferences by rememberSaveable(preview.createdAt, preview.appVersion) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = documents::dismissPreview, title = { Text("Review backup") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(backupDate(preview.createdAt))
                Text("DistillPod ${preview.appVersion}", style = MaterialTheme.typography.bodySmall)
                Text("${preview.counts["podcasts"] ?: 0} shows · ${preview.episodes} episodes · ${preview.moments} moments")
                Text("${preview.counts["transcripts"] ?: 0} transcripts · ${preview.counts["analyses"] ?: 0} insights · ${preview.counts["messages"] ?: 0} chat messages")
                Text("${preview.conflicts} existing records will be kept. Missing records are added in one library transaction.")
                if (preview.preferences != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = restorePreferences, onCheckedChange = { restorePreferences = it })
                        Text("Also restore language and model choices", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("Model files are not downloaded automatically. Device network and storage settings stay as they are.", style = MaterialTheme.typography.bodySmall)
                }
                Text("Nothing will start playing or processing. You can download audio and retry interrupted AI work when ready.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { documents.restore(restorePreferences) }) { Text("Merge library") } },
            dismissButton = { TextButton(onClick = documents::dismissPreview) { Text("Cancel") } })
    }
}
