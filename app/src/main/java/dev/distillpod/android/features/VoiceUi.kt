package dev.distillpod.android.features

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.distillpod.android.R
import dev.distillpod.android.voice.PhoneVoiceInput

@Composable
fun VoiceControls(vm: AppViewModel) {
    if (!vm.graph.context.resources.getBoolean(R.bool.voice_enabled)) return
    val voice = vm.graph.voice
    val state by voice.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var setupError by remember { mutableStateOf<String?>(null) }
    val preferences = remember { vm.graph.context.getSharedPreferences("voice", android.content.Context.MODE_PRIVATE) }
    var rewind by remember { mutableStateOf(preferences.getBoolean("rewind", false)) }
    Text("Rewind three seconds after an answer")
    Switch(checked = rewind, onCheckedChange = { rewind = it; preferences.edit().putBoolean("rewind", it).apply() })
    var consent by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.start({ PhoneVoiceInput() })
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) voice.cancel(false) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); voice.cancel(false) }
    }
    if (state.active) {
        Text(state.phase)
        Button(onClick = { voice.cancel(true) }) { Text("Cancel and resume") }
    } else OutlinedButton(onClick = { consent = true }) { Text("Ask about this") }
    TextButton(onClick = { scope.launch {
        try { vm.graph.voiceIdentity.connect(context as android.app.Activity); setupError = "Voice access connected" }
        catch (_: Exception) { setupError = "Voice sign-in did not finish. Check configuration and try again." }
    } }) { Text("Connect voice access") }
    TextButton(onClick = { voice.cancel(false); vm.graph.voiceIdentity.disconnect(); setupError = "Voice access disconnected" }) { Text("Disconnect voice access") }
    setupError?.let { Text(it) }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (state.answer.isNotBlank()) Text(state.answer)
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("Ask about this") },
        text = { Text("Your question and selected transcript text go to OpenAI. The voice is AI generated. Uses your configured API credits. Connect voice access first. This sign-in does not enable library sync.") },
        confirmButton = { TextButton(onClick = { consent = false; permission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Ask") } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text("Cancel") } })
}
