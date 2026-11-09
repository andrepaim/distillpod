package dev.distillpod.android.features

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

/** Canonical notification destination; platform recovery behavior lives in NotificationAccess. */
@Composable fun NotificationControls() {
    NotificationAccess()
}
