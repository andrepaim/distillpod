package dev.distillpod.android.features

import android.Manifest
import android.content.Context
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.distillpod.android.R
import dev.distillpod.android.voice.PhoneVoiceInput
import dev.distillpod.android.voice.VoiceState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceControls(vm: AppViewModel) {
    if (!vm.graph.context.resources.getBoolean(R.bool.voice_enabled)) return
    val voice = vm.graph.voice
    val state by voice.state.collectAsStateWithLifecycle()
    var permissionError by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && vm.graph.voiceApiKey.hasKey()) { permissionError = null; voice.start({ PhoneVoiceInput() }) }
        else permissionError = "Allow microphone access to ask a question."
    }
    val ask = {
        if (!vm.graph.voiceApiKey.hasKey()) { vm.open("settings:voice"); false }
        else { permissionError = null; permission.launch(Manifest.permission.RECORD_AUDIO); true }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) voice.cancelFrom(dev.distillpod.android.voice.VoiceOrigin.PHONE, false) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); voice.cancelFrom(dev.distillpod.android.voice.VoiceOrigin.PHONE, false) }
    }
    VoicePlayerInteraction(state.copy(error = permissionError ?: state.error), ask, voice::finishQuestion) {
        voice.cancel(true); permissionError = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoicePlayerInteraction(state: VoiceState, onAsk: () -> Boolean, onDone: () -> Unit, onCancel: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf(false) }
    LaunchedEffect(state.active) { if (state.active) { visible = true; running = true } }
    LaunchedEffect(state.active, state.error, running) {
        if (!state.active && running && state.error == null) { delay(650); visible = false; running = false }
    }
    Button(onClick = { if (onAsk()) { reviewing = false; visible = true } }, enabled = !state.active, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text("Ask about this")
    }
    if (state.answer.isNotBlank() && !state.active) TextButton(onClick = { reviewing = true; running = false; visible = true }) { Text("View last answer") }
    if (visible) {
        val dismiss = { onCancel(); visible = false; running = false }
        ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            val displayed = if (!reviewing && !state.active && !running && state.error == null) VoiceState("Preparing") else state
            VoiceSessionContent(displayed, running, onDone = onDone, onDismiss = dismiss, onRetry = { reviewing = false; onAsk() })
        }
    }
}

@Composable
internal fun VoiceSessionContent(state: VoiceState, running: Boolean, onDone: () -> Unit, onDismiss: () -> Unit, onRetry: () -> Unit) {
    val levels = remember { mutableStateListOf<Float>() }
    val currentLevel by rememberUpdatedState(state.level)
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(state.phase) {
        if (state.phase == "Listening") {
            levels.clear()
            val start = SystemClock.elapsedRealtime()
            while (true) {
                elapsed = SystemClock.elapsedRealtime() - start
                levels.add(currentLevel)
                if (levels.size > 40) levels.removeAt(0)
                delay(50)
            }
        }
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val title = when {
            state.error != null -> "Voice paused"
            state.phase == "Preparing" -> "Getting ready…"
            state.phase == "Listening" -> "Listening…"
            state.phase == "Answering" -> "Answering…"
            running -> "Returning to podcast…"
            else -> "Last answer"
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.phase == "Preparing") CircularProgressIndicator(Modifier.size(24.dp))
            else Icon(if (state.phase == "Listening") Icons.Default.Mic else Icons.Default.VolumeUp, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (state.phase == "Listening") {
            MicrophoneWaveform(levels.toList(), state.level)
            Text("${timestamp(elapsed)} · Ask about what you just heard", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onDone, enabled = elapsed >= 300, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
        if (state.phase == "Preparing") Text("Your podcast is paused. Wait for the tone before speaking.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.phase == "Answering") Text("Microphone off · Spoken answer", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.answer.isNotBlank()) VoiceAnswerTranscript(state.answer)
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
        }
        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text(when { state.phase == "Answering" -> "Stop and resume"; state.active -> "Cancel and resume"; running || state.error != null -> "Return to podcast"; else -> "Close" })
        }
    }
}

@Composable
internal fun MicrophoneWaveform(levels: List<Float>, level: Float) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(72.dp).semantics {
        contentDescription = "Microphone input level"
        progressBarRangeInfo = ProgressBarRangeInfo(level.coerceIn(0f, 1f), 0f..1f)
    }) {
        val count = 40
        val step = size.width / count
        repeat(count) { index ->
            val amplitude = levels.getOrNull(index - (count - levels.size)) ?: 0f
            val height = (size.height * amplitude.coerceIn(0f, 1f)).coerceAtLeast(2.dp.toPx())
            val x = (index + .5f) * step
            drawLine(color, Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2),
                strokeWidth = minOf(4.dp.toPx(), step * .55f), cap = StrokeCap.Round)
        }
    }
}

