package dev.distillpod.android.features

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.playback.PlaybackState

internal fun timeRemaining(durationMs: Long, positionMs: Long): String {
    if (durationMs <= 0) return "Duration unavailable"
    val minutes = ((durationMs - positionMs).coerceAtLeast(0) + 59_999) / 60_000
    return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min left" else "$minutes min left"
}

/**
 * The player's duration is authoritative for the current episode: feeds with dynamic ad
 * insertion can declare a different length. Other episodes use the stored duration.
 */
internal fun listeningDuration(ep: EpisodeEntity, player: PlaybackState): Long =
    if (player.episodeId == ep.id && player.durationMs > 0) player.durationMs else ep.durationMs

internal fun listeningPosition(ep: EpisodeEntity, player: PlaybackState): Long =
    if (player.episodeId == ep.id) player.positionMs else ep.positionMs

/** Finished episodes read "Played"; replaying the current one shows its remaining time again. */
internal fun queueTimeLabel(ep: EpisodeEntity, player: PlaybackState): String {
    val duration = listeningDuration(ep, player)
    val position = listeningPosition(ep, player)
    val atEnd = duration > 0 && position >= duration
    return if (ep.completed && (player.episodeId != ep.id || atEnd)) "Played" else timeRemaining(duration, position)
}

@Composable internal fun PodcastCover(url: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(14.dp)) {
    Surface(modifier.clip(shape), color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(contentAlignment = Alignment.Center) {
            // Remains visible while artwork loads or when its URL fails.
            Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f), modifier = Modifier.fillMaxSize(.45f))
            if (!url.isNullOrBlank()) AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable internal fun SectionHeading(title: String, detail: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable internal fun StatusLabel(text: String, highlighted: Boolean = false) {
    Surface(color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp)) {
        Text(text, Modifier.padding(horizontal = 7.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun ResumeListeningCard(ep: EpisodeEntity, podcast: PodcastEntity?, positionMs: Long, durationMs: Long,
    playing: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading("Continue listening")
        Card(onClick = onOpen, shape = RoundedCornerShape(24.dp), modifier = Modifier.testTag("resume-card"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .16f))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PodcastCover(ep.imageUrl ?: podcast?.imageUrl, Modifier.size(56.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(podcast?.title ?: "Podcast", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(ep.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    FilledIconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause listening" else "Resume listening", Modifier.size(28.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(timeRemaining(durationMs, positionMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (durationMs > 0) LinearProgressIndicator(progress = { (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) }, modifier = Modifier.weight(1f).height(4.dp))
                }
            }
        }
    }
}
