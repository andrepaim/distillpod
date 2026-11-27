package dev.distillpod.android.features

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.distillpod.android.data.ManagedBackups
import dev.distillpod.android.ui.theme.Button
import dev.distillpod.android.ui.theme.OutlinedButton
import dev.distillpod.android.ui.theme.TextButton
import dev.distillpod.android.ui.theme.extras

/**
 * Settings › Sync: the one place for keeping a library safe. Cloud sync and the daily automatic backup are
 * presented together; restoring and managing backup files lives one level down on the `backup` route.
 */
@Composable internal fun SyncPage(vm: AppViewModel) {
    val account = vm.graph.syncAccount
    val state by account.state.collectAsStateWithLifecycle()
    val fullScope by account.fullScope.collectAsStateWithLifecycle(initialValue = null)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            SyncCoverageInfo(state.configured, account.contentConfigured, fullScope != null)
            PrivacyPolicyLink(vm::openLink)
        }
        // A build without Firebase says so once in the line above instead of showing an empty account section.
        if (state.configured) {
            SyncSectionTitle("Cloud sync")
            SubscriptionSyncSettings(vm)
            HorizontalDivider()
        }
        SyncSectionTitle("Automatic backup")
        AutomaticBackupCard(vm.managedBackups) { vm.open("backup") }
        TextButton(onClick = { vm.open("backup") }) {
            Icon(Icons.Default.Restore, null); Spacer(Modifier.width(8.dp)); Text("Restore from a backup")
        }
    }
}

@Composable private fun SyncSectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })

/** One line on screen; the complete sync, backup and exclusion text stays in the sheet (#203). */
@Composable internal fun SyncCoverageInfo(configured: Boolean, contentConfigured: Boolean, expandedScope: Boolean) {
    val full = DataProtectionGuidance.coverage(configured, contentConfigured, expandedScope)
    ShortExplanation(if (configured) DataProtectionGuidance.summary else DataProtectionGuidance.sync(false, false, false), "What's covered", full)
}

@Composable internal fun AutomaticBackupCard(backups: ManagedBackups, onOpenRestore: () -> Unit) {
    val state by backups.state.collectAsStateWithLifecycle()
    val file by backups.file.collectAsStateWithLifecycle(initialValue = null)
    val status by backups.status.collectAsStateWithLifecycle(initialValue = ManagedBackups.Status())
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Restarts with each new status, so a backup that just finished is not newer than the clock it is shown against.
    LaunchedEffect(status) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(60_000) } }
    val busy = state.working || state.awaitingDestination
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val name = file
            if (name == null) {
                Text("Transcripts, insights, chats and posts don't sync. Keep them in a backup file in Google Drive or another place you choose, and DistillPod updates it once a day.",
                    style = MaterialTheme.typography.bodyMedium)
                Button(onClick = backups::create, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Set up automatic backup") }
                TextButton(onClick = onOpenRestore, enabled = !busy) { Text("Already have a backup file?") }
            } else {
                val failed = status.error.isNotBlank()
                Text(DataProtectionGuidance.backupLine(true, status, now), style = MaterialTheme.typography.titleSmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.extras.success)
                Text(name, style = MaterialTheme.typography.bodySmall)
                if (failed) {
                    Text(status.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    // Lost access is fixed by reconnecting the file, which lives on the restore page.
                    TextButton(onClick = onOpenRestore, enabled = !busy) { Text("Reconnect or change the backup file") }
                }
                Text("Updated once a day when your library changes. Your file provider handles the cloud upload.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.working) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.message, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = backups::backup, enabled = !busy) {
                    Icon(Icons.Default.CloudUpload, null); Spacer(Modifier.width(8.dp)); Text("Back up now")
                }
            }
        }
    }
}
