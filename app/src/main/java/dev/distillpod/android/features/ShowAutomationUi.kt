package dev.distillpod.android.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.distillpod.android.data.*
import org.json.JSONArray

internal data class PreparationRow(val episodeId: String, val title: String, val status: String, val retryable: Boolean)

internal fun preparationRows(
    id: String, jobs: List<EpisodeAutomationEntity>, episodes: List<EpisodeEntity>,
    transcripts: List<TranscriptEntity>, analyses: List<AnalysisEntity>,
): List<PreparationRow> {
    val selected = jobs.filter { it.podcastId == id }
    if (selected.isEmpty()) return emptyList()
    val titles = episodes.associateBy { it.id }
    val texts = transcripts.associateBy { it.episodeId }
    val insights = analyses.associateBy { it.episodeId }
    return selected.map { job ->
        val ep = titles[job.episodeId]
        val transcript = texts[job.episodeId]
        val analysis = insights[job.episodeId]
        val status = when {
            job.stage == "ready" -> "Ready"
            ep?.downloadState in listOf("failed", "paused") -> "Download ${ep?.downloadState}"
            job.stage == "downloading" -> "Download queued · waits for network settings"
            transcript?.state in listOf("failed", "paused", "waiting_model", "partial", "stale") -> "Transcript ${transcript?.state?.replace('_', ' ')}"
            analysis?.state in listOf("failed", "paused", "waiting_auth", "waiting_quota") -> "Analysis ${analysis?.state?.replace('_', ' ')}"
            job.stage == "blocked" -> "Needs attention · later episodes continue"
            else -> job.stage.replace('_', ' ')
        }
        val retryable = ep?.downloadState in listOf("failed", "paused") ||
            transcript?.state in listOf("failed", "paused", "partial", "stale") ||
            analysis?.state in listOf("failed", "paused", "waiting_auth", "waiting_quota") || job.stage == "blocked"
        PreparationRow(job.episodeId, ep?.title ?: "Episode", status, retryable)
    }
}

@Composable internal fun showPreparationRows(vm: AppViewModel, id: String): List<PreparationRow> {
    val shows by vm.automationShows.collectAsStateWithLifecycle()
    if (shows.none { it.podcastId == id }) return emptyList()
    val jobs by vm.automationJobs.collectAsStateWithLifecycle()
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val transcripts by vm.localTranscripts.collectAsStateWithLifecycle()
    val analyses by vm.analyses.collectAsStateWithLifecycle()
    return remember(id, jobs, episodes, transcripts, analyses) { preparationRows(id, jobs, episodes, transcripts, analyses) }
}

private const val PREPARATION_DISCLOSURE =
    "Download new episodes, transcribe, and generate summaries, chapters and ad markers. Transcript text is sent to ChatGPT and uses your plan allowance. Applies to future episodes on this device."

@Composable internal fun ShowPreparationDetails(vm: AppViewModel, id: String, onlyAttention: Boolean) {
    val shows by vm.automationShows.collectAsStateWithLifecycle()
    val prefs by vm.graph.preferences.collectAsStateWithLifecycle()
    val account by vm.graph.account.state.collectAsStateWithLifecycle()
    val show = shows.firstOrNull { it.podcastId == id }
    val rows = showPreparationRows(vm, id)
    var attention by rememberSaveable(id, onlyAttention) { mutableStateOf(onlyAttention) }
    var confirmEnable by rememberSaveable(id) { mutableStateOf(false) }
    if (confirmEnable && show == null) AlertDialog(
        onDismissRequest = { confirmEnable = false },
        title = { Text("Enable automatic preparation?") },
        text = { Text(PREPARATION_DISCLOSURE) },
        confirmButton = { TextButton(onClick = { confirmEnable = false; vm.automateShow(id, true) }) { Text("Enable preparation") } },
        dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text("Cancel") } },
    )
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(show != null, role = Role.Switch) {
                    if (it) confirmEnable = true else vm.automateShow(id, false)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Automatically prepare new episodes", Modifier.weight(1f).padding(end = 8.dp))
                Switch(show != null, onCheckedChange = null)
            }
        }
        if (!onlyAttention || !attention || show == null) item { Text(PREPARATION_DISCLOSURE, style = MaterialTheme.typography.bodySmall) }
        if (show != null) {
            if (!onlyAttention || !attention) item {
                Column {
                    Text(if (!show.initialized) "Waiting for the first successful feed refresh" else if (!prefs.refresh) "Scheduled checking is paused" else "Checks about every ${prefs.refreshHours} hours; Android may delay runs.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.open("settings:storage") }) { Text("Background settings") }
                    if (!account.connected) TextButton(onClick = { vm.open("settings:ai") }) { Text("Connect ChatGPT for summaries") }
                    TextButton(onClick = { vm.open("settings:transcription") }) { Text("Transcription model & charging settings") }
                }
            }
            item {
                FilterChip(selected = attention, onClick = { attention = !attention }, label = { Text("Needs attention only") })
            }
            val visible = if (attention) rows.filter { it.retryable } else rows
            if (visible.isEmpty()) item { Text(if (attention) "No episodes need attention" else "No preparations yet") }
            items(visible, key = { it.episodeId }) { row ->
                Column(Modifier.fillMaxWidth()) {
                    Text(row.title, style = MaterialTheme.typography.titleSmall)
                    Text(row.status, style = MaterialTheme.typography.bodySmall)
                    if (row.retryable) TextButton(onClick = { vm.retryAutomation(row.episodeId) },
                        modifier = Modifier.semantics { contentDescription = "Retry preparation for ${row.title}" }) {
                        Text("Retry preparation", Modifier.clearAndSetSemantics {})
                    }
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
