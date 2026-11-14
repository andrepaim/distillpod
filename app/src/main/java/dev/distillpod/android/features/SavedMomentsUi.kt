package dev.distillpod.android.features

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.distillpod.android.data.*
import org.json.JSONObject
import dev.distillpod.android.ui.theme.DistillType

internal data class SavedMomentItem(
    val id: String, val state: String, val excerpt: String, val insight: String?, val quote: String?,
    val episodeTitle: String?, val showTitle: String?, val artworkUrl: String?, val startMs: Long, val endMs: Long,
)

internal fun savedMomentItems(captures: List<DistillationEntity>, episodes: List<EpisodeEntity>, podcasts: List<PodcastEntity>): List<SavedMomentItem> {
    val episodeById = episodes.associateBy { it.id }
    val showById = podcasts.associateBy { it.id }
    return captures.map { saved ->
        val ep = episodeById[saved.episodeId]
        val show = ep?.let { showById[it.podcastId] }
        SavedMomentItem(saved.id, saved.state, saved.excerpt, saved.insight, saved.quote, ep?.title, show?.title,
            ep?.imageUrl ?: show?.imageUrl, saved.sourceStartMs, saved.sourceEndMs)
    }
}

internal fun SavedMomentItem.matches(query: String): Boolean = query.isBlank() ||
    listOfNotNull(quote, excerpt, insight, episodeTitle, showTitle).any { it.contains(query.trim(), ignoreCase = true) }

internal fun captureState(state: String) = when (state) {
    "waiting_transcript" -> "Waiting for transcript"; "waiting_auth" -> "Connect ChatGPT to continue"
    "waiting_quota" -> "Plan allowance unavailable"; "queued", "running" -> "Finding the idea"; "reviewing" -> "Checking the source"
    "needs_context" -> "Needs more context"; "unclear_source" -> "Review the transcript"; "failed" -> "Needs retry"
    "review_source" -> "Review source"; "no_speech" -> "No speech in this window"; "saved" -> "No insight yet"; "imported" -> "Imported"; else -> "Saved"
}

/** A finished (or imported) moment needs no badge, nor does the generic "Saved"; every other state is worth a glance. */
internal fun momentStatus(state: String): String? = if (state in finishedStates) null else captureState(state).takeIf { it != "Saved" }
private val finishedStates = setOf("succeeded", "imported")

/** Shows with saved moments, the most saved first, for the Saved page's show filter. */
internal fun savedShows(items: List<SavedMomentItem>): List<String> =
    items.mapNotNull { it.showTitle }.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }

/** Moments matching [query] and, when any are selected, one of [shows]. */
internal fun List<SavedMomentItem>.filtered(query: String, shows: Set<String>): List<SavedMomentItem> =
    filter { it.matches(query) && (shows.isEmpty() || it.showTitle in shows) }

@Composable internal fun SavedPage(vm: AppViewModel, captures: List<DistillationEntity>, episodes: List<EpisodeEntity>, podcasts: List<PodcastEntity>) {
    var query by rememberSaveable { mutableStateOf("") }
    var shows by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    val items = remember(captures, episodes, podcasts) { savedMomentItems(captures, episodes, podcasts) }
    val showOptions = remember(items) { savedShows(items) }
    val matching = remember(items, query, shows) { items.filtered(query, shows.toSet()) }
    SavedContent(matching, items.size, query, { query = it }, showOptions, shows.toSet(),
        { show -> shows = ArrayList(if (show in shows) shows - show else shows + show) },
        { query = ""; shows = ArrayList() }, { vm.open("capture:$it") }, { vm.root("library") })
}

