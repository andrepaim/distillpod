package dev.distillpod.android.features

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.distillpod.core.SLEEP_TIMER_EXTENSION_MS
import dev.distillpod.core.SLEEP_TIMER_MINUTES
import dev.distillpod.core.SleepTimerStatus
import kotlinx.coroutines.delay

/** Remaining time as a clock, rounded up so it reaches 0:00 only when the timer expires. */
internal fun sleepTimerClock(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) + 999) / 1000
    val h = seconds / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Minute granularity for TalkBack, so focus does not read a changing seconds value. */
internal fun sleepTimerSpoken(ms: Long): String {
    val minutes = sleepTimerMinutes(ms)
    return if (minutes == 1L) "1 minute remaining" else "$minutes minutes remaining"
}

/** Whole minutes left, rounded up and at least 1: the moon badge and the spoken status. */
internal fun sleepTimerMinutes(ms: Long): Long = ((ms.coerceAtLeast(0) + 59_999) / 60_000).coerceAtLeast(1)

/**
 * The transport row's moon button and the sleep timer sheet. The timer itself lives in the playback
 * service. While it runs, the button carries the minutes left as a badge, and a long press cancels it.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable internal fun SleepTimerControl(
    status: SleepTimerStatus?,
    onStart: (Long) -> Unit,
    onEndOfEpisode: () -> Unit,
    onExtend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    clock: () -> Long = SystemClock::elapsedRealtime,
) {
    val view = LocalView.current
    // Announce user-initiated changes once; the countdown itself is not a live region.
    fun announce(text: String) = view.announceForAccessibility(text)
    var open by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(status) {
        now = clock()
        while (status != null && status.counting && status.remainingAt(now) > 0) {
            // Wake when the rounded-up seconds value next changes.
            delay((status.remainingAt(now) % 1000).takeIf { it > 0 } ?: 1000)
            now = clock()
        }
    }
    val remaining = status?.remainingAt(now) ?: 0
    val paused = status != null && !status.endOfEpisode && !status.counting
    val cancel = { onCancel(); announce("Sleep timer cancelled") }
    val description = when {
        status == null -> "Sleep timer"
        status.endOfEpisode -> "Sleep timer on: pauses at the end of this episode"
        else -> "Sleep timer on: ${sleepTimerSpoken(remaining)}${if (paused) ", paused" else ""}"
    }
    Box(modifier.size(48.dp).clip(CircleShape)
        .combinedClickable(role = Role.Button, onClickLabel = if (status == null) "Choose a sleep timer" else "Show sleep timer",
            onLongClickLabel = if (status != null) "Cancel sleep timer" else null,
            onLongClick = if (status != null) cancel else null, onClick = { open = true })
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        BadgedBox(badge = {
            if (status != null) Badge(Modifier.clearAndSetSemantics {}, containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                if (!status.endOfEpisode) Text(sleepTimerMinutes(remaining).toString())
            }
        }) {
            Icon(if (status == null) Icons.Outlined.Bedtime else Icons.Filled.Bedtime, contentDescription = null,
                tint = if (status == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
        }
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // Scrolls on short or large-font screens, so every option stays reachable.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Sleep timer", style = MaterialTheme.typography.titleMedium)
            if (status == null) SleepTimerChoices(
                onMinutes = { minutes -> open = false; onStart(minutes * 60_000L); announce("Sleep timer set for $minutes minutes") },
                onEndOfEpisode = { open = false; onEndOfEpisode(); announce("Playback will pause at the end of this episode") })
            else SleepTimerStatusContent(status, remaining, paused,
                onExtend = { onExtend(); announce("Sleep timer extended by ${SLEEP_TIMER_EXTENSION_MS / 60_000} minutes") },
                onCancel = { open = false; cancel() })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SleepTimerChoices(onMinutes: (Int) -> Unit, onEndOfEpisode: () -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 3, horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SLEEP_TIMER_MINUTES.forEach { minutes -> SleepChoice("$minutes min", Modifier.weight(1f)) { onMinutes(minutes) } }
    }
    SleepChoice("End of episode", Modifier.fillMaxWidth(), onEndOfEpisode)
}

@Composable private fun SleepChoice(label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.heightIn(min = 48.dp).semantics { role = Role.Button }) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

@Composable private fun SleepTimerStatusContent(status: SleepTimerStatus, remaining: Long, paused: Boolean, onExtend: () -> Unit, onCancel: () -> Unit) {
    if (status.endOfEpisode) {
        Text("Sleep at end of episode", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { contentDescription = "Sleep timer: pauses at the end of this episode" })
    } else {
        Text("Sleep in ${sleepTimerClock(remaining)}${if (paused) " · paused" else ""}", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { contentDescription = "Sleep timer: ${sleepTimerSpoken(remaining)}${if (paused) ", paused" else ""}" })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!status.endOfEpisode) {
            val extendMinutes = SLEEP_TIMER_EXTENSION_MS / 60_000
            FilledTonalButton(onClick = onExtend, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                .semantics { contentDescription = "Extend sleep timer by $extendMinutes minutes" }) { Text("+$extendMinutes min") }
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            .semantics { contentDescription = "Cancel sleep timer" }) { Text("Cancel") }
    }
}
