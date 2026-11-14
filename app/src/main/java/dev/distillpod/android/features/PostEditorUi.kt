package dev.distillpod.android.features

import android.content.Intent
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.distillpod.android.ai.EvidenceSource
import dev.distillpod.android.data.*
import dev.distillpod.android.sharing.*
import dev.distillpod.android.ui.theme.DistillType
import dev.distillpod.android.ui.theme.Button
import dev.distillpod.android.ui.theme.FilterChip
import dev.distillpod.android.ui.theme.OutlinedButton
import dev.distillpod.android.ui.theme.TextButton
import dev.distillpod.android.work.SocialPostJobs
import kotlinx.coroutines.*

@Composable fun PostDraftsPage(vm: AppViewModel, momentId: String? = null, showHeading: Boolean = true) {
    val all by vm.posts.collectAsStateWithLifecycle()
    val posts = all.filter { momentId == null || it.sourceMomentId == momentId }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (showHeading) item { Text("Your posts", style = MaterialTheme.typography.headlineSmall) }
        if (posts.isEmpty()) item { Text("Create a post from a saved moment to start.") }
        items(posts, key = { it.id }) { row ->
            val result = remember(row.content) { runCatching { SocialResult.parse(row.content) }.getOrNull() }
            Card(onClick = { vm.open("post:${row.id}") }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (result == null) Text("Unreadable post", style = MaterialTheme.typography.titleMedium)
                    else {
                        Text(result.artifact?.title?.ifBlank { null } ?: result.source.episode, style = MaterialTheme.typography.titleMedium)
                        Text("${postKind(result)} · ${result.source.show}")
                        if (result.stage != "ready") Text(postStage(result.stage))
                    }
                }
            }
        }
    }
}

/** Named by what the result holds, so an Undo back to an earlier image or text reads as that earlier post. */
internal fun postKind(result: SocialResult): String {
    val artifact = result.artifact
    return when {
        result.format == "legacy" -> "Earlier draft"
        artifact == null || artifact.card.isNotEmpty() -> "Post"
        artifact.image.isNotEmpty() -> "Earlier infographic"
        else -> "Earlier text post"
    }
}

internal fun postStage(stage: String) = when (stage) {
    "queued" -> "Waiting to connect…"
    "preparing", "researching", "illustrating" -> "Writing your post…"
    "failed" -> "Couldn’t finish this version"
    "canceled" -> "Creation paused"
    else -> "Saved on this device"
}

internal fun styleName(style: String) = when (style) {
    CardContent.STYLE_QUOTE -> "Quote"
    CardContent.STYLE_IDEA -> "Big idea"
    else -> "Takeaways"
}

internal fun lookName(look: String) = when (look) {
    CardContent.LOOK_COVER -> "Cover"
    CardContent.LOOK_SIGNAL -> "Signal"
    else -> "Ember"
}

/** What a screen reader hears for the card: the words drawn in [style]. */
internal fun cardDescription(card: CardContent, style: String): String = when (style) {
    CardContent.STYLE_QUOTE -> "Quote card: “${card.quote}”"
    CardContent.STYLE_IDEA -> "Big idea card: ${card.headline.trimEnd('.')}." + card.pull.takeIf { it.isNotBlank() }?.let { " “$it”" }.orEmpty()
    else -> "Takeaways card: ${card.takeawaysTitle.trimEnd('.')}. " +
        card.takeaways.mapIndexed { index, item -> "${index + 1}. ${item.title}: ${item.detail}" }.joinToString(" ")
}

const val CAPTION_COPIED = "Caption copied. Paste it in the app you choose."

