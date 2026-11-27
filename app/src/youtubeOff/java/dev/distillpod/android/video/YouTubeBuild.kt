package dev.distillpod.android.video

import android.content.Context
import dev.distillpod.android.diagnostics.Diagnostics
import okhttp3.OkHttpClient

/**
 * This build has no YouTube sources (distillpodYoutubeEnabled=false, the Play preset): no extractor and no
 * YouTube requests. See docs/android/videocasts.md.
 */
internal object YouTubeBuild {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, http: OkHttpClient, diagnostics: Diagnostics): YouTubeSupport? = null
}
