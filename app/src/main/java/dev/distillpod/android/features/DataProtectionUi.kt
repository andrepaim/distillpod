package dev.distillpod.android.features

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun DataProtectionInfo(
    configured: Boolean,
    contentConfigured: Boolean,
    expandedScope: Boolean,
    viewingBackup: Boolean,
    onOpenOther: () -> Unit,
    navigationEnabled: Boolean = true,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sync and backup protect different things", style = MaterialTheme.typography.titleMedium)
            Text(DataProtectionGuidance.sync(configured, contentConfigured, expandedScope), style = MaterialTheme.typography.bodySmall)
            Text(DataProtectionGuidance.backup, style = MaterialTheme.typography.bodySmall)
            Text(DataProtectionGuidance.excluded, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onOpenOther, enabled = navigationEnabled) {
                Text(if (viewingBackup) "Open Library sync" else "Open Backup & Restore")
            }
        }
    }
}