/**
 * Saved moments (proposal 5.5): a search field and show filter chips that scroll away with the list, then
 * compact quote cards. Posts moved to the top bar's overflow menu ("Your posts").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun SavedContent(
    moments: List<SavedMomentItem>, total: Int, query: String, onQuery: (String) -> Unit,
    shows: List<String> = emptyList(), selectedShows: Set<String> = emptySet(), onShow: (String) -> Unit = {}, onClearFilters: () -> Unit = { onQuery("") },
    onOpen: (String) -> Unit, onBrowse: () -> Unit,
) {
    val filtering = query.isNotBlank() || selectedShows.isNotEmpty()
    // The search field is a list item, so it scrolls away and leaves the screen to the moments.
    LazyColumn(Modifier.fillMaxSize().testTag("saved-list"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (total == 0) {
            item(key = "saved-empty") { EmptySaved(onBrowse) }
            return@LazyColumn
        }
        item(key = "saved-search") { LocalSearch(query, onQuery, "Search saved moments", "Clear saved search") }
        // FilterChip: several shows can be selected at once (proposal 4). Wrapped rather than scrolled sideways.
        if (shows.size > 1) item(key = "saved-shows") {
            FlowRow(Modifier.fillMaxWidth().testTag("saved-show-filters"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                shows.forEach { show ->
                    FilterChip(selected = show in selectedShows, onClick = { onShow(show) },
                        label = { Text(show, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) })
                }
            }
        }
        item(key = "saved-count") {
            Text(if (!filtering) countLabel(total) else "${moments.size} of ${countLabel(total)}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (moments.isEmpty()) item(key = "saved-no-match") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("No matching moments", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClearFilters) { Text(if (selectedShows.isEmpty()) "Clear search" else "Clear filters") }
            }
        }
        items(moments, key = { it.id }) { SavedMomentCard(it, onOpen) }
    }
}

private fun countLabel(count: Int) = "$count ${if (count == 1) "moment" else "moments"}"

/** The quote at reading size, three lines at most, over one source line: artwork · episode · time. */
@Composable private fun SavedMomentCard(item: SavedMomentItem, onOpen: (String) -> Unit) {
    val status = momentStatus(item.state)
    Card(onClick = { onOpen(item.id) }, modifier = Modifier.fillMaxWidth().testTag("saved-moment-card"), shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(item.insight ?: item.quote?.let { "“$it”" } ?: "Moment at ${timestamp(item.startMs)}", maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = DistillType.quoteCompact)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PodcastCover(item.artworkUrl, Modifier.size(24.dp), MaterialTheme.shapes.extraSmall)
                Text("${item.episodeTitle ?: "Episode"} · ${timestamp(item.startMs)}", Modifier.weight(1f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                status?.let { StatusLabel(it) }
            }
        }
    }
}

@Composable private fun EmptySaved(onBrowse: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Default.Bookmark, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Keep the moments that matter", style = MaterialTheme.typography.headlineSmall)
        Text("Save the last minute in the player. Your quotes, insights and original sources will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onBrowse) { Text("Open library") }
    }
}

/** Callbacks for the moment page; defaults keep previews and tests concise. */
internal class MomentActions(
    val replay: () -> Unit = {}, val share: () -> Unit = {}, val copy: () -> Unit = {},
    val createPost: () -> Unit = {}, val openPosts: () -> Unit = {}, val explain: () -> Unit = {},
    val improve: () -> Unit = {}, val expandContext: () -> Unit = {}, val retranscribe: () -> Unit = {},
    val generate: () -> Unit = {}, val prepare: () -> Unit = {}, val pause: () -> Unit = {},
    val connectAccount: () -> Unit = {}, val restore: (String) -> Unit = {}, val delete: () -> Unit = {},
)

internal fun momentShareText(moment: DistillationEntity, episodeTitle: String?) = listOfNotNull(moment.insight, moment.quote?.let { "“$it”" },
    episodeTitle?.let { "$it · ${timestamp(moment.sourceStartMs)}–${timestamp(moment.sourceEndMs)}" }).joinToString("\n\n")

