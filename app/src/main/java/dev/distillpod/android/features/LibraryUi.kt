package dev.distillpod.android.features

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import dev.distillpod.android.ui.theme.DistillSpacing
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
import androidx.compose.ui.unit.Dp
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
import dev.distillpod.android.ui.theme.Button
import dev.distillpod.android.ui.theme.TextButton

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
 * repeated show name. Rows are flat (proposal 5.4); the list draws the dividers between them.
 */
@Composable internal fun EpisodeRow(ep: EpisodeEntity, podcast: PodcastEntity?, player: PlaybackState, transcriptReady: Boolean,
    captureCount: Int, showPodcast: Boolean, deletionBlock: String?, actions: EpisodeActions, modifier: Modifier = Modifier, retryPreparation: ((String) -> Unit)? = null) {
    val current = player.episodeId == ep.id
    val playing = current && player.playing
    val position = listeningPosition(ep, player)
    val duration = listeningDuration(ep, player)
    val inProgress = episodeInProgress(ep, player)
    val highlight = MaterialTheme.colorScheme.primary
    val plain = listOf(episodeDate(ep.publishedAt),
        if (position > 0 && !ep.completed) timeRemaining(duration, position) else duration.takeIf { it > 0 }?.let(::timestamp) ?: "")
        .filter(String::isNotBlank)
    // (shown, spoken, highlighted): the saved-moment count is an accent "✦ N" that TalkBack reads in words.
    val status = buildList {
        if (ep.completed) add(Triple("Played", "Played", false))
        if (ep.localFile != null) add(Triple("Offline", "Offline", true))
        if (transcriptReady) add(Triple("Transcript", "Transcript", false))
        if (captureCount > 0) add(Triple("✦ $captureCount", if (captureCount == 1) "1 saved moment" else "$captureCount saved moments", true))
        if (ep.downloadState in ACTIVE_DOWNLOAD) (if (ep.totalBytes > 0) "${(ep.downloadedBytes * 100 / ep.totalBytes).coerceIn(0, 100)}%" else "Queued").let { add(Triple(it, it, false)) }
    }
    val parts = plain.map { Triple(it, it, false) } + status
    val details = buildAnnotatedString {
        parts.forEachIndexed { index, (text, _, highlighted) ->
            if (index > 0) append(" · ")
            if (highlighted) withStyle(SpanStyle(color = highlight)) { append(text) } else append(text)
        }
    }
    val spoken = parts.joinToString(", ") { it.second }
    Column(modifier.fillMaxWidth().testTag("episode-row").clickable { actions.open(ep.id) }) {
        Row(Modifier.padding(start = DistillSpacing.gutter, end = DistillSpacing.xs, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            PodcastCover(ep.imageUrl ?: podcast?.imageUrl, Modifier.size(56.dp), MaterialTheme.shapes.small)
            Column(Modifier.weight(1f).padding(start = DistillSpacing.item, end = DistillSpacing.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (showPodcast) Text(podcast?.title ?: "Podcast", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ep.title, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (details.isNotEmpty()) Text(details, Modifier.semantics { contentDescription = spoken }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // A 36 dp circle on s3 inside a 48 dp touch target.
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { if (playing) actions.toggle() else actions.play(ep.id) },
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "${if (playing) "Pause" else "Play"} ${ep.title}",
                        Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            EpisodeMenu(ep, deletionBlock, actions, retryPreparation)
        }
        // Flush with the row's bottom edge, like the mini-player, rather than another padded row.
        // It also separates this row from the next, which then draws no divider.
        if (inProgress) LinearProgressIndicator(progress = { (position.toFloat() / duration).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp), trackColor = MaterialTheme.colorScheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
    }
}

/** Whether a row shows its listening progress line. */
internal fun episodeInProgress(ep: EpisodeEntity, player: PlaybackState): Boolean =
    listeningPosition(ep, player) > 0 && listeningDuration(ep, player) > 0 && !ep.completed

/** The divider between flat episode rows, inset by default to line up with the text after the artwork. */
@Composable internal fun EpisodeRowDivider(inset: Dp = DistillSpacing.gutter + 56.dp + DistillSpacing.item) =
    HorizontalDivider(Modifier.padding(start = inset), color = MaterialTheme.colorScheme.outlineVariant)

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
        UnsubscribeConfirmDialog(show.title, onConfirm = { confirm = null; onUnsubscribe(show.id) }, onDismiss = { confirm = null })
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

/** "1 hr 5 min" or "42 min"; blank without a known duration. */
internal fun episodeLengthLabel(durationMs: Long): String {
    if (durationMs <= 0) return ""
    val minutes = ((durationMs + 30_000) / 60_000).coerceAtLeast(1)
    return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
}

/**
 * The episode hero's backdrop colour. Kept in one place so it can switch to the player's artwork tint
 * (proposal 3.3) once that exists; until then it is the accent container.
 */
@Composable internal fun episodeHeroTint(@Suppress("UNUSED_PARAMETER") artworkUrl: String?): Color = MaterialTheme.colorScheme.primaryContainer

/**
 * Episode page (proposal 5.2): a tinted hero with artwork, show link, date and title; one filled Play or
 * Resume button; an icon row for download, queue and played; the transcript and AI [tools] as a grouped
 * list; then the source's description, collapsed when long.
 */
@Composable internal fun EpisodeDetail(ep: EpisodeEntity, podcast: PodcastEntity?, metadata: EpisodeMetadataText, resumeAt: Long,
    actions: EpisodeActions, onOpenShow: (String) -> Unit, onRetryDetails: () -> Unit, modifier: Modifier = Modifier,
    tools: @Composable () -> Unit) {
    val artwork = ep.imageUrl ?: podcast?.imageUrl
    Column(Modifier.fillMaxSize().then(modifier)) {
        EpisodeHero(ep, podcast, artwork, metadata, onOpenShow)
        Column(Modifier.fillMaxWidth().padding(horizontal = DistillSpacing.gutter), verticalArrangement = Arrangement.spacedBy(DistillSpacing.gutter)) {
            Button(onClick = { actions.play(ep.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(DistillSpacing.sm))
                Text(if (resumeAt > 0 && !ep.completed) "Resume from ${timestamp(resumeAt)}" else "Play", style = MaterialTheme.typography.labelLarge)
            }
            Row(Modifier.fillMaxWidth().testTag("episode-actions"), verticalAlignment = Alignment.Top) {
                EpisodeDownloadAction(ep, { actions.download(ep.id) }, { actions.pauseDownload(ep.id) })
                EpisodeIconAction("Play next", Icons.AutoMirrored.Filled.PlaylistPlay, { actions.enqueue(ep.id, true) })
                EpisodeIconAction("Add to queue", Icons.AutoMirrored.Filled.PlaylistAdd, { actions.enqueue(ep.id, false) })
                EpisodeIconAction(if (ep.completed) "Mark unplayed" else "Mark played", if (ep.completed) Icons.Default.RemoveDone else Icons.Default.CheckCircleOutline,
                    { actions.markPlayed(ep.id, !ep.completed) })
            }
            if (ep.downloadState in ACTIVE_DOWNLOAD) Text("${fileSize(ep.downloadedBytes)}${if (ep.totalBytes > 0) " of ${fileSize(ep.totalBytes)}" else ""} · Downloads follow your network settings.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ep.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Box(Modifier.padding(top = DistillSpacing.sm)) { tools() }
            Column(Modifier.padding(top = DistillSpacing.sm), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("About", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                metadata.description?.let { EpisodeDescription(it) }
                metadata.note?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
                if (metadata.retry) TextButton(onClick = onRetryDetails) { Text("Retry") }
            }
            Spacer(Modifier.height(DistillSpacing.section))
        }
    }
}

@Composable private fun EpisodeHero(ep: EpisodeEntity, podcast: PodcastEntity?, artwork: String?, metadata: EpisodeMetadataText, onOpenShow: (String) -> Unit) {
    val tint = episodeHeroTint(artwork)
    Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(0f to tint.copy(alpha = .85f), 1f to Color.Transparent))) {
        Column(Modifier.fillMaxWidth().padding(start = DistillSpacing.gutter, end = DistillSpacing.gutter, top = DistillSpacing.section, bottom = DistillSpacing.gutter),
            verticalArrangement = Arrangement.spacedBy(DistillSpacing.gutter)) {
            Row(horizontalArrangement = Arrangement.spacedBy(DistillSpacing.gutter), verticalAlignment = Alignment.CenterVertically) {
                PodcastCover(artwork, Modifier.size(96.dp))
                Column(Modifier.weight(1f)) {
                    EpisodeHeroShowLink(podcast, onOpenShow)
                    Text(listOf(metadata.date, episodeLengthLabel(ep.durationMs)).filter(String::isNotBlank).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(ep.title, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** The show as an accent link with a chevron; TalkBack reads "View show: <title>" like the player's link. */
@Composable private fun EpisodeHeroShowLink(podcast: PodcastEntity?, onOpen: (String) -> Unit) {
    if (podcast == null) {
        Text("Show unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        return
    }
    Box(Modifier.heightIn(min = 48.dp).clip(MaterialTheme.shapes.extraSmall)
        .clickable(role = Role.Button) { onOpen("podcast:${podcast.id}") }
        .semantics { contentDescription = "View show: ${podcast.title}" }, contentAlignment = Alignment.CenterStart) {
        Text("${podcast.title} ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A peer action (proposal section 4): a 40 dp tonal circle and a short label, the whole column a 48 dp+ target. */
@Composable private fun RowScope.EpisodeIconAction(label: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true,
    description: String? = null, state: String? = null, ring: (@Composable () -> Unit)? = null) {
    Column(Modifier.weight(1f).heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { description?.let { contentDescription = it }; state?.let { stateDescription = it } }
        .padding(vertical = DistillSpacing.xs), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape))
            ring?.invoke()
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable private fun RowScope.EpisodeDownloadAction(ep: EpisodeEntity, onDownload: () -> Unit, onPause: () -> Unit) {
    when {
        ep.downloadState in ACTIVE_DOWNLOAD -> {
            val percent = if (ep.totalBytes > 0) (ep.downloadedBytes * 100 / ep.totalBytes).coerceIn(0, 100).toInt() else null
            EpisodeIconAction(percent?.let { "$it%" } ?: "Queued", Icons.Default.Pause, onPause, description = "Pause download",
                state = percent?.let { "$it% downloaded" } ?: "Download queued") {
                val ring = Modifier.fillMaxSize()
                if (percent == null) CircularProgressIndicator(ring, strokeWidth = 2.5.dp)
                else CircularProgressIndicator(progress = { percent / 100f }, modifier = ring, strokeWidth = 2.5.dp,
                    trackColor = Color.Transparent)
            }
        }
        ep.localFile != null -> EpisodeIconAction("Offline", Icons.Default.DownloadDone, {}, enabled = false, description = "Downloaded", state = "Saved on this phone")
        ep.downloadState in listOf("paused", "failed") -> EpisodeIconAction("Resume", Icons.Default.Download, onDownload, description = "Resume download")
        else -> EpisodeIconAction("Download", Icons.Default.Download, onDownload)
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
        Text(if (expanded) "Less" else "More")
    }
}
