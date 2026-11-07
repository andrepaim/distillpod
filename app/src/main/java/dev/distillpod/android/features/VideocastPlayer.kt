package dev.distillpod.android.features

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import dev.distillpod.android.playback.PlaybackRepository
import kotlinx.coroutines.CancellationException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun VideocastPlayer(playback: PlaybackRepository, episodeId: String, audioOnlyArtwork: @Composable () -> Unit) {
    var player by remember { mutableStateOf<Player?>(null) }
    var video by remember(episodeId) { mutableStateOf(false) }
    var loading by remember(episodeId) { mutableStateOf(false) }
    var error by remember(episodeId) { mutableStateOf<String?>(null) }
    LaunchedEffect(playback) { player = playback.sessionPlayer() }
    LaunchedEffect(episodeId, video) {
        loading = true
        try { playback.video(episodeId, video) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { video = false; error = "Video is unavailable. Switched to audio mode." }
        finally { loading = false }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) video = false }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Leaving the player restores audio, including when the composable's own scope is cancelled.
    DisposableEffect(playback, episodeId) { onDispose { playback.leaveVideo(episodeId) } }
    if (video && !loading) AndroidView(factory = { context -> PlayerView(context).apply { useController = false } },
        update = { it.player = player }, onRelease = { it.player = null },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) else audioOnlyArtwork()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !video, onClick = { error = null; video = false }, label = { Text("Audio") })
        FilterChip(selected = video, enabled = !loading, onClick = { error = null; video = true }, label = { Text(if (loading) "Loading…" else "Video") })
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
