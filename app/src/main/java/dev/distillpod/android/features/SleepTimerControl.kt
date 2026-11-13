package dev.distillpod.android.features

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    val minutes = ((ms.coerceAtLeast(0) + 59_999) / 60_000).coerceAtLeast(1)
    return if (minutes == 1L) "1 minute remaining" else "$minutes minutes remaining"
}

/** Sleep timer entry and status on Now Playing. The timer itself lives in the playback service. */
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
    var choosing by rememberSaveable { mutableStateOf(false) }
    if (choosing) AlertDialog(
        onDismissRequest = { choosing = false },
        icon = { Icon(Icons.Default.Bedtime, contentDescription = null) },
        title = { Text("Sleep timer") },
        text = {
            // Scrolls on short or large-font screens, so every option stays reachable.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SLEEP_TIMER_MINUTES.forEach { minutes ->
                    TextButton(onClick = {
                        choosing = false
                        onStart(minutes * 60_000L)
                        announce("Sleep timer set for $minutes minutes")
                    }, modifier = Modifier.fillMaxWidth()) { Text("$minutes minutes", Modifier.fillMaxWidth()) }
                }
                TextButton(onClick = {
                    choosing = false
                    onEndOfEpisode()
                    announce("Playback will pause at the end of this episode")
                }, modifier = Modifier.fillMaxWidth()) { Text("End of episode", Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
    )
    if (status == null) {
        OutlinedButton(onClick = { choosing = true }, modifier = modifier) {
            Icon(Icons.Default.Bedtime, contentDescription = null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Sleep timer")
        }
        return
    }
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(status) {
        now = clock()
        while (status.counting && status.remainingAt(now) > 0) {
            // Wake when the rounded-up seconds value next changes.
            delay((status.remainingAt(now) % 1000).takeIf { it > 0 } ?: 1000)
            now = clock()
        }
    }
    val remaining = status.remainingAt(now)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Bedtime, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        if (status.endOfEpisode) {
            Text("Sleep at end of episode", Modifier.weight(1f).semantics { contentDescription = "Sleep timer: pauses at the end of this episode" },
                style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("Sleep in ${sleepTimerClock(remaining)}${if (status.counting) "" else " · paused"}",
                Modifier.weight(1f).semantics { contentDescription = "Sleep timer: ${sleepTimerSpoken(remaining)}${if (status.counting) "" else ", paused"}" },
                style = MaterialTheme.typography.bodyMedium)
            val extendMinutes = SLEEP_TIMER_EXTENSION_MS / 60_000
            OutlinedButton(onClick = { onExtend(); announce("Sleep timer extended by $extendMinutes minutes") },
                modifier = Modifier.semantics { contentDescription = "Extend sleep timer by $extendMinutes minutes" }) { Text("+$extendMinutes min") }
        }
        TextButton(onClick = { onCancel(); announce("Sleep timer cancelled") },
            modifier = Modifier.semantics { contentDescription = "Cancel sleep timer" }) { Text("Cancel") }
    }
}
