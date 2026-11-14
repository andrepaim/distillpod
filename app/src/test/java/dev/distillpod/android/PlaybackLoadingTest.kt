package dev.distillpod.android

import androidx.media3.common.Player
import dev.distillpod.android.playback.PlaybackState
import dev.distillpod.android.playback.playbackLoading
import org.junit.Assert.*
import org.junit.Test

/** A requested start that is still buffering counts as a play request, so the controls do not look idle. */
class PlaybackLoadingTest {
    private val none = Player.PLAYBACK_SUPPRESSION_REASON_NONE

    @Test fun bufferingWithPlayIntentIsLoading() {
        assertTrue(playbackLoading(true, Player.STATE_BUFFERING, none))
    }

    @Test fun pausedReadyEndedOrSuppressedIsNotLoading() {
        assertFalse(playbackLoading(false, Player.STATE_BUFFERING, none))
        assertFalse(playbackLoading(true, Player.STATE_READY, none))
        assertFalse(playbackLoading(true, Player.STATE_IDLE, none))
        assertFalse(playbackLoading(true, Player.STATE_ENDED, none))
        assertFalse(playbackLoading(true, Player.STATE_BUFFERING, Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS))
    }

    @Test fun loadingAndPlayingAreBothPlayRequests() {
        assertFalse(PlaybackState().playRequested)
        assertTrue(PlaybackState(loading = true).playRequested)
        assertTrue(PlaybackState(playing = true).playRequested)
    }
}
