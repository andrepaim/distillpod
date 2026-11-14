package dev.distillpod.android.features

import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.distillpod.android.data.UpdateState
import dev.distillpod.android.ui.theme.Button
import dev.distillpod.android.ui.theme.TextButton

internal const val UPDATE_CHECKS_TITLE = "Check for updates"

/**
 * Settings › About: a newer release with its download link, the daily-check switch and a manual check.
 * [open] receives only project release URLs (see ReleaseFeed.isProjectRelease). See docs/android/app-updates.md.
 */
@Composable internal fun AppUpdatesSection(state: UpdateState, checking: Boolean, now: Long,
    onToggle: (Boolean) -> Unit, onCheck: () -> Unit, open: (String) -> Unit) {
    state.available?.let { update ->
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DistillPod ${update.versionName} is available", Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                if (update.summary.isNotBlank()) Text(update.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Install it over this app to keep your library. Don't uninstall first.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { open(update.apkUrl) }) { Text("Download APK") }
                    TextButton(onClick = { open(update.pageUrl) }) { Text("What's new") }
                }
            }
        }
    }
    SettingsGroupCard {
        ListItem(headlineContent = { Text(UPDATE_CHECKS_TITLE, style = MaterialTheme.typography.titleSmall) },
            supportingContent = { Text("Once a day, asks GitHub whether a newer release is published and notifies you. " +
                "No personal data is sent; GitHub sees your IP address.", style = MaterialTheme.typography.bodySmall) },
            trailingContent = { Switch(state.enabled, onToggle, modifier = Modifier.semantics { contentDescription = UPDATE_CHECKS_TITLE }) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent, supportingColor = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.fillMaxWidth())
        SettingsDivider()
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lastChecked(state.checkedAt, now), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onCheck, enabled = !checking) { Text(if (checking) "Checking…" else "Check now") }
        }
    }
}

internal fun lastChecked(at: Long, now: Long): String = when {
    at <= 0 -> "Not checked yet"
    now - at < DateUtils.MINUTE_IN_MILLIS -> "Last checked just now"
    else -> "Last checked ${DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS)}"
}