@Composable fun PostEditorPage(app: AppViewModel, id: String) {
    val rows by app.posts.collectAsStateWithLifecycle()
    val row = rows.firstOrNull { it.id == id }
    val result = remember(row?.content) { row?.let { runCatching { SocialResult.parse(it.content) }.getOrNull() } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true; failure = null
        scope.launch { try { block() } catch (e: Exception) { if (e is CancellationException) throw e; failure = safeFailure(e) } finally { busy = false } }
    }
    if (result == null) { Text(if (row == null) "This post is unavailable." else "This post could not be opened.", Modifier.padding(24.dp)); return }
    val graph = app.graph
    val assets = graph.postAssets
    val isCard = result.artifact?.card?.isNotEmpty() == true
    PostResult(result, assets, PostActions(
        share = { act {
            if (isCard) { PostShareController.shareCard(context, assets, result); app.messages.send(CAPTION_COPIED) }
            else PostShareController.shareResult(context, assets, result)
        } },
        shareStory = { act { PostShareController.shareCard(context, assets, result, CardSize.Story); app.messages.send(CAPTION_COPIED) } },
        saveImage = { act {
            if (isCard) PostShareController.saveCard(context, assets, result) else PostShareController.saveResult(context, assets, result.artifact!!)
            app.messages.send("Image saved in Pictures/DistillPod")
        } },
        copy = { PostShareController.copy(context, result.shareText()) },
        change = { instruction -> act { graph.socialResults.change(id, instruction) } },
        retry = { act { graph.socialResults.change(id, result.instruction) } },
        editText = { text -> act { graph.socialResults.editText(id, text) } },
        restyle = { style, look -> graph.socialResults.restyle(id, style, look) },
        undo = { act { graph.socialResults.undo(id) } },
        cancel = { act { graph.socialResults.cancel(id); SocialPostJobs.cancel(context, id) } },
        delete = { act { SocialPostJobs.cancel(context, id); graph.posts.delete(id); app.open("posts") } },
        openLink = { url -> act { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } },
    ), busy, failure)
}

/** Callbacks for the post screen, so it can be composed and tested without the app graph. */
internal class PostActions(
    val share: () -> Unit = {}, val shareStory: () -> Unit = {}, val saveImage: () -> Unit = {}, val copy: () -> Unit = {},
    val change: (String) -> Unit = {}, val retry: () -> Unit = {}, val editText: (String) -> Unit = {},
    /** Persists a style and look; throws when refused. Called directly, never queued behind other actions. */
    val restyle: suspend (String, String) -> Unit = { _, _ -> },
    val undo: () -> Unit = {}, val cancel: () -> Unit = {}, val delete: () -> Unit = {}, val openLink: (String) -> Unit = {},
)

/**
 * One post. What is drawn follows the artifact, not the format: a card value shows the card, otherwise a
 * stored image, otherwise text. Only cards offer Change; Try again on any failed result makes a card.
 */
