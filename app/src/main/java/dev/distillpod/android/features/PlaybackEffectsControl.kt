package dev.distillpod.android.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.core.PlaybackSpeeds
import dev.distillpod.core.SpeedPreferences

/** What the Now Playing speed and effects control shows (#183). */
internal data class PlaybackEffectsState(val speed: Float = 1f, val showTitle: String? = null, val keepsShowSpeed: Boolean = false,
    val skipSilence: Boolean = false, val volumeBoost: Boolean = false, val boostUnavailable: Boolean = false)

/** Speed steps and presets, the per-show speed choice, skip silence and volume boost on Now Playing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PlaybackEffectsControl(state: PlaybackEffectsState, onSpeed: (Float) -> Unit, onKeepForShow: (Boolean) -> Unit,
    onSkipSilence: (Boolean) -> Unit, onVolumeBoost: (Boolean) -> Unit) {
    val speed = PlaybackSpeeds.normalize(state.speed) ?: 1f
    val label = PlaybackSpeeds.label(speed)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onSpeed(PlaybackSpeeds.slower(speed)) }, enabled = PlaybackSpeeds.canSlowDown(speed)) {
                    Icon(Icons.Default.Remove, "Slower")
                }
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 56.dp).clearAndSetSemantics { contentDescription = "Playback speed $label" })
                IconButton(onClick = { onSpeed(PlaybackSpeeds.faster(speed)) }, enabled = PlaybackSpeeds.canSpeedUp(speed)) {
                    Icon(Icons.Default.Add, "Faster")
                }
            }
            PlaybackSpeeds.presets.forEach { preset ->
                FilterChip(selected = speed == preset, onClick = { onSpeed(preset) }, label = { Text(PlaybackSpeeds.label(preset)) },
                    modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
        state.showTitle?.let { show ->
            val description = "Keep this speed for $show"
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = state.keepsShowSpeed, role = Role.Switch, onValueChange = onKeepForShow), verticalAlignment = Alignment.CenterVertically) {
                Text(description, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = state.keepsShowSpeed, onCheckedChange = null)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.skipSilence, onClick = { onSkipSilence(!state.skipSilence) }, label = { Text("Skip silence") })
            FilterChip(selected = state.volumeBoost, onClick = { onVolumeBoost(!state.volumeBoost) }, label = { Text("Volume boost") })
        }
        if (state.volumeBoost && state.boostUnavailable) Text("Volume boost isn't available on this device. Playback continues at normal volume.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** Binds [PlaybackEffectsControl] to the device-local settings and the service's boost status. */
@Composable internal fun PlayerEffects(vm: AppViewModel, speed: Float, podcast: PodcastEntity?) {
    val prefs by vm.graph.preferences.collectAsStateWithLifecycle()
    val speeds by vm.graph.settings.speeds.collectAsStateWithLifecycle(SpeedPreferences())
    val boost by vm.graph.playbackEvents.volumeBoost.collectAsStateWithLifecycle()
    PlaybackEffectsControl(PlaybackEffectsState(speed, podcast?.title, speeds.owns(podcast?.id), prefs.skipSilence, prefs.volumeBoost, boost.unavailable),
        onSpeed = vm::speed, onKeepForShow = { keep -> podcast?.let { vm.keepShowSpeed(it.id, speed, keep) } },
        onSkipSilence = { vm.flag("skipSilence", it) }, onVolumeBoost = { vm.flag("volumeBoost", it) })
}
