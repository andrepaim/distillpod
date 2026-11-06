package dev.distillpod.android.features

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.distillpod.android.R
import dev.distillpod.android.data.Preferences
import dev.distillpod.android.voice.HeadphoneVoiceService

@Composable
internal fun PlaybackHeadphoneSettings(vm: AppViewModel, prefs: Preferences) {
    val voiceEnabled = vm.graph.context.resources.getBoolean(R.bool.voice_enabled)
    Text("Headphone shortcuts", style = MaterialTheme.typography.titleLarge)
    Text("Choose what your headphone buttons do.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    HeadphoneShortcutCard("Previous track", "Save moment", "Save the last 60 seconds without pausing your podcast.",
        prefs.headphoneDistill, "Save moments with Previous track", onChange = { vm.flag("headphoneDistill", it) })
    if (voiceEnabled) HeadphoneNextSettings(vm, prefs.headphoneVoice)
    HeadphoneSetup(voiceEnabled)
    HorizontalDivider()
    Text("Feedback", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Moment saved sound")
            Text("Play a pen scribble whenever a moment is saved, from headphones, the phone or Android Auto. Uses media volume.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = prefs.distillSound, onCheckedChange = { vm.flag("distillSound", it) },
            modifier = Modifier.semantics { contentDescription = "Moment saved sound" })
    }
}

@Composable
private fun HeadphoneShortcutCard(command: String, action: String, detail: String, checked: Boolean,
    switchLabel: String, switchEnabled: Boolean = true, onChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit = {}) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(command, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(action, style = MaterialTheme.typography.titleMedium)
                }
                Switch(checked = checked, enabled = switchEnabled, onCheckedChange = onChange,
                    modifier = Modifier.semantics { contentDescription = switchLabel })
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

private data class HeadphonePermissions(val microphone: Boolean, val notifications: Boolean) {
    val granted get() = microphone && notifications
}
private fun headphonePermissions(context: Context) = HeadphonePermissions(
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
    NotificationManagerCompat.from(context).areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED))

@Composable
private fun HeadphoneNextSettings(vm: AppViewModel, enabled: Boolean) {
    val context = LocalContext.current
    val keyRevision by vm.graph.voiceApiKey.revisions.collectAsStateWithLifecycle()
    val hasKey = remember(keyRevision) { vm.graph.voiceApiKey.hasKey() }
    val ready by vm.graph.headphoneVoice.ready.collectAsStateWithLifecycle()
    var permissions by remember { mutableStateOf(headphonePermissions(context)) }
    var permissionError by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissions = headphonePermissions(context)
                if (permissions.granted) permissionError = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissions = headphonePermissions(context)
        permissionError = !permissions.granted
        if (permissions.granted) vm.flag("headphoneVoice", true)
    }
    val requestPermissions = {
        val missing = buildList {
            if (!permissions.microphone) add(Manifest.permission.RECORD_AUDIO)
            if (!permissions.notifications && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) permission.launch(missing.toTypedArray())
        else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    HeadphoneShortcutCard("Next track", "Ask about the podcast", "Pause for a question and spoken answer, then resume listening.",
        enabled, "Ask with Next track", switchEnabled = hasKey || enabled, onChange = { turnOn ->
            permissionError = false
            if (!turnOn) { vm.flag("headphoneVoice", false); HeadphoneVoiceService.stop(context) }
            else if (permissions.granted) vm.flag("headphoneVoice", true)
            else requestPermissions()
        }) {
        val status = when {
            !hasKey -> "Setup required"
            permissionError -> "Setup required"
            !enabled -> "Off"
            !permissions.granted -> "Setup required"
            ready -> "Ready"
            else -> "Open app to prepare"
        }
        Text(status, style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        when {
            !hasKey -> {
                Text("Add your API key in Voice settings, then return here to enable the shortcut.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.open("settings:voice") }) { Text("Set up voice") }
            }
            (enabled || permissionError) && !permissions.granted -> {
                Text("Microphone and notifications are needed for voice with the screen locked.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = requestPermissions) { Text(if (!permissions.microphone) "Allow microphone" else "Allow notifications") }
            }
            enabled -> {
                Text(if (ready) "The shortcut is ready for locked-screen use. The microphone stays off until you ask."
                    else "Start playback with DistillPod open and wait for the ready notification before locking your phone.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Press Next again to finish your question or stop the answer.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (permissionError) {
            Text(if (!permissions.microphone) "Microphone access is off. You can also enable it in app settings."
                else "Notifications are off. You can also enable them in app settings.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text("Open app settings") }
        }
    }
}

@Composable
private fun HeadphoneSetup(voiceEnabled: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Text("Headphone setup", modifier = Modifier.weight(1f))
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            if (expanded) "Collapse headphone setup" else "Expand headphone setup")
    }
    if (expanded) {
        Text("Assign gestures in your headphone app", style = MaterialTheme.typography.titleMedium)
        Text(if (voiceEnabled) "Assign one gesture to Previous track for Save moment, and another to Next track for Ask. Turn on the shortcuts you want above."
            else "Assign a gesture to Previous track, then turn on Save moment above.", style = MaterialTheme.typography.bodyMedium)
        Text("Liberty 4 NC: Soundcore → Liberty 4 NC → Controls. Choose the gestures you prefer, such as triple tap. Check the controls separately when using a single earbud.", style = MaterialTheme.typography.bodyMedium)
        Text(if (voiceEnabled) "Other accessories sending the same Previous or Next command also use these shortcuts while DistillPod handles playback. Turning a shortcut off restores its usual playback behavior."
            else "Other accessories sending Previous track also use this shortcut while DistillPod handles playback. Turning it off restores its usual playback behavior.", style = MaterialTheme.typography.bodySmall)
        if (voiceEnabled) {
            Text("Voice while the phone is locked", style = MaterialTheme.typography.titleMedium)
            Text("Start an episode with DistillPod open before locking the phone. Wait for the Headphone voice ready notification. After five minutes paused, reopen DistillPod to prepare it again.", style = MaterialTheme.typography.bodyMedium)
            Text("Wait for the tone before speaking. Next finishes recording while listening, or cancels preparation and stops an answer. A transcript must cover the current listening position.", style = MaterialTheme.typography.bodySmall)
            Text("Bluetooth voice needs Android 12 or later. Wired and USB headsets with microphones also work. If the headset disconnects, voice stops and playback stays paused; the phone microphone is not used as a fallback.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
