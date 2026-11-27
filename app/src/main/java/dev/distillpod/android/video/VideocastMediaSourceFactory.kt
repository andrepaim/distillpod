package dev.distillpod.android.video

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import dev.distillpod.android.data.AudioFileOwnership
import dev.distillpod.android.playback.AudioPlaybackSelection
import dev.distillpod.android.playback.OwnedAudioDataSource
import dev.distillpod.core.YouTubeUrl
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Owned downloads, ordinary enclosures and YouTube watch URIs. Without [youtube] (a build without YouTube
 * sources) a YouTube item fails when it loads, without any request.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VideocastMediaSourceFactory(context: Context, private val youtube: YouTubeSupport?,
    private val ownership: AudioFileOwnership,
    private val localData: DataSource.Factory = DefaultDataSource.Factory(context),
) : MediaSource.Factory {
    private val normal = DefaultMediaSourceFactory(context)
    private var drm: DrmSessionManagerProvider? = null
    private var loadErrors: LoadErrorHandlingPolicy? = null
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val selected = mediaItem.localConfiguration?.tag as? AudioPlaybackSelection
        if (selected != null) {
            val factory = DefaultMediaSourceFactory(DataSource.Factory {
                OwnedAudioDataSource(selected, ownership, localData.createDataSource())
            })
            drm?.let { factory.setDrmSessionManagerProvider(it) }
            loadErrors?.let { factory.setLoadErrorHandlingPolicy(it) }
            return factory.createMediaSource(mediaItem)
        }
        val uri = mediaItem.localConfiguration?.uri ?: return normal.createMediaSource(mediaItem)
        if (YouTubeUrl.videoId(uri.toString()) == null) return normal.createMediaSource(mediaItem)
        return youtube?.mediaSource(mediaItem) ?: ProgressiveMediaSource.Factory { UnavailableYouTubeData }.createMediaSource(mediaItem)
    }
    override fun getSupportedTypes(): IntArray = normal.supportedTypes
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = apply {
        drm = provider
        normal.setDrmSessionManagerProvider(provider)
    }
    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        loadErrors = policy
        normal.setLoadErrorHandlingPolicy(policy)
    }
}

/**
 * Opens nothing: a YouTube item in a build without YouTube sources fails with an explanation. Media3's
 * default policy never retries a FileNotFoundException.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object UnavailableYouTubeData : DataSource {
    override fun addTransferListener(listener: TransferListener) {}
    override fun open(dataSpec: DataSpec): Long = throw FileNotFoundException(YOUTUBE_UNAVAILABLE)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException(YOUTUBE_UNAVAILABLE)
    override fun getUri(): Uri? = null
    override fun close() {}
}
