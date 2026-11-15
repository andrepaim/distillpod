package dev.distillpod.android.data

import android.net.Uri
import dev.distillpod.core.YouTubeUrl

/** The playing media as capture sees it: a local path for files, otherwise the URI. */
internal fun playingSource(uri: Uri?): String? = uri?.let { if (it.scheme == "file") it.path else it.toString() }

private val videoMarkers = listOf("?distillpod_video=1", "&distillpod_video=1")

/**
 * Whether a capture may carry the episode's current media identity (#124). Source review exists
 * because the tapped audio could differ from the audio the transcript was made from. The player is
 * on the episode's own media when it plays the downloaded file, or streams the episode's current
 * enclosure (for YouTube episodes, the watch URL, also in video mode). The capture then takes the
 * episode's current fingerprint: the file hash after download, or the provisional enclosure identity
 * before it (#245). Preparation still requires the transcript to carry that same fingerprint.
 * Anything else (another file, a stale or different URL, an unknown URI) remains unverified.
 */
internal fun capturedFromOwnSource(ep: EpisodeEntity, playing: String?): Boolean {
    if (playing.isNullOrBlank() || ep.mediaFingerprint.isBlank()) return false
    if (playing == ep.localFile) return true
    if (ep.audioUrl.isBlank()) return false
    val marker = videoMarkers.firstOrNull { playing.endsWith(it) }
    val remote = if (marker == null) playing else playing.removeSuffix(marker).takeIf { YouTubeUrl.videoId(it) != null } ?: return false
    return remote == ep.audioUrl
}
