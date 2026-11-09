package dev.distillpod.android.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.distillpod.android.PlaybackService
import dev.distillpod.android.data.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PlaybackState(val episodeId: String? = null, val title: String = "", val positionMs: Long = 0,
    val durationMs: Long = 0, val playing: Boolean = false, val speed: Float = 1f,
    val queue: List<String> = emptyList(), val error: String? = null)
class PlaybackEvents {
    val lastSkipped = MutableStateFlow<SkippedMoment?>(null)
    val videoFailure = MutableStateFlow<Pair<String, String>?>(null)
}
data class SkippedMoment(val episodeId: String, val positionMs: Long, val endMs: Long)

/** Replace source and starting position in one command, even while a timeline is preparing. */
internal fun swapCurrentSource(player: Player, desired: MediaItem, position: Long = player.currentPosition.coerceAtLeast(0),
    resume: Boolean = player.playWhenReady) {
    val index = player.currentMediaItemIndex
    if (index !in 0 until player.mediaItemCount || player.currentMediaItem?.mediaId != desired.mediaId) return
    val items = (0 until player.mediaItemCount).map { if (it == index) desired else player.getMediaItemAt(it) }
    player.setMediaItems(items, index, position)
    player.prepare()
    player.playWhenReady = resume
}

/** Media3 can deliver an initial no-op AUTO_TRANSITION after connecting a controller. */
internal fun voicePositionChanged(reason: Int, oldId: String?, newId: String?, oldMs: Long, newMs: Long) =
    reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION || oldId != newId || oldMs != newMs

/** Source replacements/reorders of the same episode must not checkpoint a removal. */
internal fun shouldCheckpointRemoval(reason: Int, oldId: String?, newId: String?) =
    reason == Player.DISCONTINUITY_REASON_REMOVE && oldId != null && oldId != newId

/** Clear-and-stop must also clear play intent so a later enqueue cannot auto-start. */
internal fun clearPlaybackQueue(player: Player) { player.pause(); player.clearMediaItems() }

fun mediaItem(ep: EpisodeEntity, podcast: PodcastEntity?): MediaItem {
    val file = ep.localFile?.let(::File)?.takeIf { it.isFile }
    return MediaItem.Builder().setMediaId(ep.id).setUri(file?.let(Uri::fromFile) ?: Uri.parse(ep.audioUrl))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(ep.title).setArtist(podcast?.title ?: "DistillPod")
            .setArtworkUri((ep.imageUrl ?: podcast?.imageUrl)?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(Uri::parse)).build()).build()
}

internal fun videocastMediaItem(ep: EpisodeEntity, podcast: PodcastEntity?, video: Boolean): MediaItem =
    mediaItem(ep, podcast).let {
        if (video && dev.distillpod.core.YouTubeUrl.videoId(ep.audioUrl) != null)
            it.buildUpon().setUri(Uri.parse(ep.audioUrl).buildUpon().appendQueryParameter("distillpod_video", "1").build()).build()
        else it
    }

/** Queue edits must keep an active video URI even when offline audio is also available. */
internal fun preserveVideoItem(ep: EpisodeEntity, podcast: PodcastEntity?, current: MediaItem?): MediaItem {
    val uri = current?.localConfiguration?.uri
    return if (current?.mediaId == ep.id && uri != null &&
        dev.distillpod.core.YouTubeUrl.videoId(uri.toString()) != null && uri.getQueryParameter("distillpod_video") == "1") current
        else mediaItem(ep, podcast)
}

