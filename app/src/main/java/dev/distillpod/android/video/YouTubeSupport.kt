package dev.distillpod.android.video

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MediaSource
import dev.distillpod.android.data.PodcastCandidate
import dev.distillpod.core.Feed
import dev.distillpod.core.YouTubeUrl
import dev.distillpod.core.YouTubeVideoMetadata

/**
 * YouTube sources. Only a build with the YouTube switch on (the default, see docs/android/videocasts.md)
 * contains an implementation, YouTubeSource in src/youtubeOn. Without it the app graph holds null: the app
 * offers no way to add a YouTube source, and YouTube shows restored from a backup stay in the library
 * unchanged, never refreshed, downloaded, played or transcribed (#319).
 */
interface YouTubeSupport {
    suspend fun search(query: String): List<PodcastCandidate>
    suspend fun feed(id: String, raw: String): Feed
    /** Full single-video metadata for an episode outside the public feed window. */
    fun details(raw: String): YouTubeVideoMetadata
    fun streams(raw: String): YouTubeStreams
    /** The Media3 source of a YouTube watch URI; streams are resolved on the loader thread. */
    fun mediaSource(item: MediaItem): MediaSource
}

data class YouTubeStreams(val audio: String, val video: String?)

// Some audio CDN responses reject 5 MiB ranges even when 1 MiB ranges succeed.
internal const val YOUTUBE_RANGE_BYTES = 1024L * 1024

/** A pasted or imported YouTube address in a build without YouTube sources. */
const val YOUTUBE_LINK_UNSUPPORTED = "YouTube links are not supported in this version of DistillPod."
/** A YouTube show or episode already in the library of a build without YouTube sources. */
const val YOUTUBE_UNAVAILABLE = "YouTube videos are not available in this version of DistillPod. They stay in your library unchanged."

/** Thrown before anything is read from the network or written to the library. */
class YouTubeUnavailableException(message: String = YOUTUBE_UNAVAILABLE) : IllegalStateException(message)

/** True for a YouTube episode that a build without YouTube sources must leave alone. */
internal fun YouTubeSupport?.cannotPlay(audioUrl: String) = this == null && YouTubeUrl.videoId(audioUrl) != null
/** True for a YouTube show that a build without YouTube sources must leave alone. */
internal fun YouTubeSupport?.cannotLoad(feedUrl: String) = this == null && YouTubeUrl.source(feedUrl) != null

/** Only a YouTube episode without a playable download resolves a stream before it plays (reads the file system). */
internal fun streamNeedsResolving(audioUrl: String, localFile: String?): Boolean =
    YouTubeUrl.videoId(audioUrl) != null && localFile?.let { java.io.File(it).isFile } != true
