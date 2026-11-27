package dev.distillpod.android.video

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dev.distillpod.core.YouTubeUrl

/** Resolve on Media3's loader thread, including after process death and expired CDN links. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class YouTubeMediaSources(youtube: YouTubeSource) {
    private val network = DefaultHttpDataSource.Factory().setConnectTimeoutMs(20_000).setReadTimeoutMs(30_000)
    private val progressive = ProgressiveMediaSource.Factory(DataSource.Factory { RefreshingYouTubeDataSource(youtube, network) })

    fun create(mediaItem: MediaItem): MediaSource {
        val uri = requireNotNull(mediaItem.localConfiguration?.uri) { "Missing YouTube URI" }
        val id = requireNotNull(YouTubeUrl.videoId(uri.toString())) { "Invalid YouTube episode" }
        // Media3 exposes the first child's item through the merged timeline. Keep the
        // public URI and video marker so download/progress observers retain video mode.
        val audio = progressive.createMediaSource(mediaItem)
        if (uri.getQueryParameter("distillpod_video") != "1") return audio
        val video = progressive.createMediaSource(mediaItem.buildUpon().setUri(
            Uri.parse(YouTubeUrl.watch(id)).buildUpon().appendQueryParameter("distillpod_track", "video").build()).build())
        return MergingMediaSource(true, audio, video)
    }
}
