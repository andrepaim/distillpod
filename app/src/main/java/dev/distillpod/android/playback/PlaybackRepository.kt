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
}
data class SkippedMoment(val episodeId: String, val positionMs: Long, val endMs: Long)

fun mediaItem(ep: EpisodeEntity, podcast: PodcastEntity?): MediaItem {
    val file = ep.localFile?.let(::File)?.takeIf { it.isFile }
    return MediaItem.Builder().setMediaId(ep.id).setUri(file?.let(Uri::fromFile) ?: Uri.parse(ep.audioUrl))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(ep.title).setArtist(podcast?.title ?: "DistillPod")
            .setArtworkUri((ep.imageUrl ?: podcast?.imageUrl)?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(Uri::parse)).build()).build()
}

class PlaybackRepository(context: Context, private val db: AppDatabase, private val scope: CoroutineScope) {
    private val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    private val mutable = MutableStateFlow(PlaybackState())
    val state = mutable.asStateFlow()
    init {
        future.addListener({
            runCatching { future.get() }.onSuccess { controller ->
                controller.addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { mutable.update { it.copy(error = "Playback stopped. Check your connection or download this episode.") } }
                    override fun onMediaItemTransition(item: MediaItem?, reason: Int) { mutable.update { it.copy(error = null) } }
                })
                scope.launch {
                    while (isActive) {
                        mutable.value = PlaybackState(controller.currentMediaItem?.mediaId, controller.mediaMetadata.title?.toString() ?: "",
                            controller.currentPosition.coerceAtLeast(0), controller.duration.coerceAtLeast(0), controller.isPlaying,
                            controller.playbackParameters.speed, (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId }, mutable.value.error)
                        delay(500)
                    }
                }
            }.onFailure { mutable.update { it.copy(error = "The player could not connect. Reopen DistillPod.") } }
        }, ContextCompat.getMainExecutor(context))
    }
    private suspend fun controller() = withContext(Dispatchers.IO) { future.get(10, TimeUnit.SECONDS) }
    suspend fun play(id: String, positionMs: Long? = null) {
        val ep = db.library().episode(id) ?: error("Episode is unavailable")
        val controller = controller()
        val all = db.library().episodes().associateBy { it.id }
        val podcasts = db.library().podcasts().associateBy { it.id }
        withContext(Dispatchers.Main) {
            if (controller.currentMediaItem?.mediaId == id) {
                val desired = mediaItem(ep, podcasts[ep.podcastId])
                if (controller.currentMediaItem?.localConfiguration?.uri != desired.localConfiguration?.uri) {
                    val position = positionMs ?: controller.currentPosition.coerceAtLeast(0)
                    controller.replaceMediaItem(controller.currentMediaItemIndex, desired)
                    controller.seekTo(position)
                }
                if (positionMs != null) controller.seekTo(positionMs)
                else if (controller.playbackState == Player.STATE_ENDED) controller.seekTo(0)
                controller.prepare(); controller.play(); return@withContext
            }
            val queue = (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId }.filter { it in all }
            val ids = if (id in queue) queue else listOf(id) + queue
            val index = ids.indexOf(id)
            controller.setMediaItems(ids.map { mediaItem(all.getValue(it), podcasts[all.getValue(it).podcastId]) }, index,
                positionMs ?: ep.positionMs.takeUnless { ep.completed && ep.durationMs > 0 && it >= ep.durationMs - 3000 } ?: 0)
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
            c.setMediaItems(ids.map { mediaItem(all.getValue(it), podcasts[all.getValue(it).podcastId]) }, index, position)
            c.prepare()
        }
    }
    suspend fun remove(id: String) { val c = controller(); withContext(Dispatchers.Main) { val at = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == id }; if (at != null) c.removeMediaItem(at) } }
    suspend fun move(id: String, offset: Int) { val c = controller(); withContext(Dispatchers.Main) {
        val at = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == id } ?: return@withContext
        val to = at + offset; if (to in 0 until c.mediaItemCount) c.moveMediaItem(at, to)
    } }
    suspend fun clear() { val c = controller(); withContext(Dispatchers.Main) { c.clearMediaItems() } }
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
    suspend fun capture(): Pair<String, Long> { val c = controller(); return withContext(Dispatchers.Main) { (c.currentMediaItem?.mediaId ?: error("Play an episode first")) to c.currentPosition.coerceAtLeast(0) } }
    suspend fun captureSource(): Triple<String, Long, String?> { val c = controller(); return withContext(Dispatchers.Main) {
        Triple(c.currentMediaItem?.mediaId ?: error("Play an episode first"), c.currentPosition.coerceAtLeast(0), c.currentMediaItem?.localConfiguration?.uri?.takeIf { it.scheme == "file" }?.path)
    } }
}
