package dev.distillpod.android.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.distillpod.android.data.*

private fun counted(n: Int, word: String, plural: String = word + "s") = "$n ${if (n == 1) word else plural}"
internal fun audioCleanupSize(bytes: Long): String = when {
    bytes < 1000 -> "$bytes B"
    bytes < 1_000_000 -> "%.1f KB".format(bytes / 1000.0)
    else -> fileSize(bytes)
}
internal fun audioCleanupSummary(result: AudioCleanupResult?, cancelled: Boolean, failed: Boolean = false): String = buildString {
    append(if (cancelled) "Cleanup cancelled." else if (failed) "Audio cleanup stopped." else "Audio cleanup finished.")
    result?.let {
        append(" Deleted ${counted(it.removed.files, "audio file")} (${audioCleanupSize(it.removed.bytes)}).")
        if (it.protectedUnits > 0) append(" Kept ${counted(it.protectedUnits, "selection")} in use or automatic preparation.")
        if (it.changedUnits > 0) append(" Kept ${counted(it.changedUnits, "new or changed selection")}.")
        if (it.reconciledEntries > 0) append(" Cleared ${counted(it.reconciledEntries, "unavailable download entry", "unavailable download entries")}.")
        val failures = it.failedUnits + it.unavailableUnits
        if (failures > 0) append(" ${counted(failures, "selection")} could not be cleared. Review again to retry.")
    }
}

@Composable internal fun BulkAudioSettings(controller: AudioCleanupController, onStorageChanged: () -> Unit) {
    val state by controller.state.collectAsState()
    LaunchedEffect(state.refreshVersion) { if (state.refreshVersion > 0) onStorageChanged() }
    DisposableEffect(controller) { onDispose { controller.leaveScreen() } }
    OutlinedButton(onClick = controller::review,
        enabled = !state.running && state.phase != AudioCleanupPhase.REVIEWING,
        modifier = Modifier.testTag("audio-cleanup-review")) { Text("Clear all local audio") }
    Text("Review downloaded audio, unfinished downloads and other audio cache before deleting. Transcripts, saved insights, listening progress and models are kept.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.phase in setOf(AudioCleanupPhase.FINISHED, AudioCleanupPhase.FAILED)) {
        state.result?.let { result ->
            Text(audioCleanupSummary(result, state.cancelled, state.phase == AudioCleanupPhase.FAILED), modifier = Modifier.testTag("audio-cleanup-result"))
            Text("Deleted completed downloads: ${result.removed.downloaded.files} (${audioCleanupSize(result.removed.downloaded.bytes)})", style = MaterialTheme.typography.bodySmall)
            Text("Deleted unfinished downloads: ${result.removed.unfinished.files} (${audioCleanupSize(result.removed.unfinished.bytes)})", style = MaterialTheme.typography.bodySmall)
            Text("Deleted other cached audio: ${result.removed.cached.files} (${audioCleanupSize(result.removed.cached.bytes)})", style = MaterialTheme.typography.bodySmall)
        }
        if (state.cancelled && state.result == null) Text("Cleanup cancelled.")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    when (state.phase) {
        AudioCleanupPhase.REVIEWING -> AlertDialog(
            onDismissRequest = controller::dismissReview,
            title = { Text("Review local audio") },
            text = { Text("Checking which audio is stored on this phone…") },
            confirmButton = {},
            dismissButton = { TextButton(onClick = controller::dismissReview) { Text("Cancel") } },
        )
        AudioCleanupPhase.READY -> state.preview?.let { preview ->
            val eligible = preview.rows.filter { it.status == AudioCleanupStatus.ELIGIBLE }
            AlertDialog(
                onDismissRequest = controller::dismissReview,
                modifier = Modifier.testTag("audio-cleanup-dialog"),
                title = { Text("Delete reviewed audio?") },
                text = {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Ready to delete: ${counted(eligible.sumOf { it.files }, "audio file")} (${audioCleanupSize(eligible.sumOf { it.bytes })}).")
                        Text("Only this reviewed selection will be cleared. New, changed, playing, queued or preparing audio will be kept.")
                        Text("Reviewed completed downloads: ${preview.totals.downloaded.files} (${audioCleanupSize(preview.totals.downloaded.bytes)})")
                        Text("Reviewed unfinished downloads: ${preview.totals.unfinished.files} (${audioCleanupSize(preview.totals.unfinished.bytes)})")
                        Text("Reviewed other cached audio: ${preview.totals.cached.files} (${audioCleanupSize(preview.totals.cached.bytes)})")
                        if (preview.protectedUnits > 0) Text("Kept in use or automatic preparation: ${preview.protectedUnits}")
                        if (preview.unavailableUnits > 0) Text("Could not check safely; kept: ${preview.unavailableUnits}", color = MaterialTheme.colorScheme.error)
                        if (!preview.canApply) Text("Nothing can be cleared right now. Review again after the protected work finishes.")
                        preview.rows.forEach { row ->
                            HorizontalDivider()
                            Text(row.title, style = MaterialTheme.typography.titleSmall)
                            val kind = when (row.kind) {
                                AudioCleanupKind.DOWNLOADED -> "Downloaded audio"
                                AudioCleanupKind.UNFINISHED -> "Unfinished download"
                                AudioCleanupKind.CACHED -> "Other cached audio"
                            }
                            Text("$kind · ${counted(row.files, "file")} · ${audioCleanupSize(row.bytes)}", style = MaterialTheme.typography.bodySmall)
                            Text(row.reason ?: when (row.status) {
                                AudioCleanupStatus.ELIGIBLE -> "Will be cleared."
                                AudioCleanupStatus.PROTECTED -> "In use; kept."
                                AudioCleanupStatus.UNAVAILABLE -> "Could not check this item; kept."
                            }, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Available episodes can be downloaded again. A new review is needed after cancelling.")
                    }
                },
                confirmButton = {
                    if (preview.canApply) TextButton(onClick = controller::confirm,
                        modifier = Modifier.testTag("audio-cleanup-confirm")) { Text("Delete reviewed audio") }
                    else TextButton(onClick = controller::dismissReview) { Text("Close") }
                },
                dismissButton = {
                    if (preview.canApply) TextButton(onClick = controller::dismissReview) { Text("Cancel") }
                },
            )
        }
        AudioCleanupPhase.RUNNING, AudioCleanupPhase.CANCELLING -> AlertDialog(
            onDismissRequest = controller::cancel,
            title = { Text(if (state.phase == AudioCleanupPhase.CANCELLING) "Finishing current item…" else "Clearing reviewed audio…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val result = state.result
                    Text("Deleted ${counted(result?.removed?.files ?: 0, "audio file")} (${audioCleanupSize(result?.removed?.bytes ?: 0)}).")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Cleanup may wait for an active download before it can start. Cancelling stops the remaining cleanup after the current item finishes.")
                }
            },
            confirmButton = {
                TextButton(onClick = controller::cancel, enabled = state.phase == AudioCleanupPhase.RUNNING) { Text("Cancel remaining cleanup") }
            },
        )
        else -> Unit
    }
}
