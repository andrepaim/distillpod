package dev.distillpod.android.features

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.data.ShowSummary
import dev.distillpod.android.data.feedErrorText
import dev.distillpod.android.playback.PlaybackState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.ZoneId
import java.util.Locale

/** Episode actions shared by Library rows and the episode page; ids identify the episode. */
internal class EpisodeActions(
    val open: (String) -> Unit = {},
    val play: (String) -> Unit = {},
    val toggle: () -> Unit = {},
    val enqueue: (String, Boolean) -> Unit = { _, _ -> },
    val markPlayed: (String, Boolean) -> Unit = { _, _ -> },
    val download: (String) -> Unit = {},
    val pauseDownload: (String) -> Unit = {},
    val deleteDownload: (String) -> Unit = {},
)

internal fun AppViewModel.episodeActions(openPlayer: Boolean = false) = EpisodeActions(
    open = { open("episode:$it") }, play = { play(it); if (openPlayer) open("player") }, toggle = ::toggle,
    enqueue = ::enqueue, markPlayed = ::markCompleted, download = ::download, pauseDownload = ::pauseDownload,
    deleteDownload = ::deleteDownload)

/** Whether a feed refresh runs, without recomposing the Library for every other busy action. */
internal fun AppViewModel.feedRefreshing() = busy.map { "refresh" in it }.distinctUntilChanged()

/** "Distill last 60 seconds" needs some audio before the current position; at 0:00 there is none. */
internal fun canCaptureMoment(player: PlaybackState): Boolean = player.episodeId != null && player.positionMs >= 1_000

/** Show cards use the episode rows' date format: the year only outside the current year. */
internal fun latestEpisodeLabel(row: ShowSummary, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()): String = when {
    row.episodeCount == 0 -> "No episodes yet"
    row.latestPublishedAt <= 0 -> "Episode date unavailable"
    else -> "Latest: " + episodeDate(row.latestPublishedAt, now, zone, locale)
}

private val ACTIVE_DOWNLOAD = listOf("queued", "downloading")

/**
 * One compact Library or show-page row: artwork, text, play and a menu with every secondary action, so
 * nothing needs a second, mostly empty row (#146). Status joins the date line; a show page omits the
 * repeated show name.
 */
