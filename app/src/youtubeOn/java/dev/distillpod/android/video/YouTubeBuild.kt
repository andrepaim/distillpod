package dev.distillpod.android.video

import android.content.Context
import dev.distillpod.android.diagnostics.Diagnostics
import okhttp3.OkHttpClient

/** This build has YouTube sources (distillpodYoutubeEnabled, the default). See docs/android/videocasts.md. */
internal object YouTubeBuild {
    fun create(context: Context, http: OkHttpClient, diagnostics: Diagnostics): YouTubeSupport =
        YouTubeSource(AndroidYouTubeExtractor(context) { name, reason, elapsed, operation ->
            diagnostics.event(name, operation = operation, reason = reason, elapsedMs = elapsed)
        }, HttpYouTubeMetadataFetcher(http)).apply { onStreamRefresh = { diagnostics.event("youtube.stream_refreshed", reason = "ready") } }
}
