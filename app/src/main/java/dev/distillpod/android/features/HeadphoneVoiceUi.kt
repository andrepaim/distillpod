package dev.distillpod.android.features

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.distillpod.android.voice.HeadphoneVoiceService

@Composable
internal fun HeadphoneVoiceSettings(vm: AppViewModel, hasKey: Boolean) {
    val prefs by vm.graph.settings.flow.collectAsStateWithLifecycle(initialValue = dev.distillpod.android.data.Preferences())
    val ready by vm.graph.headphoneVoice.ready.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (HeadphoneVoiceService.canArm(vm.graph.context)) { error = null; vm.flag("headphoneVoice", true) }
        else error = "Allow microphone and notification access to use the headphone shortcut."
    }
    Text("Headphone shortcut", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Ask with next-track button")
            Text("Pause your podcast and ask a question using your headset microphone.", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = prefs.headphoneVoice, enabled = hasKey, onCheckedChange = { enabled ->
            if (!enabled) { vm.flag("headphoneVoice", false); HeadphoneVoiceService.stop(vm.graph.context) }
            else permission.launch(buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray())
        })
    }
    if (!hasKey) Text("Save your API key first.", style = MaterialTheme.typography.bodySmall)
    if (prefs.headphoneVoice) {
        Text(if (ready) "Headphone voice ready" else "Start an episode with DistillPod open to prepare voice before locking your phone.")
        Text("A notification keeps the shortcut ready while you listen. The microphone stays off until you ask. After five minutes paused, open DistillPod to prepare it again.", style = MaterialTheme.typography.bodySmall)
    }
    Text("In your headphone app, assign a gesture to Next track. Next asks; press it again to finish your question or stop an answer. Turning this off restores normal Next track. Accessories sending the same media key also use this shortcut.", style = MaterialTheme.typography.bodySmall)
    Text("Bluetooth headset voice needs Android 12 or later. Wired and USB headsets with microphones are also supported. The phone microphone is not used as a fallback.", style = MaterialTheme.typography.bodySmall)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
