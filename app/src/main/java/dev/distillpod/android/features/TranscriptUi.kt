package dev.distillpod.android.features

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.SegmentEntity
import dev.distillpod.android.data.TranscriptEntity
import dev.distillpod.android.data.TranscriptStage
import dev.distillpod.android.ui.theme.DistillSpacing
import dev.distillpod.android.ui.theme.DistillType
import dev.distillpod.android.ui.theme.Button
import dev.distillpod.android.ui.theme.OutlinedButton
import dev.distillpod.android.ui.theme.TextButton

/**
 * The transcript preparation state that [TranscriptTools] shows, shared with the episode page's
 * transcript row so both read the same row, live progress and actions.
 */
@Immutable internal class TranscriptToolState(
    val row: TranscriptEntity?, val effective: TranscriptEntity?, val running: Boolean, val duration: Long, val saved: Long,
    val worked: Long, val fraction: Float, val stage: TranscriptStage?, val statusText: String,
    val shortStatus: String, val chunk: String?, val busy: Boolean,
) {
    val canRead: Boolean get() = (effective?.processedMs ?: 0) > 0
    val canPause: Boolean get() = row?.state in listOf("processing", "queued")
    val needsModel: Boolean get() = row?.state == "waiting_model"
    val canRedoLocally: Boolean get() = effective?.source == "online" || row?.state in listOf("ready", "partial", "failed")
    /** "Prepare transcript" or "Resume transcript" when preparation can start, else null. */
    val prepareLabel: String? get() = when (row?.state) {
        "processing", "queued", "waiting_model", "ready" -> null
        null, "stale" -> "Prepare transcript"
        else -> "Resume transcript"
    }
    /** An indeterminate bar while a stage reports no measurable progress. */
    val indeterminate: Boolean get() = running && (stage !in listOf(TranscriptStage.TRANSCRIBING, TranscriptStage.QUEUED) ||
        stage == TranscriptStage.TRANSCRIBING && worked <= saved)
}

/** The transcript row's short status on the episode page (proposal 5.2): [TranscriptTools]' phase text, shortened. */
internal fun transcriptShortStatus(state: String?, source: String?, stage: TranscriptStage?, percent: Int, downloadPercent: Int?,
    preparesOnPlay: Boolean): String = when (stage) {
    TranscriptStage.ONLINE -> "Looking for an online transcript"
    TranscriptStage.PUBLISHER -> "Loading publisher transcript"
    TranscriptStage.DOWNLOADING -> downloadPercent?.let { "Downloading audio · $it%" } ?: "Downloading audio"
    TranscriptStage.CHECKING_MODEL -> "Checking transcription model"
    TranscriptStage.DECODING -> "Decoding audio"
    TranscriptStage.STARTING -> "Starting transcription"
    TranscriptStage.TRANSCRIBING -> "Transcribing · $percent%"
    TranscriptStage.SAVING -> "Saving transcript"
    else -> when (state) {
        "ready" -> when (source) { "publisher" -> "Ready · publisher"; "online" -> "Ready · online"; else -> "Ready · on this phone" }
        "queued" -> "Queued · waiting for power or network"
        "processing" -> "Preparing on this phone"
        "waiting_model" -> "Needs a transcription model"
        "paused" -> "Paused · progress saved"
        "partial" -> "Partial publisher transcript"
        "stale" -> "Audio changed · prepare again"
        "failed" -> "Couldn't finish · progress saved"
        else -> if (preparesOnPlay) "Prepared when you play" else "Not prepared yet"
    }
}