@Composable internal fun CapturePage(vm: AppViewModel, capture: DistillationEntity?, episodes: List<EpisodeEntity>, podcasts: List<PodcastEntity>) {
    if (capture == null) return
    val context = LocalContext.current
    val ep = episodes.firstOrNull { it.id == capture.episodeId }
    val show = podcasts.firstOrNull { it.id == ep?.podcastId }
    var createPost by rememberSaveable { mutableStateOf(false) }
    if (createPost) CreatePostSheet(vm, capture.id) { createPost = false }
    val dao = vm.graph.db.library()
    val pending by remember(capture.attempt) { dao.observeMomentAttempt(capture.attempt) }.collectAsStateWithLifecycle(null)
    val versions by remember(capture.id) { dao.observeMomentVersions(capture.id) }.collectAsStateWithLifecycle(emptyList())
    val reports by vm.research.collectAsStateWithLifecycle()
    val latest = reports.firstOrNull { it.distillationId == capture.id && it.state in listOf("queued", "running", "succeeded") && explanationMatches(it, capture) }
    val contextJson = pending?.takeIf { capture.state == "review_source" }?.contextJson
    val reviewContext = remember(contextJson) { contextJson?.let { json ->
        runCatching { if (JSONObject(json).has("spans")) MomentGeneration.context(json).spans.joinToString(" ") { it.text } else null }.getOrNull()
    } }
    val share = momentShareText(capture, ep?.title)
    MomentDetail(capture, ep, show?.title, latest, versions, reviewContext, MomentActions(
        replay = { vm.play(capture.episodeId, capture.sourceStartMs); vm.open("player") },
        share = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, share), "Share saved moment")) },
        copy = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("DistillPod", share)) },
        createPost = { createPost = true },
        openPosts = { vm.open("posts:${capture.id}") },
        explain = { if (latest == null) vm.explain(capture.id) else vm.open("research:${latest.id}") },
        improve = { vm.improveCapture(capture.id) },
        expandContext = { vm.improveCapture(capture.id, true) },
        retranscribe = { vm.retranscribeCapture(capture.id) },
        generate = { vm.generateCapture(capture.id, true) },
        prepare = { vm.generateCapture(capture.id) },
        pause = { vm.cancelCapture(capture.id) },
        connectAccount = { vm.open("settings:ai") },
        restore = { vm.restoreCapture(capture.id, it) },
        delete = { vm.deleteCapture(capture.id); vm.back() },
    )) { ep?.let { TranscriptTools(vm, it) } }
}

private val workingStates = setOf("waiting_transcript", "queued", "running", "reviewing")
private val retryStates = setOf("review_source", "waiting_auth", "waiting_quota", "failed", "saved")
private val contextStates = setOf("needs_context", "unclear_source")

/**
 * A saved moment: the episode and passage first, then the idea, then three primary actions.
 * Less frequent actions live in one overflow menu; recovery actions appear only for unfinished states.
 */
