package dev.distillpod.android.features

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.ui.theme.Button

/** Tabs of the player's peek sheet, in display order. */
internal enum class PlayerSheetTab(val label: String) { UP_NEXT("Up next"), TRANSCRIPT("Transcript"), INSIGHTS("Insights") }

/** The muted show name under the player title; it opens the show without touching playback. */
@Composable internal fun PlayerShowLink(podcast: PodcastEntity?, onOpen: (String) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (podcast == null) {
        Text("Show unavailable", color = muted, style = MaterialTheme.typography.bodyMedium)
        return
    }
    Row(Modifier.heightIn(min = 48.dp).clickable(onClickLabel = "View show") { onOpen("podcast:${podcast.id}") }
        .semantics(mergeDescendants = true) { contentDescription = "View show: ${podcast.title}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(podcast.title, color = muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
    }
}

/** Speed pill, −10, Play/Pause, +30 and the sleep timer moon. */
@Composable internal fun PlayerTransport(playing: Boolean, onToggle: () -> Unit, onSkip: (Long) -> Unit,
    speed: @Composable () -> Unit, sleep: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.widthIn(min = 64.dp), contentAlignment = Alignment.CenterStart) { speed() }
        IconButton(onClick = { onSkip(-10_000) }, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Default.Replay10, "Back 10 seconds", Modifier.size(30.dp))
        }
        FilledIconButton(onClick = onToggle, shape = CircleShape, modifier = Modifier.size(66.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface)) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(36.dp))
        }
        IconButton(onClick = { onSkip(30_000) }, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Default.Forward30, "Forward 30 seconds", Modifier.size(30.dp))
        }
        Box(Modifier.widthIn(min = 64.dp), contentAlignment = Alignment.CenterEnd) { sleep() }
    }
}

/**
 * Distill (the screen's one filled button), Ask when voice is enabled, and Transcript. The visible labels
 * are short; TalkBack keeps the full action names.
 */
@Composable internal fun PlayerActionDock(canDistill: Boolean, onDistill: () -> Unit, voiceEnabled: Boolean, voiceActive: Boolean,
    onAsk: () -> Unit, onTranscript: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onDistill, enabled = canDistill, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.weight(if (voiceEnabled) 1.4f else 1.8f).fillMaxHeight().heightIn(min = 56.dp).testTag("player-distill")
                .semantics { contentDescription = "Distill last 60 seconds" }) {
            Icon(Icons.Default.AutoAwesome, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Text("Distill", style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
        if (voiceEnabled) DockAction(Icons.Default.Mic, "Ask", "Ask about this", onAsk, enabled = !voiceActive)
        DockAction(Icons.AutoMirrored.Filled.Article, "Transcript", "Open transcript at current position", onTranscript)
    }
}

@Composable private fun RowScope.DockAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, description: String,
    onClick: () -> Unit, enabled: Boolean = true) {
    FilledTonalButton(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 56.dp).semantics { contentDescription = description }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The bottom handle that opens the peek sheet; a tap or an upward swipe opens it. */
@Composable internal fun PlayerSheetHandle(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val latest by rememberUpdatedState(onOpen)
    Surface(onClick = onOpen, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().pointerInput(Unit) { detectVerticalDragGestures { _, dy -> if (dy < -12f) latest() } }
            .semantics { contentDescription = "Up next, transcript and insights" }) {
        Column(Modifier.padding(top = 6.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(width = 32.dp, height = 4.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(PlayerSheetTab.entries.joinToString(" · ") { it.label }, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.KeyboardArrowUp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * The player's peek sheet: Up next (the queue), Transcript (preparation, reading and download) and
 * Insights (summary, chapters, Ask episode and ad skipping). It reuses the screens' own tools.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PlayerPeekSheet(vm: AppViewModel, ep: EpisodeEntity, initial: PlayerSheetTab, onDismiss: () -> Unit) {
    var tab by rememberSaveable(initial) { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 360.dp)) {
            SecondaryTabRow(selectedTabIndex = tab.ordinal, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                PlayerSheetTab.entries.forEach { entry ->
                    Tab(selected = tab == entry, onClick = { tab = entry }, text = { Text(entry.label, maxLines = 1) })
                }
            }
            when (tab) {
                PlayerSheetTab.UP_NEXT -> Box(Modifier.fillMaxWidth().heightIn(max = 560.dp)) { PlayerUpNext(vm) }
                PlayerSheetTab.TRANSCRIPT -> SheetTools {
                    TranscriptTools(vm, ep)
                    DownloadAction(vm, ep)
                }
                PlayerSheetTab.INSIGHTS -> SheetTools {
                    EpisodeUnderstandingTools(vm, ep)
                    PlayerAdControls(vm, ep)
                }
            }
        }
    }
}

@Composable private fun SheetTools(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)
        .padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

/** The queue, in the sheet. The live player state is read only here, while this tab is open (#141). */
@Composable private fun PlayerUpNext(vm: AppViewModel) {
    val player by vm.player.collectAsStateWithLifecycle()
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val podcasts by vm.podcasts.collectAsStateWithLifecycle()
    QueueContent(episodes, podcasts, player, { vm.root("library") }, { vm.open("episode:$it") },
        { vm.play(it) }, vm::toggle, vm::moveQueue, vm::clearQueue, vm::removeQueue)
}

/** The player's top bar overflow: episode details, the show, the queue and a fresh local transcript. */
@Composable internal fun PlayerOverflowMenu(vm: AppViewModel, ep: EpisodeEntity, podcast: PodcastEntity?) {
    var expanded by remember { mutableStateOf(false) }
    val local by vm.localTranscripts.collectAsStateWithLifecycle()
    val effective by vm.transcripts.collectAsStateWithLifecycle()
    // TranscriptToolState.canRedoLocally, without collecting live transcription progress in the top bar.
    val canTranscribeAgain = effective.firstOrNull { it.episodeId == ep.id }?.source == "online" ||
        local.firstOrNull { it.episodeId == ep.id }?.state in listOf("ready", "partial", "failed")
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "More player actions") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Episode details") }, leadingIcon = { Icon(Icons.Default.Info, null) },
                onClick = { expanded = false; vm.open("episode:${ep.id}") })
            if (podcast != null) DropdownMenuItem(text = { Text("Go to show") }, leadingIcon = { Icon(Icons.Default.Podcasts, null) },
                onClick = { expanded = false; vm.open("podcast:${podcast.id}") })
            DropdownMenuItem(text = { Text("Open queue") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                onClick = { expanded = false; vm.root("queue") })
            if (canTranscribeAgain) DropdownMenuItem(text = { Text("Transcribe locally again") }, leadingIcon = { Icon(Icons.Default.GraphicEq, null) },
                onClick = { expanded = false; vm.prepareTranscript(ep.id, true) })
        }
    }
}