@Composable internal fun rememberTranscriptToolState(vm: AppViewModel, ep: EpisodeEntity): TranscriptToolState {
    val transcripts by vm.localTranscripts.collectAsStateWithLifecycle()
    val effectiveTranscripts by vm.transcripts.collectAsStateWithLifecycle()
    val effective = effectiveTranscripts.firstOrNull { it.episodeId == ep.id }
    val row = transcripts.firstOrNull { it.episodeId == ep.id }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val prefs by vm.graph.preferences.collectAsStateWithLifecycle()
    val progress by vm.graph.transcription.progress.collectAsStateWithLifecycle()
    val live = progress[ep.id]?.takeIf { it.revision == row?.revision }
    val running = row?.state in listOf("queued", "processing")
    val duration = live?.durationMs?.takeIf { it > 0 } ?: row?.durationMs ?: 0
    val saved = row?.processedMs ?: 0
    var highWater by remember(ep.id, row?.revision) { mutableLongStateOf(saved) }
    val reported = maxOf(saved, live?.workedMs ?: 0)
    val worked = if (running) maxOf(reported, highWater) else saved
    SideEffect { highWater = if (running) worked else saved }
    val fraction = if (duration > 0) (worked.toFloat() / duration).coerceIn(0f, if (row?.state == "ready") 1f else 0.999f) else 0f
    val stage = if (running) live?.stage else null
    val downloadPercent = if (ep.totalBytes > 0) (ep.downloadedBytes * 100 / ep.totalBytes).coerceIn(0, 100).toInt() else null
    val phaseText = when (stage) {
        TranscriptStage.ONLINE -> "Looking for an existing online transcript"
        TranscriptStage.PUBLISHER -> "Loading publisher transcript"
        TranscriptStage.DOWNLOADING -> downloadPercent?.let { "Downloading audio · $it%" } ?: "Downloading audio"
        TranscriptStage.CHECKING_MODEL -> "Checking transcription model"
        TranscriptStage.DECODING -> "Decoding audio"
        TranscriptStage.STARTING -> "Starting transcription"
        TranscriptStage.TRANSCRIBING -> "Transcribing · ${timestamp(worked)} / ${timestamp(duration)} · ${(fraction * 100).toInt()}%"
        TranscriptStage.SAVING -> "Saving transcript"
        else -> null
    }
    val statusText = phaseText ?: when (row?.state) {
        "ready" -> when (row.source) {
            "publisher" -> "Publisher transcript · segment timings"
            "online" -> "Online transcript · PodcastTranscript.ai · segment timings"
            else -> "On-device transcript · ${row.model} · segment timings"
        }
        "queued" -> "Queued · waiting for power, network or an Android processing slot"
        "processing" -> "Preparing on this phone · ${timestamp(row.processedMs)} completed"
        "waiting_model" -> if (prefs.transcribeOnPlayback) "Download a model to enable automatic transcription" else "Download the selected model in Settings to continue"
        "paused" -> "Paused · completed chunks are saved"
        "partial" -> "Publisher transcript has partial coverage"
        "stale" -> "Audio changed · prepare a new transcript"
        "failed" -> "Preparation could not finish · completed chunks are saved"
        else -> if (prefs.transcribeOnPlayback) "A transcript will be prepared when you play this episode. Audio stays on your phone." else "Prepare a transcript to browse text and capture insights. Audio stays on your phone."
    }
    val shortStatus = transcriptShortStatus(row?.state, row?.source, stage, (fraction * 100).toInt(), downloadPercent, prefs.transcribeOnPlayback)
    val chunk = if (running) "${timestamp(saved)} saved${if (live != null && live.chunkEndMs > live.chunkStartMs) " · current chunk ${timestamp(live.chunkStartMs)}–${timestamp(live.chunkEndMs)}" else ""}" else null
    return TranscriptToolState(row, effective, running, duration, saved, worked, fraction, stage, statusText, shortStatus, chunk,
        "transcript:${ep.id}" in busy)
}

/** The full transcript preparation card: status, progress and every preparation action. The player and insight pages use it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun TranscriptTools(vm: AppViewModel, ep: EpisodeEntity) {
    val state = rememberTranscriptToolState(vm, ep)
    val row = state.row
    val animated by animateFloatAsState(state.fraction, tween(650), label = "Transcript progress")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Transcript", style = MaterialTheme.typography.titleMedium)
        Text(state.statusText, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (row != null && row.state != "ready") {
            if (state.duration > 0) LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Transcript preparation progress" })
            if (state.indeterminate) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.chunk?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (state.effective?.source == "online") Text("Online timings may differ from your audio. Saved moments and ad skipping use a publisher or local transcript.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        row?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.canPause -> OutlinedButton(onClick = { vm.pauseTranscript(ep.id) }) { Text("Pause transcript") }
                state.needsModel -> Button(onClick = { vm.open("settings:transcription") }) { Text("Download a model") }
                else -> state.prepareLabel?.let { label -> Button(onClick = { vm.prepareTranscript(ep.id) }, enabled = !state.busy) { Text(label) } }
            }
            if (state.canRead) OutlinedButton(onClick = { vm.open("transcript:${ep.id}") }) { Text("Read transcript") }
        }
        if (state.canRedoLocally) TextButton(onClick = { vm.prepareTranscript(ep.id, true) }) { Text("Transcribe locally again") }
    }
}

/**
 * The episode page's transcript entry (proposal 5.2): status as supporting text, tap to read or start
 * preparing, and the less common preparation actions in a trailing menu.
 */
