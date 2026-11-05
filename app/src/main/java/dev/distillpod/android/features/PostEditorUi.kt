package dev.distillpod.android.features

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.distillpod.android.data.*
import dev.distillpod.android.sharing.*
import kotlinx.coroutines.*

@Composable fun PostDraftsPage(vm: AppViewModel, momentId: String? = null) {
    val all by vm.posts.collectAsStateWithLifecycle()
    val posts = all.filter { momentId == null || it.sourceMomentId == momentId }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Post drafts", style = MaterialTheme.typography.headlineSmall); Text("Saved on this device and included in library backups.") }
        if (posts.isEmpty()) item { Text("Create a post from a saved moment to start.") }
        items(posts, key = { it.id }) { row ->
            val content = runCatching { PostContent.parse(row.content) }.getOrNull()
            Card(onClick = { vm.open("post:${row.id}") }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text(content?.headline?.ifBlank { content.episode } ?: "Unreadable draft", style = MaterialTheme.typography.titleMedium)
                    Text("${content?.destination.orEmpty()} · ${content?.show.orEmpty()}")
                    if (row.sourceMomentId == null) Text("Original moment removed · source preserved")
                    Row {
                        TextButton(onClick = { vm.duplicatePost(row.id) }) { Text("Duplicate") }
                        TextButton(onClick = { vm.deletePost(row.id) }) { Text("Delete draft") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun PostEditorPage(app: AppViewModel, id: String) {
    val editor: PostEditorViewModel = viewModel(key = "post-editor:$id", factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = PostEditorViewModel(app.graph, id, extras.createSavedStateHandle()) as T
    })
    val state by editor.state.collectAsStateWithLifecycle()
    val content = state.content
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var design by rememberSaveable { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var renderError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(content) {
        bitmap = null; renderError = null
        if (content != null) {
            delay(300)
            try { bitmap = withContext(Dispatchers.Default) { PostCardRenderer.render(content) } }
            catch (e: Exception) { if (e is CancellationException) throw e; renderError = e.message }
        }
    }
    fun output(action: suspend (PostContent) -> Unit) {
        if (exporting) return
        exporting = true
        scope.launch {
            try { val snapshot = editor.flush() ?: return@launch; action(snapshot) }
            catch (e: Exception) { if (e is CancellationException) throw e; editor.report(safeFailure(e)) }
            finally { exporting = false }
        }
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (state.saved) "Saved on this device" else "Saving changes…", style = MaterialTheme.typography.labelMedium)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { scope.launch { editor.flush() } }) { Text("Retry save") } }
        if (content == null) return@Column
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = !design, onClick = { design = false }, label = { Text("Content") })
            FilterChip(selected = design, onClick = { design = true }, label = { Text("Design & preview") })
        }
        if (!design) {
            Choices("Format", listOf("LinkedIn", "Instagram"), content.destination) { editor.edit { p -> p.copy(destination = it) } }
            Choices("Post language", listOf("en", "pt-BR"), content.language) { editor.edit { p -> p.copy(language = it) } }
            PostField("Your perspective · what stood out to you?", content.perspective, 2000) { editor.edit { p -> p.copy(perspective = it) } }
            Text("Your perspective helps AI draft the post. Review the post body for the final wording.", style = MaterialTheme.typography.bodySmall)
            PostField("Headline", content.headline, 300) { editor.edit { p -> p.copy(headline = it) } }
            PostField("Takeaway", content.takeaway, 2500) { editor.edit { p -> p.copy(takeaway = it) } }
            PostField(if (content.destination == "Instagram") "Caption" else "Post body", content.body, 12000) { editor.edit { p -> p.copy(body = it) } }
            PostField("Original-language quote · exact source text", content.quote, 1600) { editor.edit { p -> p.copy(quote = it) } }
            Text("Quotes stay in the source language. Put your translation in the takeaway and label it as a translation.", style = MaterialTheme.typography.bodySmall)
            PostField("Public source link (optional)", content.sourceUrl, 2048) { editor.edit { p -> p.copy(sourceUrl = it) } }
            Text("Source: ${content.attribution()}", style = MaterialTheme.typography.bodySmall)
            if (content.excerpt.isNotBlank()) {
                var sourceOpen by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = { sourceOpen = !sourceOpen }) { Text(if (sourceOpen) "Hide original excerpt" else "View original excerpt") }
                if (sourceOpen) Text(content.excerpt)
            }
            val rows by app.posts.collectAsStateWithLifecycle()
            val momentId = rows.firstOrNull { it.id == id }?.sourceMomentId
            val moments by app.captures.collectAsStateWithLifecycle()
            val source = moments.firstOrNull { it.id == momentId }
            val episodes by app.episodes.collectAsStateWithLifecycle()
            val episode = episodes.firstOrNull { it.id == source?.episodeId }
            if (episode != null && episode.mediaFingerprint != content.sourceFingerprint) Text("The source audio changed. Review it before relying on this replay time.", color = MaterialTheme.colorScheme.tertiary)
            if (source != null) TextButton(onClick = { app.play(source.episodeId, content.startMs) }) { Text("Replay source") }
            Text("AI sends this excerpt and your draft to your connected ChatGPT account and uses its allowance.", style = MaterialTheme.typography.bodySmall)
            if (state.generating) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick = editor::cancel) { Text("Cancel generation") }
            } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Draft with AI", "Shorten", "Simplify", "Rewrite opening", "Use my perspective").forEach { action ->
                    OutlinedButton(onClick = { editor.generate(action) }, enabled = content.excerpt.isNotBlank()) { Text(action) }
                }
            }
            state.proposal?.let { proposal ->
                Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Suggested revision", style = MaterialTheme.typography.titleMedium)
                    Text(proposal.headline); Text(proposal.takeaway); Text(proposal.body)
                    Button(onClick = editor::accept) { Text("Use version") }
                    TextButton(onClick = editor::reject) { Text("Keep current") }
                } }
            }
            if (state.canUndo) TextButton(onClick = editor::undo) { Text("Undo accepted revision") }
        } else {
            Choices("Layout", listOf("Idea", "Quote"), content.layout) { editor.edit { p -> p.copy(layout = it) } }
            Choices("Theme", listOf("Ink", "Lavender", "Mint"), content.theme) { editor.edit { p -> p.copy(theme = it) } }
            Choices("Image size", listOf("Portrait", "Square"), if (content.square) "Square" else "Portrait") { editor.edit { p -> p.copy(square = it == "Square") } }
            Row { Checkbox(content.branding, onCheckedChange = { editor.edit { p -> p.copy(branding = it) } }); Text("Made with DistillPod") }
            bitmap?.let { Image(it.asImageBitmap(), contentDescription = content.altText(), modifier = Modifier.fillMaxWidth().aspectRatio(if (content.square) 1f else 0.8f)) }
            renderError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            PostField("Image description (optional)", content.imageDescription, 4000) { editor.edit { p -> p.copy(imageDescription = it) } }
            TextButton(onClick = { PostShareController.copy(context, content.altText()) }) { Text("Copy image description") }
        }
        HorizontalDivider()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { output { PostShareController.text(context, it) } }, enabled = !exporting) { Text("Share text") }
            OutlinedButton(onClick = { try { PostShareController.copy(context, content.validated().shareText()) } catch (e: Exception) { editor.report(safeFailure(e)) } }) { Text("Copy caption") }
            OutlinedButton(onClick = { output { PostShareController.image(context, it) } }, enabled = !exporting) { Text("Share image") }
            OutlinedButton(onClick = { output { PostShareController.save(context, it); app.messages.send("Image saved in Pictures/DistillPod") } }, enabled = !exporting) { Text("Save image") }
        }
        Text("Finish posting in the receiving app. If the caption is missing, use Copy caption.", style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun Choices(label: String, values: List<String>, selected: String, choose: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { values.forEach { value -> FilterChip(selected == value, { choose(value) }, label = { Text(value) }) } }
}
@Composable private fun PostField(label: String, value: String, limit: Int, change: (String) -> Unit) {
    OutlinedTextField(value, { change(it.take(limit)) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), maxLines = 12)
}