class PlaybackRepository(private val context: Context, private val db: AppDatabase, private val scope: CoroutineScope,
    private val prepareVideo: suspend (String) -> Unit = { url ->
        val streams = runInterruptible(Dispatchers.IO) {
            (context.applicationContext as dev.distillpod.android.DistillPodApplication).graph.youtube.streams(url)
        }
        require(streams.video != null) { "No compatible video is available for this episode" }
    }) {
    private val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    private val mutable = MutableStateFlow(PlaybackState())
    val state = mutable.asStateFlow()
    init {
        future.addListener({
            runCatching { future.get() }.onSuccess { controller ->
                fun refreshState() {
                    mutable.value = PlaybackState(controller.currentMediaItem?.mediaId, controller.mediaMetadata.title?.toString() ?: "",
                        controller.currentPosition.coerceAtLeast(0), controller.duration.coerceAtLeast(0), controller.isPlaying,
                        controller.playbackParameters.speed, (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId }, mutable.value.error)
                }
                controller.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) { refreshState() }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { mutable.update { it.copy(error = "Playback stopped. Check your connection or download this episode.") } }
                    override fun onMediaItemTransition(item: MediaItem?, reason: Int) { mutable.update { it.copy(error = null) } }
                })
                scope.launch {
                    while (isActive) {
                        refreshState()
                        delay(500)
                    }
                }
            }.onFailure { mutable.update { it.copy(error = "The player could not connect. Reopen DistillPod.") } }
        }, ContextCompat.getMainExecutor(context))
    }
    private suspend fun controller() = withContext(Dispatchers.IO) { future.get(10, TimeUnit.SECONDS) }
    suspend fun sessionPlayer(): Player = controller()
    val videoFailure get() = (context.applicationContext as dev.distillpod.android.DistillPodApplication).graph.playbackEvents.videoFailure
    fun leaveVideo(episodeId: String) { scope.launch { runCatching { video(episodeId, false) } } }

    /** Swap the same episode at the same position; offline audio remains the default. */
    suspend fun video(episodeId: String, enabled: Boolean) {
        val c = controller()
        val id = withContext(Dispatchers.Main) { c.currentMediaItem?.mediaId } ?: return
        if (id != episodeId) return
        val ep = db.library().episode(id) ?: return
        val youtube = dev.distillpod.core.YouTubeUrl.videoId(ep.audioUrl) != null
        if (enabled && youtube) prepareVideo(ep.audioUrl)
        val podcast = db.library().podcast(ep.podcastId)
        withContext(Dispatchers.Main) {
            if (c.currentMediaItem?.mediaId != id) return@withContext
            val position = c.currentPosition.coerceAtLeast(0)
            if (enabled) videoFailure.value = null
            (context.applicationContext as dev.distillpod.android.DistillPodApplication).graph.diagnostics.event(
                if (enabled) "videocast.video_requested" else "videocast.audio_restored", reason = if (enabled) "accepted" else "ready", source = "phone")
            c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, !enabled).build()
            if (youtube) {
                val desired = videocastMediaItem(ep, podcast, enabled)
                if (c.currentMediaItem?.localConfiguration?.uri != desired.localConfiguration?.uri) {
                    swapCurrentSource(c, desired, position)
                }
            }
        }
    }
    suspend fun play(id: String, positionMs: Long? = null) {
        val ep = db.library().episode(id) ?: error("Episode is unavailable")
        val controller = controller()
        val all = db.library().episodes().associateBy { it.id }
        val podcasts = db.library().podcasts().associateBy { it.id }
        withContext(Dispatchers.Main) {
            if (controller.currentMediaItem?.mediaId == id) {
                val desired = preserveVideoItem(ep, podcasts[ep.podcastId], controller.currentMediaItem)
                if (controller.currentMediaItem?.localConfiguration?.uri != desired.localConfiguration?.uri) {
                    val position = positionMs ?: controller.currentPosition.coerceAtLeast(0)
                    swapCurrentSource(controller, desired, position)
                }
                if (positionMs != null) controller.seekTo(positionMs)
                else if (controller.playbackState == Player.STATE_ENDED) controller.seekTo(0)
                controller.prepare(); controller.play(); return@withContext
            }
            val queue = (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId }.filter { it in all }
            val ids = dev.distillpod.android.auto.AutoPlayback.selectionIds(id, queue)
            val index = ids.indexOf(id)
            controller.setMediaItems(ids.map { mediaItem(all.getValue(it), podcasts[all.getValue(it).podcastId]) }, index,
                positionMs ?: ep.positionMs.takeUnless { ep.completed } ?: 0)
            controller.prepare(); controller.play()
        }
    }
    suspend fun toggle() { val c = controller(); withContext(Dispatchers.Main) { if (c.isPlaying) c.pause() else { c.prepare(); c.play() } } }
    suspend fun pause() { val c = controller(); withContext(Dispatchers.Main) { c.pause() } }
    suspend fun seek(positionMs: Long) { val c = controller(); withContext(Dispatchers.Main) { c.seekTo(positionMs.coerceAtLeast(0)) } }
    suspend fun skip(delta: Long) { val c = controller(); withContext(Dispatchers.Main) { c.seekTo((c.currentPosition + delta).coerceAtLeast(0)) } }
    suspend fun speed(value: Float) { require(value in 0.5f..3f); val c = controller(); withContext(Dispatchers.Main) { c.setPlaybackSpeed(value) } }
    suspend fun enqueue(id: String, next: Boolean = false) {
        val all = db.library().episodes().associateBy { it.id }
        require(id in all)
        val podcasts = db.library().podcasts().associateBy { it.id }
        val c = controller()
        withContext(Dispatchers.Main) {
            val current = c.currentMediaItem?.mediaId
            if (current == id) return@withContext
            val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.filter { it in all && it != id }.toMutableList()
            val at = if (next && current in ids) ids.indexOf(current) + 1 else ids.size
            ids.add(at, id)
            val index = ids.indexOf(current).coerceAtLeast(0)
            val position = if (current in ids) c.currentPosition.coerceAtLeast(0) else 0L
            c.setMediaItems(ids.map { preserveVideoItem(all.getValue(it), podcasts[all.getValue(it).podcastId], c.currentMediaItem) }, index, position)
            c.prepare()
        }
    }
    suspend fun remove(id: String) { val c = controller(); withContext(Dispatchers.Main) { val at = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == id }; if (at != null) c.removeMediaItem(at) } }
    suspend fun move(id: String, offset: Int) {
        if (offset != -1 && offset != 1) return
        val c = controller(); withContext(Dispatchers.Main) {
        val at = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == id } ?: return@withContext
        val to = at + offset; if (to in 0 until c.mediaItemCount) c.moveMediaItem(at, to)
    } }
    suspend fun clear() { val c = controller(); withContext(Dispatchers.Main) { clearPlaybackQueue(c) } }
    suspend fun synchronizeRestoredQueue() {
        val episodes = db.library().episodes().associateBy { it.id }
        val podcasts = db.library().podcasts().associateBy { it.id }
        val restored = db.library().queue().map { it.episodeId }.filter { it in episodes }
        val saved = db.library().playerSession()
        val c = controller()
        withContext(Dispatchers.Main) {
            if (c.mediaItemCount == 0 && restored.isNotEmpty()) {
                val index = restored.indexOf(saved?.episodeId).coerceAtLeast(0)
                c.pause()
                c.setMediaItems(restored.map { mediaItem(episodes.getValue(it), podcasts[episodes.getValue(it).podcastId]) }, index, episodes.getValue(restored[index]).positionMs)
                c.setPlaybackSpeed(saved?.speed ?: 1f)
            } else {
                val existing = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.toSet()
                restored.filter { it !in existing }.forEach { c.addMediaItem(mediaItem(episodes.getValue(it), podcasts[episodes.getValue(it).podcastId])) }
            }
        }
    }
    suspend fun acceptCloudQueue(imported:(List<String>)->Unit) {
        val all=db.library().episodes().associateBy {it.id};val podcasts=db.library().podcasts().associateBy {it.id}
        val desired=db.library().queue().map {it.episodeId}.filter {it in all}
        val c=controller()
        withContext(Dispatchers.Main) {
            val current=c.currentMediaItem?.mediaId
            val ids=if(current!=null && current in all)listOf(current)+desired.filter {it!=current} else desired
            if(ids.isEmpty() || ids==(0 until c.mediaItemCount).map {c.getMediaItemAt(it).mediaId})return@withContext
            val index=ids.indexOf(current).coerceAtLeast(0)
            val position=if(current in ids)c.currentPosition.coerceAtLeast(0) else all.getValue(ids[index]).positionMs
            val playWhenReady=c.playWhenReady
            imported(ids)
            c.setMediaItems(ids.map {preserveVideoItem(all.getValue(it),podcasts[all.getValue(it).podcastId],c.currentMediaItem)},index,position)
            c.prepare();c.playWhenReady=playWhenReady
        }
    }
    suspend fun carPlaybackToken(): android.support.v4.media.session.MediaSessionCompat.Token {
        val c = controller()
        val future = withContext(Dispatchers.Main) {
            c.sendCustomCommand(dev.distillpod.android.voice.CarVoiceEntry.TOKEN, android.os.Bundle.EMPTY)
        }
        val result = withContext(Dispatchers.IO) { future.get(10, TimeUnit.SECONDS) }
        check(result.resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS)
        @Suppress("DEPRECATION")
        val token = result.extras.getParcelable<android.media.session.MediaSession.Token>(dev.distillpod.android.voice.CarVoiceEntry.TOKEN_EXTRA)
            ?: error("Car playback token unavailable")
        return android.support.v4.media.session.MediaSessionCompat.Token.fromToken(token)
    }
    suspend fun pauseForVoice(changed: () -> Unit, rewindMs: Long = 0): dev.distillpod.android.voice.VoiceLease {
        val c = controller()
        return withContext(Dispatchers.Main) {
            val id = c.currentMediaItem?.mediaId ?: throw dev.distillpod.android.voice.VoiceContextUnavailable(
                dev.distillpod.android.voice.VoiceReadiness.EPISODE_UNAVAILABLE)
            val position = c.currentPosition.coerceAtLeast(0)
            val wasPlaying = c.playWhenReady
            c.pause()
            var valid = true
            val listener = object : Player.Listener {
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) { if (item?.mediaId != id) { valid = false; changed() } }
                @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
                override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                    if (voicePositionChanged(reason, old.mediaItem?.mediaId, new.mediaItem?.mediaId, old.positionMs, new.positionMs)) { valid = false; changed() }
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { valid = false; changed() }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { valid = false; changed() }
            }
            c.addListener(listener)
            object : dev.distillpod.android.voice.VoiceLease {
                override val episode = id
                override val position = position
                override suspend fun finish(resume: Boolean) = finish(resume, true)
                override suspend fun finish(resume: Boolean, allowRewind: Boolean) = withContext(Dispatchers.Main) {
                    c.removeListener(listener)
                    if (resume && valid && wasPlaying && c.currentMediaItem?.mediaId == id) {
                        if (allowRewind && rewindMs > 0) c.seekTo((position - rewindMs.coerceAtMost(3000)).coerceAtLeast(0))
                        c.play()
                    }
                }
            }
        }
    }
    /** Null is an expected readiness condition; controller failures still propagate. */
    suspend fun voicePosition(): Pair<String, Long>? {
        val c = controller()
        return withContext(Dispatchers.Main) {
            c.currentMediaItem?.mediaId?.let { it to c.currentPosition.coerceAtLeast(0) }
        }
    }
    suspend fun capture(): Pair<String, Long> { val c = controller(); return withContext(Dispatchers.Main) { (c.currentMediaItem?.mediaId ?: error("Play an episode first")) to c.currentPosition.coerceAtLeast(0) } }
    suspend fun captureSource(): Triple<String, Long, String?> { val c = controller(); return withContext(Dispatchers.Main) {
        Triple(c.currentMediaItem?.mediaId ?: error("Play an episode first"), c.currentPosition.coerceAtLeast(0), c.currentMediaItem?.localConfiguration?.uri?.takeIf { it.scheme == "file" }?.path)
    } }
}