@Composable internal fun EpisodeRow(ep: EpisodeEntity, podcast: PodcastEntity?, player: PlaybackState, transcriptReady: Boolean,
    captureCount: Int, showPodcast: Boolean, deletionBlock: String?, actions: EpisodeActions, modifier: Modifier = Modifier, retryPreparation: ((String) -> Unit)? = null) {
    val current = player.episodeId == ep.id
    val playing = current && player.playing
    val position = listeningPosition(ep, player)
    val duration = listeningDuration(ep, player)
    val inProgress = position > 0 && duration > 0 && !ep.completed
    val highlight = MaterialTheme.colorScheme.primary
    val details = buildAnnotatedString {
        val plain = listOf(episodeDate(ep.publishedAt),
            if (position > 0 && !ep.completed) timeRemaining(duration, position) else duration.takeIf { it > 0 }?.let(::timestamp) ?: "")
        val status = buildList {
            if (ep.completed) add("Played" to false)
            if (ep.localFile != null) add("Offline" to true)
            if (transcriptReady) add("Transcript" to false)
            if (captureCount > 0) add("$captureCount saved" to true)
            if (ep.downloadState in ACTIVE_DOWNLOAD) add((if (ep.totalBytes > 0) "${(ep.downloadedBytes * 100 / ep.totalBytes).coerceIn(0, 100)}%" else "Queued") to false)
        }
        (plain.filter(String::isNotBlank).map { it to false } + status).forEachIndexed { index, (text, highlighted) ->
            if (index > 0) append(" · ")
            if (highlighted) withStyle(SpanStyle(color = highlight)) { append(text) } else append(text)
        }
    }
    Card(onClick = { actions.open(ep.id) }, shape = RoundedCornerShape(18.dp), modifier = modifier.fillMaxWidth().testTag("episode-row"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            PodcastCover(ep.imageUrl ?: podcast?.imageUrl, Modifier.size(56.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (showPodcast) Text(podcast?.title ?: "Podcast", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ep.title, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (details.isNotEmpty()) Text(details, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalIconButton(onClick = { if (playing) actions.toggle() else actions.play(ep.id) }) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "${if (playing) "Pause" else "Play"} ${ep.title}")
            }
            EpisodeMenu(ep, deletionBlock, actions, retryPreparation)
        }
        // Flush with the card's bottom edge, like the mini-player, rather than another padded row.
        if (inProgress) LinearProgressIndicator(progress = { (position.toFloat() / duration).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(3.dp))
    }
}

@Composable private fun EpisodeMenu(ep: EpisodeEntity, blocked: String?, actions: EpisodeActions, retryPreparation: ((String) -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More actions for ${ep.title}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (retryPreparation != null) DropdownMenuItem(text = { Text("Retry preparation") },
                onClick = { retryPreparation(ep.id); open = false })
            DropdownMenuItem(text = { Text("Play next") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) },
                onClick = { actions.enqueue(ep.id, true); open = false })
            DropdownMenuItem(text = { Text("Add to queue") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                onClick = { actions.enqueue(ep.id, false); open = false })
            when {
                ep.downloadState in ACTIVE_DOWNLOAD -> DropdownMenuItem(text = { Text("Pause download") },
                    leadingIcon = { Icon(Icons.Default.PauseCircleOutline, null) }, onClick = { actions.pauseDownload(ep.id); open = false })
                ep.localFile == null -> DropdownMenuItem(text = { Text(if (ep.downloadState in listOf("paused", "failed")) "Resume download" else "Download") },
                    leadingIcon = { Icon(Icons.Default.Download, null) }, onClick = { actions.download(ep.id); open = false })
            }
            DropdownMenuItem(text = { Text(if (ep.completed) "Mark unplayed" else "Mark played") },
                leadingIcon = { Icon(if (ep.completed) Icons.Default.RemoveDone else Icons.Default.Done, null) },
                onClick = { actions.markPlayed(ep.id, !ep.completed); open = false })
            if (ep.localFile != null) {
                DropdownMenuItem(text = { Text("Delete download") }, enabled = blocked == null,
                    leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { actions.deleteDownload(ep.id); open = false })
                blocked?.let { DropdownMenuItem(text = { Text(it) }, enabled = false, onClick = {}) }
            }
        }
    }
}

@Composable internal fun DownloadAction(vm: AppViewModel, ep: EpisodeEntity) =
    EpisodeDownloadButton(ep, { vm.download(ep.id) }, { vm.pauseDownload(ep.id) })

@Composable internal fun EpisodeDownloadButton(ep: EpisodeEntity, onDownload: () -> Unit, onPause: () -> Unit) {
    if (ep.downloadState in ACTIVE_DOWNLOAD) TextButton(onClick = onPause) {
        Text(if (ep.totalBytes > 0) "${(ep.downloadedBytes * 100 / ep.totalBytes).coerceIn(0, 100)}% · Pause" else "Queued · Pause")
    } else if (ep.localFile == null) TextButton(onClick = onDownload) { Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Text(if (ep.downloadState in listOf("paused", "failed")) " Resume" else " Download") }
}

/**
 * Followed shows whose last refresh failed, each with its reason and a way out (#146). On a show page,
 * only that show's own error appears.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun FeedProblems(problems: List<PodcastEntity>, showPage: Boolean, refreshing: Boolean, onRetry: (String) -> Unit,
    onOpen: (String) -> Unit, onUnsubscribe: (String) -> Unit, now: Long = System.currentTimeMillis()) {
    if (problems.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<PodcastEntity?>(null) }
    confirm?.let { show ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text("Unsubscribe from ${show.title}?") },
            text = { Text("It stops refreshing on your devices. Downloaded episodes, listening progress and saved moments are kept.") },
            confirmButton = { TextButton(onClick = { confirm = null; onUnsubscribe(show.id) }) { Text("Unsubscribe") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
    val tint = MaterialTheme.colorScheme.tertiary
    Surface(color = tint.copy(alpha = .07f), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, tint.copy(alpha = .28f)),
        modifier = Modifier.fillMaxWidth().testTag("feed-problems")) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.CloudOff, null, tint = tint, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(when {
                        showPage -> "This show couldn't refresh"
                        problems.size == 1 -> "1 show couldn't refresh"
                        else -> "${problems.size} shows couldn't refresh"
                    }, style = MaterialTheme.typography.titleSmall, color = tint)
                    Text("Saved episodes are still available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!showPage && problems.size > 1) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Show details") }
            }
            if (showPage || problems.size == 1 || expanded) problems.forEach { show ->
                Column(Modifier.padding(start = 30.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (!showPage) Text(show.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    show.error?.let { Text(feedErrorText(it), style = MaterialTheme.typography.bodySmall) }
                    if (show.refreshedAt > 0) Text("Last updated ${episodeDate(show.refreshedAt, now)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(Modifier.offset(x = (-12).dp)) {
                        TextButton(onClick = { onRetry(show.id) }, enabled = !refreshing,
                            modifier = Modifier.semantics { contentDescription = "Retry ${show.title}" }) { Text("Retry") }
                        if (!showPage) {
                            TextButton(onClick = { onOpen(show.id) }, modifier = Modifier.semantics { contentDescription = "Open ${show.title}" }) { Text("Open show") }
                            TextButton(onClick = { confirm = show }, modifier = Modifier.semantics { contentDescription = "Unsubscribe from ${show.title}" }) { Text("Unsubscribe") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Episode page: playback, queue and played actions directly under the title, then the source's
 * description (collapsed when long), then transcript and AI [tools] (#146).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun EpisodeDetail(ep: EpisodeEntity, podcast: PodcastEntity?, metadata: EpisodeMetadataText, resumeAt: Long,
    actions: EpisodeActions, onOpenShow: (String) -> Unit, onRetryDetails: () -> Unit, modifier: Modifier = Modifier,
    tools: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().then(modifier).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PodcastCover(ep.imageUrl ?: podcast?.imageUrl, Modifier.size(96.dp))
            Column(Modifier.weight(1f)) { EpisodeShowLink(podcast, onOpenShow); Text(metadata.date, style = MaterialTheme.typography.bodySmall) }
        }
        Text(ep.title, style = MaterialTheme.typography.headlineSmall)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { actions.play(ep.id) }) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text(if (resumeAt > 0 && !ep.completed) "Resume ${timestamp(resumeAt)}" else "Play episode")
                }
                EpisodeDownloadButton(ep, { actions.download(ep.id) }, { actions.pauseDownload(ep.id) })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { actions.enqueue(ep.id, true) }, label = { Text("Play next") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, Modifier.size(AssistChipDefaults.IconSize)) })
                AssistChip(onClick = { actions.enqueue(ep.id, false) }, label = { Text("Add to queue") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, Modifier.size(AssistChipDefaults.IconSize)) })
                AssistChip(onClick = { actions.markPlayed(ep.id, !ep.completed) }, label = { Text(if (ep.completed) "Mark unplayed" else "Mark played") },
                    leadingIcon = { Icon(if (ep.completed) Icons.Default.RemoveDone else Icons.Default.Done, null, Modifier.size(AssistChipDefaults.IconSize)) })
            }
        }
        if (ep.downloadState in ACTIVE_DOWNLOAD) Text("${fileSize(ep.downloadedBytes)}${if (ep.totalBytes > 0) " of ${fileSize(ep.totalBytes)}" else ""} · Downloads follow your network settings.", style = MaterialTheme.typography.bodySmall)
        ep.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Description", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            metadata.description?.let { EpisodeDescription(it) }
            metadata.note?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
            if (metadata.retry) TextButton(onClick = onRetryDetails) { Text("Retry") }
        }
        tools()
    }
}

@Composable private fun EpisodeDescription(text: String) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    SelectionContainer {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge,
            maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow })
    }
    if (expanded || overflows) TextButton(onClick = { expanded = !expanded }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
        Text(if (expanded) "Show less" else "Show more")
    }
}
