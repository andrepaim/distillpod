package dev.distillpod.android.features

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.distillpod.android.data.*
import org.json.JSONArray

private const val AUTOMATION_TITLE = "Prepare new episodes automatically"
private const val AUTOMATION_DETAIL = "Download new episodes, transcribe, and generate summaries, chapters and ad markers. Transcript text is sent to ChatGPT and uses your plan allowance. Applies to future episodes on this device."

/**
 * The show page's follow state (#203): a tonal "Following" button whose menu holds Unsubscribe,
 * confirmed with the same dialog as the feed-problems card. Not following offers Follow directly.
 */
@Composable internal fun ShowFollowButton(title: String, subscribed: Boolean, onFollow: () -> Unit, onUnsubscribe: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    if (confirm) UnsubscribeConfirmDialog(title, onConfirm = { confirm = false; onUnsubscribe() }, onDismiss = { confirm = false })
    if (!subscribed) { FilledTonalButton(onClick = onFollow) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Follow") }; return }
    Box {
        FilledTonalButton(onClick = { menu = true }) {
            Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Following")
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Unsubscribe") }, onClick = { menu = false; confirm = true })
        }
    }
}

/** Unfollowing syncs to the user's other devices, so it is always confirmed. */
@Composable internal fun UnsubscribeConfirmDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Unsubscribe from $title?") },
        text = { Text("It stops refreshing on your devices. Downloaded episodes, listening progress and saved moments are kept.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Unsubscribe") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable internal fun ShowAutomationControls(vm: AppViewModel, id: String) {
    val shows by vm.automationShows.collectAsStateWithLifecycle()
    val jobs by vm.automationJobs.collectAsStateWithLifecycle()
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val transcripts by vm.localTranscripts.collectAsStateWithLifecycle()
    val analyses by vm.analyses.collectAsStateWithLifecycle()
    val prefs by vm.graph.preferences.collectAsStateWithLifecycle()
    val account by vm.graph.account.state.collectAsStateWithLifecycle()
    val show = shows.firstOrNull { it.podcastId == id }
    var about by rememberSaveable { mutableStateOf(false) }
    if (about) LearnMoreSheet(AUTOMATION_TITLE, listOf(AUTOMATION_DETAIL)) { about = false }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsGroupCard {
            ListItem(headlineContent = { Text(AUTOMATION_TITLE, style = MaterialTheme.typography.titleSmall) },
                supportingContent = { Text("Download, transcribe and add insights · uses ChatGPT", style = MaterialTheme.typography.bodySmall) },
                trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { about = true }) { Icon(Icons.Outlined.Info, "About automatic preparation") }
                    Switch(show != null, { vm.automateShow(id, it) }, Modifier.semantics { contentDescription = AUTOMATION_TITLE })
                } },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent, supportingColor = MaterialTheme.colorScheme.onSurfaceVariant))
        }
        if (show != null) {
            Text(if (!show.initialized) "Waiting for the first successful feed refresh" else if (!prefs.refresh) "Scheduled checking is paused" else "Checks about every ${prefs.refreshHours} hours; Android may delay runs.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.open("settings:storage") }) { Text("Background settings") }
            if (!account.connected) TextButton(onClick = { vm.open("settings:ai") }) { Text("Connect ChatGPT for summaries") }
            TextButton(onClick = { vm.open("settings:transcription") }) { Text("Transcription model & charging settings") }
            jobs.filter { it.podcastId == id }.forEach { job ->
                val ep = episodes.firstOrNull { it.id == job.episodeId }
                val transcript = transcripts.firstOrNull { it.episodeId == job.episodeId }
                val analysis = analyses.firstOrNull { it.episodeId == job.episodeId }
                val status = when {
                    job.stage == "ready" -> "Ready"
                    ep?.downloadState in listOf("failed", "paused") -> "Download ${ep?.downloadState}"
                    job.stage == "downloading" -> "Download queued · waits for network settings"
                    transcript?.state in listOf("failed", "paused", "waiting_model", "partial", "stale") -> "Transcript ${transcript?.state?.replace('_', ' ')}"
                    analysis?.state in listOf("failed", "paused", "waiting_auth", "waiting_quota") -> "Analysis ${analysis?.state?.replace('_', ' ')}"
                    job.stage == "blocked" -> "Needs attention · later episodes continue"
                    else -> job.stage.replace('_', ' ')
                }
                Text("${ep?.title ?: "Episode"} · $status", style = MaterialTheme.typography.bodySmall)
                if (ep?.downloadState in listOf("failed", "paused") || transcript?.state in listOf("failed", "paused", "partial", "stale") || analysis?.state in listOf("failed", "paused", "waiting_auth", "waiting_quota") || job.stage == "blocked") {
                    TextButton(onClick = { vm.retryAutomation(job.episodeId) }) { Text("Retry preparation") }
                }
            }
        }
    }
}

@Composable internal fun PlayerAdControls(vm: AppViewModel, ep: EpisodeEntity, showReview: Boolean = true) {
    val transcripts by vm.localTranscripts.collectAsStateWithLifecycle()
    val analyses by vm.analyses.collectAsStateWithLifecycle()
    val choices by vm.adPreferences.collectAsStateWithLifecycle()
    val prefs by vm.graph.preferences.collectAsStateWithLifecycle()
    val analysis = analyses.firstOrNull { it.episodeId == ep.id }
    val transcript = transcripts.firstOrNull { it.episodeId == ep.id }
    if (!canSkipMarkedAds(ep, transcript, analysis) || ep.localFile?.let { java.io.File(it).isFile } != true) return
    val ads = runCatching { JSONArray(analysis!!.adsJson) }.getOrNull() ?: return
    if (ads.length() == 0) { Text("No ads marked", style = MaterialTheme.typography.bodySmall); return }
    val choice = choices.firstOrNull { it.episodeId == ep.id }
    val enabled = if (choice != null) choice.enabled && choice.revision == adSelectionRevision(analysis!!) else prefs.skipAds
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Skip marked ads", Modifier.weight(1f))
        Switch(enabled, { vm.skipEpisodeAds(ep.id, it) })
    }
    if (dev.distillpod.core.YouTubeUrl.videoId(ep.audioUrl) != null)
        Text("Ad skipping works in Audio mode using this episode’s download.", style = MaterialTheme.typography.bodySmall)
    if (showReview) TextButton(onClick = { vm.open("analysis:${ep.id}") }) { Text("Review marked ads") }
}

@Composable internal fun RefreshIntervalControl(vm: AppViewModel, prefs: Preferences) {
    var custom by remember(prefs.refreshHours) { mutableStateOf(prefs.refreshHours.toString()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Check for new episodes every ${prefs.refreshHours} hours")
        Row { listOf(1, 3, 6, 12, 24).forEach { hours -> TextButton(onClick = { vm.refreshHours(hours) }) { Text("${hours}h") } } }
        OutlinedTextField(value = custom, onValueChange = { custom = it.take(3) }, label = { Text("Custom hours (1–168)") }, singleLine = true)
        TextButton(enabled = custom.toIntOrNull() in 1..168, onClick = { custom.toIntOrNull()?.let(vm::refreshHours) }) { Text("Apply interval") }
        Text("Android may delay checks to conserve battery. Force-stopping the app pauses background work until you reopen it.", style = MaterialTheme.typography.bodySmall)
    }
}
