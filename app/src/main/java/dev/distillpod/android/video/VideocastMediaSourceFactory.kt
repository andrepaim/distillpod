package dev.distillpod.android.video

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import dev.distillpod.core.YouTubeUrl
import java.io.IOException

/** Resolve on Media3's loader thread, including after process death and expired CDN links. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideocastMediaSourceFactory(context: Context, youtube: YouTubeSource) : MediaSource.Factory {
    private val network = DefaultHttpDataSource.Factory().setConnectTimeoutMs(20_000).setReadTimeoutMs(30_000)
    private val normal = DefaultMediaSourceFactory(context)
    private val youtubeData = ResolvingDataSource.Factory(DataSource.Factory {
        BoundedRangeDataSource(network.createDataSource())
    }) { spec ->
        if (YouTubeUrl.videoId(spec.uri.toString()) == null) spec else {
            val streams = youtube.streams(spec.uri.toString())
            val url = if (spec.uri.getQueryParameter("distillpod_track") == "video")
                streams.video ?: throw IOException("No compatible YouTube video stream is available") else streams.audio
            spec.withUri(Uri.parse(url))
        }
    }
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri ?: return normal.createMediaSource(mediaItem)
        val id = YouTubeUrl.videoId(uri.toString()) ?: return normal.createMediaSource(mediaItem)
        val progressive = ProgressiveMediaSource.Factory(youtubeData)
        // Media3 exposes the first child's item through the merged timeline. Keep the
        // public URI and video marker so download/progress observers retain video mode.
        val audio = progressive.createMediaSource(mediaItem)
        if (uri.getQueryParameter("distillpod_video") != "1") return audio
        val video = progressive.createMediaSource(mediaItem.buildUpon().setUri(
            Uri.parse(YouTubeUrl.watch(id)).buildUpon().appendQueryParameter("distillpod_track", "video").build()).build())
        return MergingMediaSource(true, audio, video)
    }
    override fun getSupportedTypes(): IntArray = normal.supportedTypes
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = apply {
        normal.setDrmSessionManagerProvider(provider)
    }
    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        normal.setLoadErrorHandlingPolicy(policy)
    }
}