@Composable internal fun TranscriptListItem(vm: AppViewModel, ep: EpisodeEntity) {
    val state = rememberTranscriptToolState(vm, ep)
    val animated by animateFloatAsState(state.fraction, tween(650), label = "Transcript progress")
    val read = { vm.open(transcriptRoute(ep.id)) }
    val prepare = state.prepareLabel
    val reading = state.canRead || state.running || (prepare == null && !state.needsModel)
    val label = when { reading -> "Read transcript"; state.needsModel -> "Download a model"; else -> prepare!! }
    EpisodeToolRow(title = "Transcript", supporting = state.shortStatus, icon = Icons.Default.Subtitles, onClickLabel = label,
        onClick = { when { reading -> read(); state.needsModel -> vm.open("settings:transcription"); else -> vm.prepareTranscript(ep.id) } },
        enabled = reading || state.needsModel || !state.busy,
        modifier = Modifier.testTag("episode-transcript"),
        extra = {
            val row = state.row
            if (row != null && row.state != "ready" && (state.duration > 0 || state.indeterminate)) {
                val bar = Modifier.fillMaxWidth().padding(top = 6.dp).semantics { contentDescription = "Transcript preparation progress" }
                if (state.indeterminate || state.duration <= 0) LinearProgressIndicator(bar) else LinearProgressIndicator(progress = { animated }, modifier = bar)
            }
            row?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
        },
        trailing = {
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "Transcript options") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    if (state.canRead) DropdownMenuItem(text = { Text("Read transcript") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Article, null) },
                        onClick = { open = false; read() })
                    if (state.canPause) DropdownMenuItem(text = { Text("Pause transcript") }, leadingIcon = { Icon(Icons.Default.PauseCircleOutline, null) },
                        onClick = { open = false; vm.pauseTranscript(ep.id) })
                    if (state.needsModel) DropdownMenuItem(text = { Text("Download a model") }, leadingIcon = { Icon(Icons.Default.Download, null) },
                        onClick = { open = false; vm.open("settings:transcription") })
                    prepare?.let { DropdownMenuItem(text = { Text(it) }, enabled = !state.busy, leadingIcon = { Icon(Icons.Default.PlayCircleOutline, null) },
                        onClick = { open = false; vm.prepareTranscript(ep.id) }) }
                    if (state.canRedoLocally) DropdownMenuItem(text = { Text("Transcribe locally again") }, leadingIcon = { Icon(Icons.Default.Refresh, null) },
                        onClick = { open = false; vm.prepareTranscript(ep.id, true) })
                }
            }
        })
}

/** A transcript page route, optionally positioned at the passage containing [atMs] (#179). */
internal fun transcriptRoute(episodeId: String, atMs: Long? = null) = "transcript:$episodeId" + (atMs?.let { "@$it" } ?: "")
internal data class TranscriptTarget(val episodeId: String, val atMs: Long?)
internal fun transcriptTarget(route: String): TranscriptTarget {
    val rest = route.removePrefix("transcript:")
    val at = rest.substringAfterLast('@', "").toLongOrNull()
    return if (at != null) TranscriptTarget(rest.substringBeforeLast('@'), at) else TranscriptTarget(rest, null)
}

/** The passage starting at [atMs], else one containing it, else the last one before it; -1 without passages. */
internal fun transcriptFocusIndex(segments: List<SegmentEntity>, atMs: Long): Int =
    segments.indexOfFirst { it.startMs == atMs }.takeIf { it >= 0 }
        ?: segments.indexOfFirst { atMs >= it.startMs && atMs < it.endMs }.takeIf { it >= 0 }
        ?: segments.indexOfLast { it.startMs <= atMs }.takeIf { it >= 0 } ?: if (segments.isEmpty()) -1 else 0

