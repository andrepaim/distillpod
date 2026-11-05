package dev.distillpod.android.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.distillpod.android.data.*
import org.json.JSONArray
import org.json.JSONObject

private fun objects(json: String): List<JSONObject> = runCatching { val a = JSONArray(json); (0 until a.length()).map { a.getJSONObject(it) } }.getOrDefault(emptyList())
@OptIn(ExperimentalLayoutApi::class)
@Composable fun EpisodeUnderstandingTools(vm: AppViewModel, ep: EpisodeEntity) {
    val transcripts by vm.transcripts.collectAsStateWithLifecycle()
    val ready = transcripts.any { it.episodeId == ep.id && it.state == "ready" && it.fingerprint == ep.mediaFingerprint }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Explore this episode", style = MaterialTheme.typography.titleMedium)
        Text(if (ready) "Use the transcript for a summary, chapters and grounded questions." else "Prepare a complete transcript to unlock episode insights and chat.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.open("analysis:${ep.id}") }, enabled = ready) { Text("Summary & chapters") }
            OutlinedButton(onClick = { vm.open("chat:${ep.id}") }, enabled = ready) { Text("Ask episode") }
        }
    }
}

@Composable fun AnalysisPage(vm: AppViewModel, ep: EpisodeEntity?) {
    if (ep == null) return
    val analyses by vm.analyses.collectAsStateWithLifecycle()
    val transcripts by vm.transcripts.collectAsStateWithLifecycle()
    val prefs by vm.graph.preferences.collectAsStateWithLifecycle()
    val account by vm.graph.account.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val row = analyses.firstOrNull { it.episodeId == ep.id }
    val transcript = transcripts.firstOrNull { it.episodeId == ep.id }
    val current = transcript?.state == "ready" && transcript.fingerprint == ep.mediaFingerprint
    val valid = current && row?.revision == transcript?.revision && row?.state == "succeeded"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(ep.title, style = MaterialTheme.typography.titleLarge)
        Text("Analysis sends transcript parts to ChatGPT and uses your plan allowance. Completed parts are reused after an interruption.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!current) TranscriptTools(vm, ep)
        if (!account.connected) OutlinedButton(onClick = { vm.open("settings:ai") }) { Text("Connect ChatGPT") }
        if (row?.state in listOf("queued", "running")) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("${row!!.completedParts} of ${row.totalParts.takeIf { it > 0 }?.toString() ?: "…"} parts saved")
            OutlinedButton(onClick = { vm.pauseAnalysis(ep.id) }) { Text("Pause analysis") }
        } else if (!valid) Button(onClick = { vm.analyze(ep.id) }, enabled = current && account.connected && "analysis:${ep.id}" !in busy) { Text(if (row?.revision == transcript?.revision) "Resume analysis" else "Generate episode insights") }
        row?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (valid && row != null) {
            Text("Summary", style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(row.summary) }
            row.model?.let { Text("Generated with $it · verify important details against the audio.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("Chapters", style = MaterialTheme.typography.titleMedium)
            val chapters = objects(row.chaptersJson)
            if (chapters.isEmpty()) Text("No chapter boundaries were identified.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            chapters.forEach { chapter -> TextButton(onClick = { vm.play(ep.id, chapter.getLong("startMs")); vm.open("player") }) { Text("${timestamp(chapter.getLong("startMs"))} · ${chapter.getString("title")}") } }
            HorizontalDivider(); Text("Suggested ads", style = MaterialTheme.typography.titleMedium)
            Text("Review the original audio before approving a range. Skipping uses approved ranges in downloaded audio and preserves the original timeline. Seeking into a range or choosing Undo plays it normally.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val ads = objects(row.adsJson)
            if (ads.isEmpty()) Text("No explicit ad ranges were identified.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ads.forEachIndexed { index, ad ->
                Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${timestamp(ad.getLong("startMs"))} – ${timestamp(ad.getLong("endMs"))}")
                    Text(ad.getString("reason"), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.play(ep.id, ad.getLong("startMs")); vm.open("player") }) { Text("Listen to range") }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = ad.optBoolean("approved"), onCheckedChange = { vm.approveAd(ep.id, index, it) }, enabled = transcript?.source != "online"); Text("Approve this range for skipping") }
                } }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Skip approved ads", Modifier.weight(1f)); Switch(prefs.skipAds, { vm.flag("skipAds", it) })
            }
            if (ep.localFile == null) Text("Download this episode to use verified ad skipping.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable fun EpisodeChatPage(vm: AppViewModel, ep: EpisodeEntity?) {
    if (ep == null) return
    val all by vm.chat.collectAsStateWithLifecycle()
    val transcripts by vm.transcripts.collectAsStateWithLifecycle()
    val account by vm.graph.account.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val draft by vm.chatDraft.collectAsStateWithLifecycle()
    val transcript = transcripts.firstOrNull { it.episodeId == ep.id }
    val ready = transcript?.state == "ready" && transcript.fingerprint == ep.mediaFingerprint
    val messages = all.filter { it.episodeId == ep.id }
    val running = messages.any { it.state in listOf("queued", "running") }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size) }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(ep.title, style = MaterialTheme.typography.titleMedium); Text("Answers use selected transcript excerpts. Sources may omit relevant details; replay them to verify. Questions and excerpts are sent to ChatGPT.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!ready) item { TranscriptTools(vm, ep) }
            items(messages, key = { it.id }) { message ->
                val current = message.revision == transcript?.revision && message.fingerprint == ep.mediaFingerprint
                Card(colors = CardDefaults.cardColors(containerColor = if (message.role == "user") MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (message.role == "user") "You" else if (message.state == "succeeded") "Episode reply" else "Incomplete reply", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        if (message.text.isNotBlank()) SelectionContainer { Text(message.text) }
                        if (message.state in listOf("queued", "running")) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            TextButton(onClick = { vm.pauseReply(message.id) }) { Text("Stop reply") }
                        }
                        if (message.role == "assistant" && message.state == "succeeded" && current) {
                            val sources = runCatching { val a = JSONArray(message.sourcesJson); (0 until a.length()).map { a.getLong(it) }.distinct() }.getOrDefault(emptyList())
                            sources.forEach { at -> TextButton(onClick = { vm.play(ep.id, at); vm.open("player") }) { Text("Source · ${timestamp(at)}") } }
                        }
                        if (!current) Text("This message used an older transcript. Source links are disabled.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        message.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (message.role == "assistant" && message.state !in listOf("queued", "running", "succeeded")) TextButton(onClick = { vm.retryReply(message.id) }, enabled = ready && account.connected && !running) { Text("Retry reply") }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!account.connected) TextButton(onClick = { vm.open("settings:ai") }) { Text("Connect ChatGPT to ask questions") }
            OutlinedTextField(draft, vm::chatDraft, label = { Text("Ask about this episode") }, modifier = Modifier.fillMaxWidth(), maxLines = 4, enabled = ready)
            Button(onClick = { vm.ask(ep.id) }, modifier = Modifier.fillMaxWidth(), enabled = ready && account.connected && !running && draft.trim().length >= 2 && "ask:${ep.id}" !in busy) { Text("Send question") }
        }
    }
}