@Composable internal fun MomentDetail(
    capture: DistillationEntity, ep: EpisodeEntity?, showTitle: String?, explanation: ResearchEntity?,
    versions: List<MomentVersionEntity>, reviewContext: String?, actions: MomentActions,
    transcriptTools: @Composable () -> Unit = {},
) {
    val working = capture.state in workingStates
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ep?.title ?: "Episode", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(showTitle, "${timestamp(capture.sourceStartMs)}–${timestamp(capture.sourceEndMs)}").joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MomentMenu(capture, ep, explanation, working, actions)
        }
        if (ep != null && capture.fingerprint.isNotBlank() && capture.fingerprint != ep.mediaFingerprint)
            Text("The audio source changed since this moment was saved. The saved text is preserved; review the audio before relying on its replay time.",
                color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        if (momentStatus(capture.state) != null || capture.error != null) MomentStatusPanel(capture, ep, working, reviewContext, actions, transcriptTools)
        capture.insight?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.headlineSmall) } }
        capture.quote?.let { SelectionContainer { Text("“$it”", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        MomentPrimaryActions(capture, actions)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .3f))
        Disclosure("Source passage", capture.id + ":source") {
            SelectionContainer { Text(capture.excerpt.ifBlank { "The source text is waiting for a transcript." }, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (capture.sourceJson.isNotBlank()) {
                Text("Saved at ${timestamp(capture.endMs)} · Passage ${timestamp(capture.sourceStartMs)}–${timestamp(capture.sourceEndMs)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (capture.sourceEndMs > capture.endMs) Text("The passage includes a few seconds after your tap to finish the thought.", style = MaterialTheme.typography.bodySmall)
                runCatching { JSONObject(capture.sourceJson).optString("originalExcerpt") }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
                    Text("Original capture", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        // Unreadable snapshots are left out of both the list and its count.
        val restorable = remember(versions, capture) { versions.mapNotNull { v -> runCatching { v to restoreMomentSnapshot(capture, v.snapshot) }.getOrNull() } }
        if (restorable.isNotEmpty()) Disclosure("Previous versions (${restorable.size})", capture.id + ":versions") {
            restorable.forEach { (version, previous) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(previous.insight.orEmpty())
                        previous.quote?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall) }
                        Text("Source ${timestamp(previous.sourceStartMs)}–${timestamp(previous.sourceEndMs)}", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { actions.restore(version.id) }, enabled = !working) { Text("Restore this version") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun MomentPrimaryActions(capture: DistillationEntity, actions: MomentActions) {
    // Compact padding keeps all three on one row on a typical phone; larger text wraps instead of clipping.
    val padding = PaddingValues(start = 12.dp, top = 8.dp, end = 16.dp, bottom = 8.dp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = actions.replay, contentPadding = padding) {
            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Replay ${timestamp(capture.sourceStartMs)}")
        }
        OutlinedButton(onClick = actions.share, contentPadding = padding) {
            Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Share")
        }
        OutlinedButton(onClick = actions.copy, contentPadding = padding) {
            Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copy")
        }
    }
}

@Composable private fun MomentMenu(capture: DistillationEntity, ep: EpisodeEntity?, explanation: ResearchEntity?, working: Boolean, actions: MomentActions) {
    var open by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(capture.id) { mutableStateOf(false) }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this saved moment?") },
        text = { Text("Its insight, quote and earlier versions are removed. Posts made from it are kept.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; actions.delete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More actions for this moment") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable fun entry(label: String, icon: ImageVector, enabled: Boolean = true, detail: String? = null, onClick: () -> Unit) =
                DropdownMenuItem(text = {
                    Column(Modifier.padding(vertical = if (detail == null) 0.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(label)
                        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }, leadingIcon = { Icon(icon, null) }, enabled = enabled, onClick = { open = false; onClick() })
            entry("Create post", Icons.Default.PostAdd, enabled = capture.excerpt.isNotBlank(), onClick = actions.createPost)
            entry("Posts from this moment", Icons.AutoMirrored.Filled.Article, onClick = actions.openPosts)
            if (capture.excerpt.isNotBlank())
                entry(if (explanation?.state == "succeeded") "Read explanation" else "Explain this idea", Icons.Default.Lightbulb, onClick = actions.explain)
            if (!working && ep != null && capture.insight != null)
                entry("Improve this distill", Icons.Default.AutoFixHigh, detail = "Uses two ChatGPT requests. The current result is kept meanwhile.", onClick = actions.improve)
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Delete saved moment", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { open = false; confirmDelete = true })
        }
    }
}

/** Shown only while a moment is unfinished or reports an error, with the action that moves it forward. */
@Composable private fun MomentStatusPanel(capture: DistillationEntity, ep: EpisodeEntity?, working: Boolean, reviewContext: String?,
    actions: MomentActions, transcriptTools: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            momentStatus(capture.state)?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary) }
            if (working) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick = actions.pause) { Text("Pause generation") }
            }
            if (capture.state == "saved" && capture.excerpt.isBlank() && ep != null) OutlinedButton(onClick = actions.prepare) { Text("Prepare saved moment") }
            if (capture.state == "waiting_transcript" && ep != null) transcriptTools()
            if (capture.state == "waiting_auth") Button(onClick = actions.connectAccount) { Text("Connect ChatGPT") }
            if (capture.state == "review_source") {
                Text("Review the proposed source context below before sending it to ChatGPT. The saved result is kept until its replacement passes review.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                Text("Proposed source context", style = MaterialTheme.typography.titleSmall)
                if (reviewContext != null) SelectionContainer { Text(reviewContext, style = MaterialTheme.typography.bodyMedium) }
                else Text("The proposed source context isn’t available to show. Don’t generate until it appears.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (capture.excerpt.isNotBlank() && capture.state in retryStates) {
                Button(onClick = actions.generate) {
                    Text(when (capture.state) { "review_source" -> "Generate insight from this source"; "saved" -> "Generate insight"; else -> "Retry insight" })
                }
                Text("Retrying a request can use more of your ChatGPT allowance.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!working && ep != null && capture.state in contextStates) {
                OutlinedButton(onClick = actions.expandContext) { Text("Expand context and retry") }
                OutlinedButton(onClick = actions.retranscribe) { Text("Transcribe and retry") }
                Text("For clearer speech recognition, select Base in Settings before transcribing again. Generation and source review use two AI requests; your current saved result is kept while a replacement is prepared.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            capture.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** A labelled show/hide row that announces its state, with at least a 48 dp touch target. */
@Composable private fun Disclosure(label: String, key: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = if (expanded) "Hide" else "Show", role = Role.Button) { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) content()
    }
}