/**
 * The transcript reader (proposal 5.3): paragraphs of serif text with a tappable timestamp gutter, the
 * current paragraph marked while this episode plays, saved moments underlined, follow-along scrolling,
 * and search with match navigation. Preparation options sit in the overflow once a transcript is ready.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TranscriptPage(vm: AppViewModel, ep: EpisodeEntity?, focusMs: Long? = null) {
    if (ep == null) return
    val transcripts by vm.transcripts.collectAsStateWithLifecycle()
    val row = transcripts.firstOrNull { it.episodeId == ep.id }
    var segments by remember(ep.id, row?.revision) { mutableStateOf<List<SegmentEntity>>(emptyList()) }
    LaunchedEffect(ep.id, row?.revision, row?.processedMs) { segments = row?.let { dev.distillpod.android.data.EpisodeTextResolver(vm.graph.db).segments(ep.id, it.revision) } ?: emptyList() }
    val paragraphs = remember(segments) { transcriptParagraphs(segments) }
    val tools = rememberTranscriptToolState(vm, ep)
    val captures by vm.captures.collectAsStateWithLifecycle()
    val moments = remember(captures, ep.id) { captures.filter { it.episodeId == ep.id }.map { Triple(it.id, it.startMs, it.endMs) } }
    var query by rememberSaveable(ep.id) { mutableStateOf("") }
    var searching by rememberSaveable(ep.id) { mutableStateOf(false) }
    var matchIndex by rememberSaveable(ep.id) { mutableIntStateOf(0) }
    var optionsSheet by rememberSaveable(ep.id) { mutableStateOf(false) }
    val matches = remember(paragraphs, query) { transcriptMatches(paragraphs, query) }
    LaunchedEffect(matches) { matchIndex = matchIndex.coerceIn(0, (matches.size - 1).coerceAtLeast(0)) }

    // Position ticks only move the current paragraph: read them through derived state.
    val player = vm.player.collectAsStateWithLifecycle()
    val playingThis by remember(ep.id) { derivedStateOf { player.value.episodeId == ep.id } }
    val playing by remember(ep.id) { derivedStateOf { player.value.episodeId == ep.id && player.value.playing } }
    val current by remember(ep.id, paragraphs) { derivedStateOf { if (player.value.episodeId == ep.id) currentParagraphIndex(paragraphs, player.value.positionMs) else -1 } }
    // The search result's paragraph, chosen from the whole transcript (#179).
    val focused = remember(paragraphs, segments, focusMs) { focusMs?.let { paragraphOfSegment(paragraphs, transcriptFocusIndex(segments, it)) } ?: -1 }

    val showTools = row?.state != "ready"
    val header = if (showTools) 1 else 0
    val list = rememberLazyListState()
    var following by remember(ep.id, focusMs) { mutableStateOf(focusMs == null) }
    // A manual scroll pauses follow-along; programmatic scrolls emit no drag interaction.
    LaunchedEffect(list) { list.interactionSource.interactions.collect { if (it is DragInteraction.Start) following = false } }
    // Opened from a search result or the player: bring its paragraph into view once, after the passages load.
    var positioned by remember(ep.id, focusMs) { mutableStateOf(false) }
    LaunchedEffect(paragraphs.isNotEmpty(), focused) {
        if (positioned || paragraphs.isEmpty()) return@LaunchedEffect
        val target = focused.takeIf { it >= 0 } ?: current
        if (target >= 0) list.scrollToItem(header + target)
        positioned = true
    }
    LaunchedEffect(current, following, playing, searching, positioned) {
        if (following && playing && !searching && current >= 0 && positioned) list.animateScrollToItem(header + current)
    }
    LaunchedEffect(matchIndex, matches, searching) {
        if (searching) matches.getOrNull(matchIndex)?.let { following = false; list.animateScrollToItem(header + it) }
    }

    if (optionsSheet) ModalBottomSheet(onDismissRequest = { optionsSheet = false }) {
        Column(Modifier.padding(horizontal = DistillSpacing.gutter).padding(bottom = DistillSpacing.section)) { TranscriptTools(vm, ep) }
    }

    Column(Modifier.fillMaxSize()) {
        if (searching) TranscriptSearchBar(query, { query = it; matchIndex = 0 }, matches.size, matchIndex,
            onPrevious = { if (matches.isNotEmpty()) matchIndex = (matchIndex - 1 + matches.size) % matches.size },
            onNext = { if (matches.isNotEmpty()) matchIndex = (matchIndex + 1) % matches.size },
            onClose = { searching = false; query = "" })
        else TranscriptToolbar(
            status = listOfNotNull(transcriptSourceLabel(tools.effective), paragraphs.size.takeIf { it > 0 }?.let { if (it == 1) "1 paragraph" else "$it paragraphs" }).joinToString(" · "),
            source = tools.statusText, canRedoLocally = tools.canRedoLocally && !showTools,
            onSearch = { searching = true }, onOptions = { optionsSheet = true }, onRedoLocally = { vm.prepareTranscript(ep.id, true) })
        Box(Modifier.weight(1f)) {
            val needle = if (searching) query.trim() else ""
            val accent = MaterialTheme.colorScheme.primary
            val momentColor = MaterialTheme.colorScheme.onPrimaryContainer
            // One gutter width for the whole transcript, wide enough for its longest timestamp at any text size.
            val long = (paragraphs.lastOrNull()?.startMs ?: 0) >= 3_600_000
            // Scale from the label's own size: Android's non-linear font scaling grows large sp values less.
            val label = with(LocalDensity.current) { MaterialTheme.typography.labelSmall.fontSize.toDp() }
            val gutter = label * (if (long) 4.4f else 3.2f) + DistillSpacing.gutter + DistillSpacing.xs
            LazyColumn(state = list, contentPadding = PaddingValues(start = DistillSpacing.xs, end = DistillSpacing.gutter, top = DistillSpacing.sm, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(DistillSpacing.sm), modifier = Modifier.fillMaxSize().testTag("transcript-passages")) {
                if (showTools) item(key = "tools") {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(start = DistillSpacing.item, bottom = DistillSpacing.sm)) {
                        Box(Modifier.padding(DistillSpacing.gutter)) { TranscriptTools(vm, ep) }
                    }
                }
                if (paragraphs.isEmpty()) item(key = "empty") {
                    Text("Your transcript will appear here", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = DistillSpacing.item, top = DistillSpacing.sm))
                }
                itemsIndexed(paragraphs, key = { _, p -> p.key }) { index, paragraph ->
                    val text = remember(paragraph, moments, needle, accent, momentColor) { paragraphText(paragraph, moments, needle, accent, momentColor) { vm.open("capture:$it") } }
                    TranscriptParagraphRow(paragraph, text, gutter, current = index == current, dimmed = current >= 0 && index != current,
                        focused = index == focused, currentMatch = searching && matches.getOrNull(matchIndex) == index,
                        momentIds = remember(paragraph, moments) { paragraph.segments.mapNotNull { momentForSegment(it, moments) }.distinct() },
                        onListen = { vm.play(ep.id, paragraph.startMs) }, onMoment = { vm.open("capture:$it") })
                }
            }
            if (playingThis && !following && current >= 0 && !searching) Surface(onClick = { following = true }, shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, shadowElevation = 6.dp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = DistillSpacing.gutter).heightIn(min = 48.dp)) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.VerticalAlignCenter, null, Modifier.size(18.dp))
                    Text("Follow along", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

internal fun transcriptSourceLabel(row: TranscriptEntity?): String? = when {
    row == null || row.processedMs <= 0 -> null
    row.source == "publisher" -> "Publisher transcript"
    row.source == "online" -> "Online transcript"
    else -> "On-device transcript"
}

/** Paragraph text: segments joined, saved moments underlined as links, search matches highlighted. */
private fun paragraphText(paragraph: TranscriptParagraph, moments: List<Triple<String, Long, Long>>, needle: String, accent: Color,
    momentColor: Color, onMoment: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    // No background here, so a search match inside a saved moment still shows its highlight.
    val momentStyle = TextLinkStyles(SpanStyle(color = momentColor, textDecoration = TextDecoration.Underline))
    paragraph.segments.forEachIndexed { index, segment ->
        if (index > 0) append(" ")
        val text = segment.text.trim()
        val moment = momentForSegment(segment, moments)
        if (moment == null) append(text)
        else withLink(LinkAnnotation.Clickable("moment:$moment", momentStyle) { onMoment(moment) }) { append(text) }
    }
    if (needle.isNotEmpty()) {
        val all = paragraph.text
        var at = all.indexOf(needle, ignoreCase = true)
        while (at >= 0) {
            addStyle(SpanStyle(background = accent.copy(alpha = .38f)), at, at + needle.length)
            at = all.indexOf(needle, at + needle.length, ignoreCase = true)
        }
    }
}