@Composable
internal fun VoiceAnswerTranscript(answer: String) {
    val scroll = rememberScrollState()
    val dragging by scroll.interactionSource.collectIsDraggedAsState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(dragging) { if (dragging) follow = false }
    LaunchedEffect(answer) { if (follow && !dragging) { delay(30); scroll.scrollTo(scroll.maxValue) } }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(scroll).padding(16.dp)
            .semantics { contentDescription = "Spoken answer transcript" }) {
            Text(answer, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceSettings(vm: AppViewModel) {
    val store = vm.graph.voiceApiKey
    var hasKey by remember { mutableStateOf(store.hasKey()) }
    var editKey by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showCarSetup by remember { mutableStateOf(false) }
    val preferences = remember { vm.graph.context.getSharedPreferences("voice", Context.MODE_PRIVATE) }
    var rewind by remember { mutableStateOf(preferences.getBoolean("rewind", false)) }
    Text("Spoken answers", style = MaterialTheme.typography.titleLarge)
    Text("Ask about the podcast, hear an answer in an AI-generated voice, then return to listening.")
    Text("OpenAI API key", style = MaterialTheme.typography.titleMedium)
    Text(if (hasKey) "API key saved on this phone" else "Add a project API key to enable spoken answers", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = { editKey = true }) { Text(if (hasKey) "Replace API key" else "Add API key") }
    if (hasKey) TextButton(onClick = {
        vm.graph.voice.cancel(false)
        dev.distillpod.android.voice.HeadphoneVoiceService.stop(vm.graph.context)
        try { store.clear(); hasKey = false; message = "API key removed" }
        catch (_: Exception) { message = "Could not remove the API key. Try again." }
    }) { Text("Remove API key") }
    Text("Questions and transcript excerpts go to OpenAI and use your API credits. Your key stays encrypted on this phone and is excluded from backups and sync.", style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text("Answers", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Rewind after an answer")
            Text("Replay the preceding three seconds", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = rewind, onCheckedChange = { rewind = it; preferences.edit().putBoolean("rewind", it).apply() })
    }
    HorizontalDivider()
    Text("Ways to ask", style = MaterialTheme.typography.titleMedium)
    Text("On your phone, use Ask about this in the player.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { vm.open("settings:playback") }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Headphones, null); Spacer(Modifier.width(8.dp)); Text("Headphone controls")
    }
    OutlinedButton(onClick = { showCarSetup = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.DirectionsCar, null); Spacer(Modifier.width(8.dp)); Text("Android Auto setup")
    }
    message?.let { Text(it) }
    if (editKey) VoiceApiKeyDialog(onDismiss = { editKey = false }, onSave = {
        vm.graph.voice.cancel(false); store.save(it); hasKey = true; message = "API key saved on this phone"; editKey = false
    })
    if (showCarSetup) ModalBottomSheet(onDismissRequest = { showCarSetup = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val carConnected by vm.graph.carVoiceEntry.connected.collectAsStateWithLifecycle()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Android Auto setup", style = MaterialTheme.typography.headlineSmall)
            Text(if (carConnected) "Car voice connected" else "Open DistillPod on the car display to connect voice.")
            Text("While parked, open DistillPod from the car launcher and look for Ask at the top of the player. There may be only one DistillPod icon.")
            Text("After updating, reconnect Android Auto and open the app from its launcher. Ask is available on compatible hosts.")
            Text("Voice uses the API key saved here. Key entry and microphone permission setup stay on the phone.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { showCarSetup = false }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
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
