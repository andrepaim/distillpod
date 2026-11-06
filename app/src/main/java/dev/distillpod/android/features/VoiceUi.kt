package dev.distillpod.android.features

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    val keyStore = vm.graph.voiceApiKey
    var hasKey by remember { mutableStateOf(keyStore.hasKey()) }
    var editKey by remember { mutableStateOf(false) }
    var setupMessage by remember { mutableStateOf<String?>(null) }
    val preferences = remember { vm.graph.context.getSharedPreferences("voice", Context.MODE_PRIVATE) }
    var rewind by remember { mutableStateOf(preferences.getBoolean("rewind", false)) }
    Text("Rewind three seconds after an answer")
    Switch(checked = rewind, onCheckedChange = { rewind = it; preferences.edit().putBoolean("rewind", it).apply() })
    var consent by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && keyStore.hasKey()) voice.start({ PhoneVoiceInput() })
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
    } else OutlinedButton(onClick = { if (hasKey) consent = true else editKey = true }) { Text("Ask about this") }
    TextButton(onClick = { editKey = true }) { Text(if (hasKey) "Replace OpenAI API key" else "Add OpenAI API key") }
    if (hasKey) TextButton(onClick = {
        voice.cancel(false)
        try { keyStore.clear(); hasKey = false; setupMessage = "API key removed" }
        catch (_: Exception) { setupMessage = "Could not remove the API key. Try again." }
    }) { Text("Remove API key") }
    setupMessage?.let { Text(it) }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (state.answer.isNotBlank()) Text(state.answer)
    if (editKey) VoiceApiKeyDialog(onDismiss = { editKey = false }, onSave = { value ->
        voice.cancel(false)
        keyStore.save(value)
        hasKey = true; setupMessage = "API key saved on this phone"; editKey = false
    })
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("Ask about this") },
        text = { Text("Your question and selected transcript text go directly to OpenAI. The voice is AI generated. Usage is billed to the API project associated with your saved key, including its available credits.") },
        confirmButton = { TextButton(onClick = { consent = false; permission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Ask") } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text("Cancel") } })
}

@Composable
internal fun VoiceApiKeyDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    // Deliberately not rememberSaveable: the draft must not enter saved activity state.
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn), onDismissRequest = onDismiss, title = { Text("OpenAI API key") }, text = {
        Column {
            Text("Paste your project API key. It is encrypted on this phone and excluded from library backups and sync.")
            OutlinedTextField(value = draft, onValueChange = { draft = it.take(512); error = null },
                label = { Text("API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), isError = error != null)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = draft.isNotBlank(), onClick = {
        try { onSave(draft); draft = "" }
        catch (_: IllegalArgumentException) { error = "Enter a valid OpenAI API key" }
        catch (_: Exception) { draft = ""; error = "Could not save the API key. Try again." }
    }) { Text("Save key") } }, dismissButton = { TextButton(onClick = { draft = ""; onDismiss() }) { Text("Cancel") } })
}