@Composable private fun TranscriptParagraphRow(paragraph: TranscriptParagraph, text: AnnotatedString, gutter: Dp, current: Boolean, dimmed: Boolean,
    focused: Boolean, currentMatch: Boolean, momentIds: List<String>, onListen: () -> Unit, onMoment: (String) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val time = timestamp(paragraph.startMs)
    val highlight = when { focused -> MaterialTheme.colorScheme.surfaceContainer; currentMatch -> MaterialTheme.colorScheme.surfaceContainerLow; else -> Color.Transparent }
    Row(Modifier.fillMaxWidth()
        .background(highlight, MaterialTheme.shapes.small)
        .drawBehind { if (current) drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
        .semantics {
            selected = current
            if (focused) stateDescription = "Search result"
            if (momentIds.isNotEmpty()) customActions = momentIds.mapIndexed { i, id ->
                CustomAccessibilityAction(if (momentIds.size == 1) "Open saved moment" else "Open saved moment ${i + 1}") { onMoment(id); true }
            }
        }
        .then(if (focused) Modifier.testTag("transcript-focus") else Modifier)) {
        Box(Modifier.width(gutter.coerceAtLeast(48.dp)).heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = "Listen") { onListen() }
            .semantics { contentDescription = "$time · Listen" }) {
            Text(time, Modifier.padding(start = DistillSpacing.item, top = 6.dp), style = MaterialTheme.typography.labelSmall,
                color = if (current) accent else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SelectionContainer(Modifier.weight(1f).padding(vertical = 2.dp)) {
            Text(text, style = DistillType.reading, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dimmed) .7f else 1f))
        }
    }
}

