package dev.distillpod.android.features

/** Presentation only: changing this state must never replace the media item or playback session. */
internal data class VideoPresentation(
    val fullscreen: Boolean = false,
    val dismissedLandscape: Boolean = false,
    val forcedLandscape: Boolean = false,
    val sawLandscape: Boolean = false,
) {
    fun enter() = copy(fullscreen = true, dismissedLandscape = false, forcedLandscape = true, sawLandscape = false)
    fun exit() = VideoPresentation(dismissedLandscape = true)
    fun orientation(landscape: Boolean, autoRotate: Boolean): VideoPresentation {
        if (!autoRotate) return this
        if (forcedLandscape && !sawLandscape && !landscape) return this
        if (!landscape) return VideoPresentation()
        if (dismissedLandscape) return this
        return copy(fullscreen = true, forcedLandscape = false, sawLandscape = true)
    }
}
