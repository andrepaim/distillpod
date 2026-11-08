package dev.distillpod.android.features

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import dev.distillpod.android.playback.PlaybackRepository
import dev.distillpod.android.playback.PlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import java.util.concurrent.atomic.AtomicReference

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun VideocastPlayer(playback: PlaybackRepository, episodeId: String,
    title: String = "", fullscreenExitRequest: Int = 0, onFullscreen: (Boolean) -> Unit = {}, onCapture: () -> Unit = {}, captureBusy: Boolean = false,
    onVoice: () -> Unit = {}, onToggle: (() -> Unit)? = null, onSeek: ((Long) -> Unit)? = null, voiceEnabled: Boolean = false, voiceActive: Boolean = false,
    audioOnlyArtwork: @Composable () -> Unit) {
    var player by remember { mutableStateOf<Player?>(null) }
    val state by playback.state.collectAsStateWithLifecycle()
    var video by remember(episodeId) { mutableStateOf(false) }
    var loading by remember(episodeId) { mutableStateOf(false) }
    var error by remember(episodeId) { mutableStateOf<String?>(null) }
    var firstFrame by remember(episodeId) { mutableStateOf(false) }
    var presentation by remember(episodeId) { mutableStateOf(VideoPresentation()) }
    val fullscreen = video && presentation.fullscreen
    LaunchedEffect(fullscreenExitRequest) {
        if (presentation.fullscreen) presentation = presentation.exit()
    }
    val notifyFullscreen by rememberUpdatedState(onFullscreen)
    SideEffect { notifyFullscreen(fullscreen) }
    DisposableEffect(episodeId) { onDispose { notifyFullscreen(false) } }
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    fun autoRotate() = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
    LaunchedEffect(video, landscape) {
        if (!video) presentation = VideoPresentation()
        else if (!presentation.forcedLandscape) presentation = presentation.orientation(landscape, autoRotate())
    }
    val orientationChanged by rememberUpdatedState<(Boolean) -> Unit>({ horizontal ->
        // Normal automatic entry/exit follows the display configuration. Sensors only
        // release the explicit landscape request once the owner actually turns the phone.
        if (presentation.forcedLandscape) presentation = presentation.orientation(horizontal, autoRotate())
    })
    DisposableEffect(context, video) {
        val sensor = object : OrientationEventListener(context) {
            override fun onOrientationChanged(angle: Int) {
                when (angle) {
                    in 60..120, in 240..300 -> orientationChanged(true)
                    in 0..30, in 150..210, in 330..359 -> orientationChanged(false)
                }
            }
        }
        if (video && sensor.canDetectOrientation()) sensor.enable()
        onDispose { sensor.disable() }
    }
    VideoWindow(fullscreen, presentation.forcedLandscape)
    BackHandler(fullscreen) { presentation = presentation.exit() }
    val modeRequest = remember(playback, episodeId) { AtomicReference<Job?>() }
    val failure by playback.videoFailure.collectAsStateWithLifecycle()
    LaunchedEffect(failure, episodeId) {
        if (failure?.first == episodeId) { video = false; error = failure?.second }
    }
    LaunchedEffect(playback) { player = playback.sessionPlayer() }
    DisposableEffect(player, episodeId) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onPlayerError(playbackError: androidx.media3.common.PlaybackException) {
                if (video) { video = false; error = "Video is unavailable. Returning to audio." }
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(playback, episodeId, video) {
        val request = currentCoroutineContext().job
        modeRequest.set(request)
        firstFrame = false
        loading = true
        try { playback.video(episodeId, video) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { video = false; error = "Video is unavailable. Switched to audio mode." }
        finally { modeRequest.compareAndSet(request, null); loading = false }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, playback, episodeId) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            video = false
            presentation = VideoPresentation()
            modeRequest.getAndSet(null)?.cancel()
            playback.leaveVideo(episodeId)
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(playback, episodeId) { onDispose { playback.leaveVideo(episodeId) } }
    Column(if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        if (video) Box(if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
            // Keep the same Android view, surface and MediaController across fullscreen changes.
            AndroidView(factory = { viewContext -> PlayerView(viewContext).apply { useController = false } },
                update = { it.player = player; it.keepScreenOn = state.playing },
                onRelease = { it.player = null }, modifier = Modifier.fillMaxSize().background(Color.Black))
            if (loading || !firstFrame) CircularProgressIndicator()
            if (fullscreen) VideoOverlay(state, title, loading || !firstFrame, captureBusy, voiceActive, voiceEnabled,
                onToggle = onToggle ?: { player?.let { if (it.isPlaying) it.pause() else it.play() }; Unit },
                onSeek = onSeek ?: { player?.seekTo(it); Unit }, onCapture = onCapture, onVoice = onVoice,
                onExit = { presentation = presentation.exit() })
            else IconButton(onClick = { presentation = presentation.enter() },
                modifier = Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = .65f))) {
                Icon(Icons.Default.Fullscreen, "Enter fullscreen", tint = Color.White)
            }
        } else audioOnlyArtwork()
        if (!fullscreen) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !video, onClick = { error = null; video = false }, label = { Text("Audio") })
                FilterChip(selected = video, enabled = !loading, onClick = { error = null; video = true }, label = { Text(if (loading) "Loading…" else "Video") })
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** Window/orientation effects never touch playback. The original activity policy is restored on exit. */
@Composable private fun VideoWindow(fullscreen: Boolean, forcedLandscape: Boolean) {
    val activity = LocalContext.current.activity() ?: return
    val original = remember(activity) { activity.requestedOrientation }
    var forcing by remember(activity) { mutableStateOf(false) }
    DisposableEffect(activity, fullscreen) {
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        val previousBehavior = bars.systemBarsBehavior
        if (fullscreen) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen) {
                if (forcing) { activity.requestedOrientation = original; forcing = false }
                bars.systemBarsBehavior = previousBehavior
                bars.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    LaunchedEffect(fullscreen, forcedLandscape) {
        if (fullscreen && forcedLandscape) {
            forcing = true
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else if (forcing) {
            activity.requestedOrientation = original
            forcing = false
        }
    }
}

@Composable internal fun VideoOverlay(state: PlaybackState, title: String, buffering: Boolean,
    captureBusy: Boolean, voiceActive: Boolean, voiceEnabled: Boolean,
    onToggle: () -> Unit, onSeek: (Long) -> Unit, onCapture: () -> Unit, onVoice: () -> Unit, onExit: () -> Unit) {
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val accessibility = androidx.compose.ui.platform.LocalAccessibilityManager.current
    val hideAfter = accessibility?.calculateRecommendedTimeoutMillis(3500, containsIcons = true, containsText = true, containsControls = true) ?: 3500L
    val pinned = !state.playing || buffering || captureBusy || voiceActive || dragging != null
    LaunchedEffect(controls, pinned, interaction, hideAfter) {
        if (pinned) controls = true
        else if (controls) { delay(hideAfter); controls = false }
    }
    fun touch() { controls = true; interaction++ }
    Box(Modifier.fillMaxSize().testTag("Fullscreen video")) {
        Box(Modifier.fillMaxSize().semantics { contentDescription = "Show or hide video controls" }
            .clickable { if (!pinned) controls = !controls else touch() })
        if (controls) Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .50f))
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { touch(); onExit() }) { Icon(Icons.Default.FullscreenExit, "Exit fullscreen", tint = Color.White) }
            }
            Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { touch(); onSeek((state.positionMs - 10_000).coerceAtLeast(0)) }) { Icon(Icons.Default.Replay10, "Back 10 seconds", tint = Color.White) }
                FilledIconButton(onClick = { touch(); onToggle() }, modifier = Modifier.size(64.dp)) {
                    Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.playing) "Pause video" else "Play video", Modifier.size(36.dp))
                }
                IconButton(onClick = { touch(); onSeek((state.positionMs + 30_000).coerceAtMost(state.durationMs.coerceAtLeast(0))) }) { Icon(Icons.Default.Forward30, "Forward 30 seconds", tint = Color.White) }
            }
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { touch(); onCapture() }, enabled = !captureBusy && !voiceActive) {
                        Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text(if (captureBusy) "Saving…" else "Distill")
                    }
                    OutlinedButton(onClick = { touch(); onVoice() }, enabled = voiceEnabled && !voiceActive && !captureBusy) {
                        Icon(Icons.Default.Mic, null); Spacer(Modifier.width(6.dp)); Text(if (voiceActive) "Voice active" else "Voice question")
                    }
                    Spacer(Modifier.weight(1f))
                    Text("${timestamp(dragging?.toLong() ?: state.positionMs)} / ${timestamp(state.durationMs)}", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                Slider(value = dragging ?: state.positionMs.toFloat().coerceIn(0f, state.durationMs.coerceAtLeast(1).toFloat()),
                    onValueChange = { touch(); dragging = it }, onValueChangeFinished = { dragging?.let { onSeek(it.toLong()) }; dragging = null; touch() },
                    valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(), enabled = state.durationMs > 0,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Video playback position" })
            }
        }
    }
}
