package dev.distillpod.android.features

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.distillpod.android.ai.EvidenceSource
import dev.distillpod.android.data.*
import dev.distillpod.android.sharing.*
import dev.distillpod.android.work.SocialPostJobs
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CreatePostSheet(vm: AppViewModel, moment: String, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("What would you like to share?", style = MaterialTheme.typography.headlineSmall)
            Card(onClick = { dismiss(); vm.createPost(moment, "discussion") }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Quote & discussion", style = MaterialTheme.typography.titleLarge)
                    Text("A striking quote and a thoughtful, beautifully spaced text post.")
                }
            }
            Card(onClick = { dismiss(); vm.createPost(moment, "infographic") }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Infographic", style = MaterialTheme.typography.titleLarge)
                    Text("An illustrated explanation, enriched with web research and sources.")
                }
            }
            Text("Uses your connected ChatGPT account. Infographics also use web search and image generation.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable fun PostDraftsPage(vm: AppViewModel, momentId: String? = null, showHeading: Boolean = true) {
    val all by vm.posts.collectAsStateWithLifecycle()
    val posts = all.filter { momentId == null || it.sourceMomentId == momentId }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (showHeading) item { Text("Your posts", style = MaterialTheme.typography.headlineSmall) }
        if (posts.isEmpty()) item { Text("Create a post from a saved moment to start.") }
        items(posts, key = { it.id }) { row ->
            val result = runCatching { SocialResult.parse(row.content) }.getOrNull()
            Card(onClick = { vm.open("post:${row.id}") }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(result?.artifact?.title?.ifBlank { result.source.episode } ?: result?.source?.episode ?: "Unreadable post", style = MaterialTheme.typography.titleMedium)
                    Text("${if (result?.format == "infographic") "Infographic" else if (result?.format == "legacy") "Earlier draft" else "Quote & discussion"} · ${result?.source?.show.orEmpty()}")
                    if (result?.stage != "ready") Text(postStage(result?.stage.orEmpty()))
                }
            }
        }
    }
}

private fun postStage(stage: String) = when(stage) {
    "queued" -> "Waiting to connect…"
    "preparing" -> "Finding the clearest way to share this idea…"
    "researching" -> "Researching supporting evidence…"
    "illustrating" -> "Illustrating the idea…"
    "failed" -> "Couldn’t finish this version"
    "canceled" -> "Creation paused"
    else -> "Saved on this device"
}

