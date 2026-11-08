package dev.distillpod.android.features

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.distillpod.android.data.*
import org.json.JSONArray

@Composable internal fun ShowAutomationControls(vm: AppViewModel, id: String) {
    val shows by vm.automationShows.collectAsStateWithLifecycle()
    val jobs by vm.automationJobs.collectAsStateWithLifecycle()
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val transcripts by vm.localTranscripts.collectAsStateWithLifecycle()
    val analyses by vm.analyses.collectAsStateWithLifecycle()
    val prefs by vm.graph.preferences.collectAsStateWithLifecycle()
    val account by vm.graph.account.state.collectAsStateWithLifecycle()
    val show = shows.firstOrNull { it.podcastId == id }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Automatically prepare new episodes", Modifier.weight(1f))
            Switch(show != null, { vm.automateShow(id, it) })
        }
        Text("Download new episodes, transcribe, and generate summaries, chapters and ad markers. Transcript text is sent to ChatGPT and uses your plan allowance. Applies to future episodes on this device.", style = MaterialTheme.typography.bodySmall)
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
                    else -> job.stage.replace('_', ' ')
                }
                Text("${ep?.title ?: "Episode"} · $status", style = MaterialTheme.typography.bodySmall)
                if (ep?.downloadState in listOf("failed", "paused") || transcript?.state in listOf("failed", "paused", "partial", "stale") || analysis?.state in listOf("failed", "paused", "waiting_auth", "waiting_quota")) {
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
