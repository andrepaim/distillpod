package dev.distillpod.android.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.core.PlaybackSpeeds
import dev.distillpod.core.SpeedPreferences

/** What the Now Playing speed and effects sheet shows (#183). */
internal data class PlaybackEffectsState(val speed: Float = 1f, val showTitle: String? = null, val keepsShowSpeed: Boolean = false,
    val skipSilence: Boolean = false, val volumeBoost: Boolean = false, val boostUnavailable: Boolean = false)

/** The slider moves in 0.05× steps between [PlaybackSpeeds.MIN] and [PlaybackSpeeds.MAX]: 49 stops between the ends. */
internal val SPEED_SLIDER_STEPS = ((PlaybackSpeeds.MAX - PlaybackSpeeds.MIN) * 20).toInt() - 1

/**
 * Speed and effects, the content of the speed sheet: the current value with tenth steps, a 0.05× slider,
 * the presets, the per-show speed choice, skip silence and volume boost.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PlaybackEffectsControl(state: PlaybackEffectsState, onSpeed: (Float) -> Unit, onKeepForShow: (Boolean) -> Unit,
    onSkipSilence: (Boolean) -> Unit, onVolumeBoost: (Boolean) -> Unit) {
    val speed = PlaybackSpeeds.normalize(state.speed) ?: 1f
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: speed
    val label = PlaybackSpeeds.label(shown)
    val haptics = LocalHapticFeedback.current
    // One traversal group: speed and effects are read together and stay separate from neighbouring player controls.
    Column(Modifier.fillMaxWidth().semantics { isTraversalGroup = true }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { onSpeed(PlaybackSpeeds.slower(speed)) }, enabled = PlaybackSpeeds.canSlowDown(speed)) {
                Icon(Icons.Default.Remove, "Slower")
            }
            Text(label, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 128.dp).clearAndSetSemantics { contentDescription = "Playback speed $label" })
            FilledTonalIconButton(onClick = { onSpeed(PlaybackSpeeds.faster(speed)) }, enabled = PlaybackSpeeds.canSpeedUp(speed)) {
                Icon(Icons.Default.Add, "Faster")
            }
        }
        Slider(value = shown, valueRange = PlaybackSpeeds.MIN..PlaybackSpeeds.MAX, steps = SPEED_SLIDER_STEPS,
            onValueChange = { value ->
                PlaybackSpeeds.normalize(value)?.let { snapped ->
                    if (snapped != shown) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    dragging = snapped
                }
            },
            onValueChangeFinished = { dragging?.let(onSpeed); dragging = null },
            // 49 stops would draw as a row of dots; the haptic tick marks each one instead.
            colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Adjust playback speed"; stateDescription = label })
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            PlaybackSpeeds.presets.forEach { preset ->
                FilterChip(selected = speed == preset, onClick = { onSpeed(preset) }, label = { Text(PlaybackSpeeds.label(preset)) })
            }
        }
        state.showTitle?.let { show -> EffectSwitch("Keep this speed for $show", state.keepsShowSpeed, onKeepForShow) }
        EffectSwitch("Skip silence", state.skipSilence, onSkipSilence)
        EffectSwitch("Volume boost", state.volumeBoost, onVolumeBoost,
            "Volume boost isn't available on this device. Playback continues at normal volume.".takeIf { state.volumeBoost && state.boostUnavailable })
    }
}

/** A whole-row switch; the row is the single toggle TalkBack reads with its title and any warning. */
@Composable private fun EffectSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit, warning: String? = null) {
    ListItem(headlineContent = { Text(title) },
        supportingContent = warning?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange))
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

/** The transport row's speed pill ("1.25×"), which opens the speed and effects sheet. */
@Composable internal fun SpeedPill(speed: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = PlaybackSpeeds.label(PlaybackSpeeds.normalize(speed) ?: 1f)
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.semantics { contentDescription = "Speed and effects, $label" }) {
        Box(Modifier.defaultMinSize(minWidth = 60.dp, minHeight = 40.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** The speed pill and its sheet; the sheet stays open while the speed or effects change. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PlayerSpeedControl(vm: AppViewModel, speed: Float, podcast: PodcastEntity?, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    SpeedPill(speed, { open = true }, modifier)
    if (open) ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Speed & effects", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
            PlayerEffects(vm, speed, podcast)
        }
    }
}