@Composable private fun TranscriptToolbar(status: String, source: String, canRedoLocally: Boolean, onSearch: () -> Unit, onOptions: () -> Unit,
    onRedoLocally: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = DistillSpacing.gutter, end = DistillSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(status, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search transcript") }
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "Transcript options") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(source, style = MaterialTheme.typography.bodySmall) }, enabled = false,
                    leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = {})
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Preparation options") }, leadingIcon = { Icon(Icons.Default.Tune, null) },
                    onClick = { open = false; onOptions() })
                if (canRedoLocally) DropdownMenuItem(text = { Text("Transcribe locally again") }, leadingIcon = { Icon(Icons.Default.Refresh, null) },
                    onClick = { open = false; onRedoLocally() })
            }
        }
    }
}

@Composable private fun TranscriptSearchBar(query: String, onQuery: (String) -> Unit, count: Int, index: Int, onPrevious: () -> Unit,
    onNext: () -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(start = DistillSpacing.xs, end = DistillSpacing.gutter)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close transcript search") }
            OutlinedTextField(value = query, onValueChange = { onQuery(it.take(200)) }, label = { Text("Search transcript") }, singleLine = true,
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, "Clear transcript search") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); keyboard?.hide() }),
                shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f).focusRequester(focus))
        }
        if (query.isNotBlank()) Row(Modifier.fillMaxWidth().padding(start = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (count == 0) "No matching passages" else "${index + 1} of $count", Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = onPrevious, enabled = count > 1) { Icon(Icons.Default.KeyboardArrowUp, "Previous match") }
            IconButton(onClick = onNext, enabled = count > 1) { Icon(Icons.Default.KeyboardArrowDown, "Next match") }
        }
    }
}
