package dev.distillpod.android.features

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.distillpod.android.ui.theme.TextButton

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun DataProtectionInfo(
    configured: Boolean,
    contentConfigured: Boolean,
    expandedScope: Boolean,
    viewingBackup: Boolean,
    onOpenOther: () -> Unit,
    navigationEnabled: Boolean = true,
    details: List<String> = emptyList(),
) {
    // One line on screen; the complete sync, backup and exclusion text stays verbatim in the sheet (#203).
    val unavailable = DataProtectionGuidance.sync(configured, contentConfigured, expandedScope).takeIf { !configured }
    val full = listOfNotNull(DataProtectionGuidance.sync(configured, contentConfigured, expandedScope).takeIf { configured },
        DataProtectionGuidance.backup, DataProtectionGuidance.excluded) + details
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Sync and backup protect different things", style = MaterialTheme.typography.titleSmall)
            Text(unavailable ?: DataProtectionGuidance.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LearnMoreLink("Sync and backup", full)
                TextButton(onClick = onOpenOther, enabled = navigationEnabled, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                    Text(if (viewingBackup) "Open Library sync" else "Open Backup & Restore")
                }
            }
        }
    }
}
