package dev.distillpod.android.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.distillpod.android.data.*
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun StorageSettings(
    vm: AppViewModel,
    prefs: Preferences,
    episodes: List<EpisodeEntity>,
    models: List<ModelEntity>,
) {
    val deletionBlocks by vm.audioDeletionBlocks.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var usage by remember { mutableStateOf<AudioStorageUsage?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var manage by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var discardPartial by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(episodes, models, refresh) {
        try {
            usage = vm.graph.media.storageUsage()
            unavailable = false
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { unavailable = true }
    }
    HorizontalDivider()
    Text("Storage", style = MaterialTheme.typography.titleLarge)
    if (unavailable) Text("Storage usage could not be read. Try refreshing.", color = MaterialTheme.colorScheme.error)
    else if (usage == null) Text("Reading storage usage…")
    else usage?.let { bytes ->
        Text("Audio quota used: ${fileSize(bytes.quotaBytes)} of ${fileSize(prefs.storageLimitBytes)}")
        Text("Downloaded audio: ${fileSize(bytes.downloadedBytes)}")
        Text("Partial downloads and cache: ${fileSize(bytes.partialCacheBytes)}")
        Text("Transcription models: ${fileSize(bytes.modelBytes)} · separate from the audio limit",
            style = MaterialTheme.typography.bodySmall)
        if (bytes.quotaBytes > prefs.storageLimitBytes) {
            Text("Audio is over the limit. Free space or raise the limit before downloading more.",
                color = MaterialTheme.colorScheme.error)
        }
    }
    TextButton(onClick = { refresh++ }) { Text("Refresh storage usage") }
    TextButton(onClick = { vm.open("settings:transcription") }) { Text("Manage transcription models") }
    Text("Lowering the audio limit does not delete files. Existing downloads stay available; new or resumed downloads must fit within the limit.",
        style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1L, 2L, 5L).forEach { gb ->
            FilterChip(selected = prefs.storageLimitBytes == gb * 1024 * 1024 * 1024,
                onClick = { vm.action("settings") { vm.graph.settings.storage(gb * 1024 * 1024 * 1024) } },
                label = { Text("$gb GB") })
        }
    }
    OutlinedButton(onClick = {
        vm.action("storage") {
            val bytes = vm.graph.media.clearUnusedAudio()
            refresh++
            vm.messages.send("Cleared ${fileSize(bytes)} of unused audio. Downloads and protected files were kept.")
        }
    }) { Text("Clear unused audio") }
    Text("Removes leftover cache files after feed changes. Downloads, resumable paused partials, queued audio and processing files are kept.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = { manage = !manage }) { Text(if (manage) "Hide downloads" else "Manage downloads") }
    if (manage) {
        Text("Deleting audio keeps saved insights and listening progress. Playing, queued and transcribing audio is protected.",
            style = MaterialTheme.typography.bodySmall)
        val downloads = episodes.filter { it.localFile != null || it.downloadState in listOf("paused", "queued", "downloading", "failed") }
        if (downloads.isEmpty()) Text("No downloads to manage.")
        downloads.forEach { ep ->
            val blocked = deletionBlocks[ep.id]
            Column(Modifier.testTag("storage-download-${ep.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ep.title, style = MaterialTheme.typography.titleSmall)
                Text(ep.downloadState.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall)
                blocked?.let { Text(it.feedback, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { vm.open("episode:${ep.id}") }) { Text("Open episode") }
                if (ep.localFile != null) {
                    TextButton(enabled = blocked == null, onClick = { selectedId = ep.id; discardPartial = false }) { Text("Delete download") }
                } else if (ep.downloadState == "paused") {
                    TextButton(enabled = blocked == null, onClick = { selectedId = ep.id; discardPartial = true }) { Text("Discard paused partial") }
                    TextButton(onClick = { vm.download(ep.id) }) { Text("Resume download") }
                }
            }
        }
    }
    selectedId?.let { id ->
        val blocked = deletionBlocks[id]
        val title = episodes.firstOrNull { it.id == id }?.title ?: "This episode"
        AlertDialog(
            onDismissRequest = { selectedId = null },
            title = { Text(if (discardPartial) "Discard paused download?" else "Delete downloaded audio?") },
            text = { Text(blocked?.feedback ?: ("$title\n\n" + if (discardPartial)
                "The partial audio will be removed. Downloading again starts from the beginning. Saved insights and listening progress are kept."
                else "The downloaded audio will be removed from this phone. You can download it again. Saved insights and listening progress are kept.")) },
            confirmButton = {
                TextButton(enabled = blocked == null, onClick = {
                    val partial = discardPartial
                    selectedId = null
                    vm.action("storage") {
                        if (partial) vm.graph.media.discardPausedPartial(id) else vm.graph.media.deleteDownload(id)
                        refresh++
                        vm.messages.send(if (partial) "Paused partial discarded." else "Download deleted.")
                    }
                }) { Text(if (discardPartial) "Discard partial" else "Delete download") }
            },
            dismissButton = { TextButton(onClick = { selectedId = null }) { Text("Cancel") } },
        )
    }
}
