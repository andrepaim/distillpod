package dev.distillpod.android.features

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.distillpod.android.data.DistillationEntity
import org.json.JSONArray

/** Where [ms] falls on a track of [durationMs], or null when it is outside it or the duration is unknown. */
internal fun trackFraction(ms: Long, durationMs: Long): Float? =
    if (durationMs <= 0 || ms !in 0..durationMs) null else ms.toFloat() / durationMs

/** Chapter start times after the beginning, from an analysis' chapters JSON; empty when it cannot be read. */
internal fun chapterStarts(chaptersJson: String): List<Long> = runCatching {
    val chapters = JSONArray(chaptersJson)
    (0 until chapters.length()).map { chapters.getJSONObject(it).optLong("startMs", -1) }
}.getOrDefault(emptyList()).filter { it > 0 }.distinct().sorted()

/** Saved moments of [episodeId], marked where their replay starts. */
internal fun momentMarks(captures: List<DistillationEntity>, episodeId: String): List<Long> =
    captures.filter { it.episodeId == episodeId }.map { it.startMs }.distinct().sorted()

/**
 * The Now Playing position slider. The track is neutral, with 2 dp gaps at chapter starts and accent
 * dots at saved moments. Only this composable reads the live position, so playback ticks recompose
 * the scrubber and its time labels and nothing else on the player (#141).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PlayerScrubber(
    positionMs: () -> Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    moments: List<Long> = emptyList(),
    chapters: List<Long> = emptyList(),
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val max = durationMs.coerceAtLeast(1).toFloat()
    val displayed = dragging?.toLong() ?: positionMs()
    val fill = MaterialTheme.colorScheme.onSurface
    val rest = MaterialTheme.colorScheme.onSurface.copy(alpha = .26f)
    val accent = MaterialTheme.colorScheme.primary
    val halo = MaterialTheme.colorScheme.background
    Column(modifier.fillMaxWidth()) {
        Slider(
            value = dragging ?: displayed.toFloat().coerceIn(0f, max),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { onSeek(it.toLong()) }; dragging = null },
            valueRange = 0f..max,
            enabled = durationMs > 0,
            modifier = Modifier.fillMaxWidth().testTag("player-scrubber").semantics {
                contentDescription = "Episode playback position"
                stateDescription = "${timestamp(displayed)} of ${timestamp(durationMs)}"
            },
            thumb = { Box(Modifier.size(16.dp).background(fill, CircleShape)) },
            track = { state ->
                val span = state.valueRange.endInclusive - state.valueRange.start
                val progress = if (span > 0) ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f) else 0f
                Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                    val height = 4.dp.toPx()
                    val gap = 2.dp.toPx()
                    val y = size.height / 2
                    val width = size.width
                    val radius = CornerRadius(height / 2)
                    val cuts = chapters.mapNotNull { trackFraction(it, durationMs)?.times(width) }
                    (listOf(0f) + cuts + width).zipWithNext().forEach { (from, to) ->
                        val start = if (from == 0f) 0f else from + gap / 2
                        val end = if (to == width) width else to - gap / 2
                        if (end <= start) return@forEach
                        drawRoundRect(rest, Offset(start, y - height / 2), Size(end - start, height), radius)
                        val filled = minOf(end, progress * width)
                        if (filled > start) drawRoundRect(fill, Offset(start, y - height / 2), Size(filled - start, height), radius)
                    }
                    moments.forEach { mark ->
                        val x = trackFraction(mark, durationMs)?.times(width) ?: return@forEach
                        drawCircle(halo, 5.dp.toPx(), Offset(x, y))
                        drawCircle(accent, 3.5.dp.toPx(), Offset(x, y))
                    }
                }
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(timestamp(displayed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(timeRemaining(durationMs, displayed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