@Composable internal fun PostResult(result: SocialResult, assets: PostAssets, actions: PostActions, busy: Boolean = false, failure: String? = null) {
    val active = result.stage in SocialResult.ACTIVE_STAGES
    val artifact = result.artifact
    val card = remember(artifact?.card) { artifact?.cardContent() }
    var change by rememberSaveable { mutableStateOf(false) }
    var instruction by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editedText by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var sourcesOpen by rememberSaveable { mutableStateOf(false) }
    val edit = { editedText = artifact?.text.orEmpty(); editing = true }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(result.source.episode.ifBlank { "Post" }, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (result.source.show.isNotBlank()) Text(result.source.show, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PostMenu(result, card, active, actions, edit) { deleting = true }
            }
            if (active) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(postStage(result.stage))
                Text("You can leave this screen. Your post will stay here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = actions.cancel) { Text("Cancel") }
            }
            if (result.stage in listOf("failed", "canceled")) {
                Text(result.error.ifBlank { "Creation paused. Your previous version is safe." }, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = actions.retry, enabled = !busy) { Text("Try again") }
            }
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (artifact != null) {
                if (active || result.stage == "failed") Text("Your last completed version", style = MaterialTheme.typography.labelMedium)
                when {
                    card != null -> CardSection(result, card, artifact.text, assets, enabled = !active, restyle = actions.restyle, edit = edit)
                    artifact.image.isNotEmpty() -> {
                        EarlierNote()
                        AsyncImage(model = assets.file(artifact.image), contentDescription = artifact.description,
                            modifier = Modifier.fillMaxWidth().aspectRatio(0.8f))
                    }
                    else -> {
                        EarlierNote()
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                if (artifact.quote.isNotBlank()) Text("“${artifact.quote}”", style = DistillType.quote)
                                Text(artifact.text, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
                Text(result.source.attribution(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // A card quotes the podcast and cites nothing else, so its disclosure is the excerpt, not a source list.
                TextButton(onClick = { sourcesOpen = !sourcesOpen }) { Text(if (card != null) (if (sourcesOpen) "Hide excerpt" else "Show excerpt") else if (sourcesOpen) "Hide sources" else "Sources") }
                if (sourcesOpen) {
                    Text(result.source.excerpt, style = MaterialTheme.typography.bodyMedium)
                    if (result.source.sourceUrl.isNotBlank()) TextButton(onClick = { actions.openLink(result.source.sourceUrl) }) { Text("Podcast episode") }
                    EvidenceSource.list(artifact.sources).forEach { source ->
                        TextButton(onClick = { actions.openLink(source.url) }) { Text("[${source.id}] ${source.title}") }
                    }
                }
            }
        }
        if (artifact != null) Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = actions.share, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Share") }
                if (card != null) OutlinedButton(onClick = { change = true }, enabled = !active && !busy) { Text("Change") }
            }
        }
    }
    if (change) AlertDialog(onDismissRequest = { change = false }, title = { Text("What should change?") },
        text = { OutlinedTextField(instruction, { instruction = it.take(2000) }, placeholder = { Text("Make it shorter…") }, modifier = Modifier.fillMaxWidth(), minLines = 3) },
        confirmButton = { TextButton(onClick = { change = false; actions.change(instruction); instruction = "" }, enabled = instruction.isNotBlank() && !busy) { Text("Update") } },
        dismissButton = { TextButton(onClick = { change = false }) { Text("Cancel") } })
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text(if (card != null) "Edit caption" else "Edit discussion") }, text = {
        OutlinedTextField(editedText, { editedText = it.take(16000) }, modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 12)
    }, confirmButton = { TextButton(onClick = { editing = false; actions.editText(editedText) }, enabled = editedText.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } })
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete this post?") }, text = { Text("Your saved moment will remain.") },
        confirmButton = { TextButton(onClick = { deleting = false; actions.delete() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}

@Composable private fun EarlierNote() =
    Text("Made with an earlier version of posts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable private fun PostMenu(result: SocialResult, card: CardContent?, active: Boolean, actions: PostActions, edit: () -> Unit, delete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val artifact = result.artifact
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More post actions") }
        DropdownMenu(open, { open = false }) {
            @Composable fun entry(label: String, enabled: Boolean = true, onClick: () -> Unit) =
                DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = { open = false; onClick() })
            if (artifact != null && card != null) {
                entry("Copy caption", onClick = actions.copy)
                entry("Save image", onClick = actions.saveImage)
                entry("Share as Story (9:16)", onClick = actions.shareStory)
                entry("Edit caption", enabled = !active, onClick = edit)
            } else if (artifact != null) {
                entry(if (artifact.image.isNotEmpty()) "Copy description & sources" else "Copy text", onClick = actions.copy)
                if (artifact.image.isNotEmpty()) entry("Save image", onClick = actions.saveImage)
                else if (!active) entry("Edit discussion", onClick = edit)
            }
            if (!active && result.previous != null) entry("Undo last change", onClick = actions.undo)
            entry("Delete post", onClick = delete)
        }
    }
}

/** A loaded cover, possibly none; null while the stored cover is still being read. */
private class CoverLoad(val bitmap: Bitmap?)

/**
 * The card preview, its style row and look swatches, and the caption. Styles and looks render locally
 * and persist through [restyle] with no AI request; the shown choice leads the stored one until it arrives.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun CardSection(result: SocialResult, card: CardContent, caption: String, assets: PostAssets, enabled: Boolean,
    restyle: suspend (String, String) -> Unit, edit: () -> Unit) {
    val cover by produceState(if (card.cover.isEmpty()) CoverLoad(null) else null, card.cover) {
        value = CoverLoad(withContext(Dispatchers.IO) { PostShareController.cover(assets, card.cover) })
    }
    var desired by remember { mutableStateOf<Pair<String, String>?>(null) }
    var restyleFailure by remember { mutableStateOf<String?>(null) }
    val style = desired?.first ?: card.style
    val look = desired?.second ?: card.look
    // Cleared only once the stored card shows the choice, so the pager never jumps back to the old style meanwhile.
    LaunchedEffect(card.style, card.look) { if (desired == card.style to card.look) desired = null }
    LaunchedEffect(desired) {
        val wanted = desired ?: return@LaunchedEffect
        try { restyle(wanted.first, wanted.second); restyleFailure = null }
        catch (e: Exception) { if (e is CancellationException) throw e; restyleFailure = safeFailure(e); if (desired == wanted) desired = null }
    }
    val styles = CardContent.STYLES
    val pager = rememberPagerState(initialPage = styles.indexOf(style).coerceAtLeast(0)) { styles.size }
    val shownStyle by rememberUpdatedState(style)
    val shownLook by rememberUpdatedState(look)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page -> if (styles[page] != shownStyle) desired = styles[page] to shownLook }
    }
    LaunchedEffect(style) {
        val target = styles.indexOf(style).coerceAtLeast(0)
        if (pager.settledPage != target && !pager.isScrollInProgress) pager.animateScrollToPage(target)
    }
    // Feed-size bitmaps are large: keep a few per card version and let older ones go to the garbage collector.
    val cache = remember(card.copy(style = "", look = ""), result.source.show, result.source.episode, cover) { LruCache<String, ImageBitmap>(4) }
    HorizontalPager(pager, Modifier.fillMaxWidth(), pageSpacing = 12.dp, userScrollEnabled = enabled, key = { styles[it] }) { page ->
        CardPreview(card, styles[page], look, result.source.show, result.source.episode, cover, cache)
    }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        styles.forEach { option ->
            FilterChip(selected = option == style, onClick = { desired = option to look }, label = { Text(styleName(option)) }, enabled = enabled)
        }
    }
    val looks = CardContent.LOOKS.filter { it != CardContent.LOOK_COVER || (card.cover.isNotEmpty() && card.tint != 0) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        looks.forEach { option ->
            LookSwatch(option, option == look, enabled, cover?.bitmap, card.tint) { desired = style to option }
        }
    }
    restyleFailure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Text("Caption", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
    SelectionContainer { Text(caption, style = MaterialTheme.typography.bodyLarge) }
    TextButton(onClick = edit, enabled = enabled) { Text("Edit caption") }
}

@Composable private fun CardPreview(card: CardContent, style: String, look: String, show: String, episode: String, cover: CoverLoad?,
    cache: LruCache<String, ImageBitmap>) {
    val context = LocalContext.current
    val key = "$style/$look"
    // Keeps the previous picture while a new look renders, so switching never flashes empty.
    var image by remember(cache) { mutableStateOf(cache.get(key)) }
    LaunchedEffect(key, cache, cover) {
        if (cover == null) return@LaunchedEffect
        cache.get(key)?.let { image = it; return@LaunchedEffect }
        image = try {
            withContext(Dispatchers.Default) {
                ShareCardRenderer.render(context, card, show, episode, cover.bitmap, CardSize.Feed, style, look).asImageBitmap()
            }.also { cache.put(key, it) }
        } catch (e: Exception) { if (e is CancellationException) throw e; null }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(CardSize.Feed.width / CardSize.Feed.height.toFloat()).clip(MaterialTheme.shapes.small)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        // A dark card on the dark page needs an edge.
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)) {
        image?.let { Image(it, cardDescription(card, style), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

@Composable private fun LookSwatch(look: String, selected: Boolean, enabled: Boolean, cover: Bitmap?, tint: Int, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val coverImage = remember(cover) { cover?.asImageBitmap() }
    Box(Modifier.size(52.dp).clip(CircleShape).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = "${lookName(look)} look" }.alpha(if (enabled) 1f else .38f), contentAlignment = Alignment.Center) {
        Box(Modifier.size(42.dp).border(if (selected) 2.5.dp else 1.dp, ring, CircleShape).padding(5.dp).clip(CircleShape).then(when (look) {
            CardContent.LOOK_SIGNAL -> Modifier.background(Color(0xFFFFB547))
            CardContent.LOOK_COVER -> Modifier.background(Color(tint))
            else -> Modifier.background(Brush.radialGradient(listOf(Color(0xFF3B2A12), Color(0xFF191816), Color(0xFF0F0E0D))))
        }), contentAlignment = Alignment.Center) {
            when {
                look == CardContent.LOOK_COVER && coverImage != null -> Image(coverImage, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                look == CardContent.LOOK_SIGNAL -> Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF1A1206)))
                look == CardContent.LOOK_EMBER -> Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFFB547)))
            }
        }
    }
}
