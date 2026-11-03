package dev.distillpod.android.features

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.distillpod.android.data.BackupDocuments
import dev.distillpod.android.data.ManagedBackups
import kotlinx.coroutines.flow.receiveAsFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** All file types stay visible: provider MIME classification must not hide an otherwise valid backup. */
internal class BackupFilePicker(private val initial: () -> Uri?) : ActivityResultContract<Array<String>, Uri?>() {
    private val delegate = ActivityResultContracts.OpenDocument()
    override fun createIntent(context: Context, input: Array<String>) = delegate.createIntent(context, input).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        initial()?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
    }
    override fun parseResult(resultCode: Int, intent: Intent?) = delegate.parseResult(resultCode, intent)
}

@Composable internal fun ManagedBackupLaunchers(backups: ManagedBackups, onCreated: (Uri?) -> Unit = backups::created) {
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), onCreated)
    LaunchedEffect(backups) {
        backups.destinations.receiveAsFlow().collect { name ->
            try { create.launch(name) } catch (_: android.content.ActivityNotFoundException) { backups.created(null) }
        }
    }
}
private fun savedDate(at: Long) = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm:ss", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))

@Composable internal fun ManagedBackupScreen(backups: ManagedBackups, transfers: BackupDocuments) {
    val state by backups.state.collectAsStateWithLifecycle()
    val transfer by transfers.state.collectAsStateWithLifecycle()
    val picker = remember { BackupFilePicker { backups.state.value.location?.uri } }
    val connect = rememberLauncherForActivityResult(picker, backups::connect)
    val older = rememberLauncherForActivityResult(picker, transfers::preview)
    val busy = state.working || state.awaitingDestination || transfer.working || transfer.preview != null
    LaunchedEffect(backups) { if (transfer.preview == null) backups.refresh() }
    fun managed(action: () -> Unit) { transfers.dismissPreview(); action() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Icon(Icons.Default.CloudDone, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Your library, one tap away", style = MaterialTheme.typography.headlineMedium)
        Text("Connect a backup file once. DistillPod remembers it for your next backup and restore.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (state.location == null) "Set up Drive backup" else "Connected backup file", style = MaterialTheme.typography.titleLarge)
                state.location?.let {
                    Text(it.name, color = MaterialTheme.colorScheme.primary)
                    Text("Future backups use this file automatically. No folder browsing or file naming.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { managed(backups::backup) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CloudUpload, null); Spacer(Modifier.width(8.dp)); Text("Back up now")
                    }
                    OutlinedButton(onClick = { managed { backups.refresh(choose = true) } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Restore, null); Spacer(Modifier.width(8.dp)); Text("Restore a backup")
                    }
                } ?: run {
                    Text("Choose Drive → My Drive and save the suggested file. This is the only setup step; the file keeps your dated backup history.")
                    Button(onClick = { managed(backups::create) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Create backup file") }
                    OutlinedButton(onClick = { managed { connect.launch(arrayOf("*/*")) } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Connect existing backup") }
                }
                Text("Keeps up to 10 recent backups, within the history size limit. Your original standalone exports are kept.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val message = if (state.working || transfer.phase.isBlank()) state.message else transfer.phase
        if (message.isNotEmpty()) Card(colors = CardDefaults.cardColors(containerColor = if (state.error || transfer.error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.working || transfer.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(message)
            }
        }
        state.history.firstOrNull()?.let { latest ->
            Text("Latest available backup", style = MaterialTheme.typography.titleMedium)
            Text(savedDate(latest.at), color = MaterialTheme.colorScheme.secondary)
            Text("${latest.shows} shows · ${latest.episodes} episodes · ${latest.moments} moments", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (latest.local) Text("Recovery copy on this phone", color = MaterialTheme.colorScheme.tertiary)
            TextButton(onClick = { managed { backups.refresh(choose = true) } }, enabled = !busy) { Text("View backup history (${state.history.size})") }
        }
        if (state.location != null) {
            HorizontalDivider()
            TextButton(onClick = { managed { connect.launch(arrayOf("*/*")) } }, enabled = !busy) { Text("Connect another backup file") }
            TextButton(onClick = { managed(backups::create) }, enabled = !busy) { Text("Create another backup file") }
            TextButton(onClick = { managed(backups::disconnect) }, enabled = !busy) { Text("Forget backup connection") }
        }
        HorizontalDivider()
        Text("Have an older export?", style = MaterialTheme.typography.titleMedium)
        Text("Import a standalone DistillPod JSON backup. All file types are shown so Drive cannot hide it because of its file type.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { older.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Import older backup") }
        TextButton(onClick = transfers::prepare, enabled = !busy) { Text("Export a standalone JSON copy") }
        Text("Reconnect the file once after reinstalling or on a new phone. Saving is one tap; backups do not run on a schedule. Drive manages upload and sync.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Backups include library text, progress, queue, transcripts and preferences. Audio, model files and sign-in credentials are excluded. Backup files are readable; keep them private.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (state.chooseBackup) AlertDialog(onDismissRequest = backups::dismissChoices, title = { Text("Choose a backup") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.history.isEmpty()) Text("No saved backups yet. Use Back up now to create one.")
            state.history.forEach { item ->
                Surface(Modifier.fillMaxWidth().testTag("Backup choice ${item.id}").clickable { backups.preview(item.id, item.local) }, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(savedDate(item.at), style = MaterialTheme.typography.titleSmall)
                        Text("${item.shows} shows · ${item.episodes} episodes · ${item.moments} moments", style = MaterialTheme.typography.bodySmall)
                        Text(if (item.local) "Recovery copy on this phone" else "DistillPod ${item.version}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = backups::dismissChoices) { Text("Cancel") } })
    BackupReview(transfers)
}