@Composable fun PostEditorPage(app: AppViewModel, id: String) {
    val rows by app.posts.collectAsStateWithLifecycle()
    val row = rows.firstOrNull { it.id == id }
    val result = remember(row?.content) { row?.let { runCatching { SocialResult.parse(it.content) }.getOrNull() } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var change by rememberSaveable { mutableStateOf(false) }
    var instruction by rememberSaveable { mutableStateOf("") }
    var sourcesOpen by rememberSaveable { mutableStateOf(false) }
    var edit by rememberSaveable { mutableStateOf(false) }
    var editedText by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true; failure = null
        scope.launch { try { block() } catch (e: Exception) { if (e is CancellationException) throw e; failure = safeFailure(e) } finally { busy = false } }
    }
    if (result == null) { Text(if (row == null) "This post is unavailable." else "This post could not be opened.", Modifier.padding(24.dp)); return }
    val active = result.stage in listOf("queued", "preparing", "researching", "illustrating")
    val artifact = result.artifact
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (result.format == "infographic") "Infographic" else if (result.format == "legacy") "Earlier draft" else "Quote & discussion", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More post actions") }
                    DropdownMenu(menu, { menu = false }) {
                        if (artifact != null) DropdownMenuItem(text = { Text(if (result.format == "infographic") "Copy description & sources" else "Copy text") }, onClick = { menu = false; PostShareController.copy(context,result.shareText()) })
                        if (artifact != null && result.format == "infographic") DropdownMenuItem(text = { Text("Save image") }, onClick = { menu = false; act { PostShareController.saveResult(context,app.graph.postAssets,artifact); app.messages.send("Image saved in Pictures/DistillPod") } })
                        if (!active && artifact != null && result.format != "infographic") DropdownMenuItem(text = { Text("Edit discussion") }, onClick = { menu = false; editedText = artifact.text; edit = true })
                        if (!active && result.previous != null) DropdownMenuItem(text = { Text("Undo last change") }, onClick = { menu = false; act { app.graph.socialResults.undo(id) } })
                        DropdownMenuItem(text = { Text("Delete post") }, onClick = { menu = false; deleting = true })
                    }
                }
            }
            if (active) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(postStage(result.stage))
                Text("You can leave this screen. Your post will stay here.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { act { app.graph.socialResults.cancel(id); SocialPostJobs.cancel(context,id) } }) { Text("Cancel") }
            }
            if (result.stage in listOf("failed", "canceled")) {
                Text(result.error.ifBlank { "Creation paused. Your previous version is safe." }, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { act { app.graph.socialResults.change(id,result.instruction) } }, enabled = !busy) { Text("Try again") }
            }
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (artifact != null) {
                if (active || result.stage == "failed") Text("Your last completed version", style = MaterialTheme.typography.labelMedium)
                if (result.format == "infographic") {
                    AsyncImage(model = app.graph.postAssets.file(artifact.image), contentDescription = artifact.description,
                        modifier = Modifier.fillMaxWidth().aspectRatio(0.8f))
                } else SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        if (artifact.quote.isNotBlank()) Text("“${artifact.quote}”", style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif))
                        Text(artifact.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Text(result.source.attribution(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { sourcesOpen = !sourcesOpen }) { Text(if (sourcesOpen) "Hide sources" else "Sources") }
                if (sourcesOpen) {
                    Text(result.source.excerpt, style = MaterialTheme.typography.bodyMedium)
                    if (result.source.sourceUrl.isNotBlank()) TextButton(onClick = { act { context.startActivity(Intent(Intent.ACTION_VIEW,result.source.sourceUrl.toUri())) } }) { Text("Podcast episode") }
                    EvidenceSource.list(artifact.sources).forEach { source ->
                        TextButton(onClick = { act { context.startActivity(Intent(Intent.ACTION_VIEW,source.url.toUri())) } }) { Text("[${source.id}] ${source.title}") }
                    }
                }
            }
        }
        if (artifact != null) Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { act { PostShareController.shareResult(context, app.graph.postAssets, result) } }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Share") }
                OutlinedButton(onClick = { change = true }, enabled = !active && !busy) { Text("Change") }
            }
        }
    }
    if (change) AlertDialog(onDismissRequest = { change = false }, title = { Text("What should change?") },
        text = { OutlinedTextField(instruction, { instruction = it.take(2000) }, placeholder = { Text("Make it more concise…") }, modifier = Modifier.fillMaxWidth(), minLines = 3) },
        confirmButton = { TextButton(onClick = { change = false; act { app.graph.socialResults.change(id,instruction); instruction = "" } }, enabled = instruction.isNotBlank() && !busy) { Text("Update") } },
        dismissButton = { TextButton(onClick = { change = false }) { Text("Cancel") } })
    if (edit) AlertDialog(onDismissRequest = { edit = false }, title = { Text("Edit discussion") }, text = {
        OutlinedTextField(editedText, { editedText = it.take(12000) }, minLines = 5, maxLines = 12)
    }, confirmButton = { TextButton(onClick = { edit = false; act { app.graph.socialResults.editText(id,editedText) } }, enabled = editedText.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = { edit = false }) { Text("Cancel") } })
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete this post?") }, text = { Text("Your saved moment will remain.") },
        confirmButton = { TextButton(onClick = { deleting = false; act { SocialPostJobs.cancel(context,id); app.graph.posts.delete(id); app.open("posts") } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}
