package dev.distillpod.android.features

import dev.distillpod.android.data.explanationMoment
import dev.distillpod.android.data.explanationMatches
import dev.distillpod.android.data.sourceStartMs

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ExplanationPage(vm: AppViewModel, id: String) {
    val reports by vm.research.collectAsStateWithLifecycle()
    val moments by vm.captures.collectAsStateWithLifecycle()
    val row = reports.firstOrNull { it.id == id } ?: return
    val account by vm.graph.account.state.collectAsStateWithLifecycle()
    val moment = moments.firstOrNull { it.id == row.distillationId }
    val context = LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let { vm.exportExplanation(id, it) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Transcript explanation", style = MaterialTheme.typography.headlineSmall)
        Text("This mode explains the saved excerpt. It does not search the web or verify outside facts. Web-backed research is not enabled in this build.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (row.state) {
            "queued", "running" -> { LinearProgressIndicator(Modifier.fillMaxWidth()); OutlinedButton(onClick = { vm.pauseExplanation(id) }) { Text("Pause explanation") } }
            "succeeded" -> {
                SelectionContainer { Text(row.report) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { export.launch("distillpod-explanation.txt") }) { Text("Export text") }
                    OutlinedButton(onClick = { vm.action("share:$id") {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, vm.explanationText(id)), "Share explanation"))
                    } }) { Text("Share") }
                }
            }
            else -> {
                row.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!account.connected) OutlinedButton(onClick = { vm.open("settings:ai") }) { Text("Connect ChatGPT") }
                if (moment != null) Button(onClick = { vm.explain(moment.id) }, enabled = account.connected) { Text("Retry explanation") }
            }
        }
        if (moment != null) {
            val source=explanationMoment(row,moment)
            HorizontalDivider(); Text("Saved source", style = MaterialTheme.typography.titleMedium)
            if(!explanationMatches(row,moment))Text("This explanation belongs to a previous version of the distill.",style=MaterialTheme.typography.bodySmall)
            SelectionContainer { Text(source.excerpt.ifBlank { "The original excerpt was not saved with this older explanation." }, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = { vm.play(source.episodeId, source.sourceStartMs); vm.open("player") }) { Text("Listen to source · ${timestamp(source.sourceStartMs)}") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun RecommendationsPage(vm: AppViewModel) {
    val topics by vm.suggestionTopics.collectAsStateWithLifecycle()
    val state by vm.recommendationState.collectAsStateWithLifecycle()
    val error by vm.recommendationError.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val podcasts by vm.podcasts.collectAsStateWithLifecycle()
    val account by vm.graph.account.state.collectAsStateWithLifecycle()
    var history by rememberSaveable { mutableStateOf(false) }
    val running = state in listOf("queued", "running")
    val followed = podcasts.filter { it.subscribed }.flatMap { listOf(it.feedUrl, it.originalFeedUrl) }.toSet()
    val visible = suggestions.filter { !it.dismissed && it.feedUrl !in followed }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("What are you curious about?", style = MaterialTheme.typography.headlineSmall)
            Text("ChatGPT suggests up to three searches. Real podcast feeds are checked before results appear. This runs only when you request it and uses your plan allowance.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { OutlinedTextField(topics, vm::suggestionTopics, label = { Text("Topics, languages or interests") }, modifier = Modifier.fillMaxWidth(), maxLines = 4, enabled = !running) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(history, { history = it }, enabled = !running); Text("Also send titles of podcasts I follow", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) } }
        item {
            if (!account.connected) OutlinedButton(onClick = { vm.open("settings:ai") }) { Text("Connect ChatGPT") }
            else if (running) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = vm::cancelRecommendations) { Text("Cancel suggestions") } }
            else Button(onClick = { vm.recommend(history) }, enabled = topics.trim().length >= 2, modifier = Modifier.fillMaxWidth()) { Text("Find suggestions") }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
        items(visible, key = { it.feedUrl }) { suggestion ->
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(suggestion.title, style = MaterialTheme.typography.titleMedium)
                Text(suggestion.author, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(suggestion.reason, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.subscribe(suggestion.feedUrl) }) { Text("Follow") }
                    TextButton(onClick = { vm.dismissSuggestion(suggestion.feedUrl) }) { Text("Dismiss") }
                }
            } }
        }
    }
}
