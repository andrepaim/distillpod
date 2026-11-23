package dev.distillpod.android

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import dev.distillpod.android.playback.playbackSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackRetryStateTest {
    private class RetryPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var current = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(listOf(MediaItemData.Builder("lecture").setMediaItem(
                MediaItem.Builder().setMediaId("lecture").build()).build()))
            .build()

        override fun getState() = current

        fun update(state: Int, failure: PlaybackException? = null) {
            current = current.buildUpon().setPlayerError(failure).setPlaybackState(state)
                .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
            invalidateState()
        }
    }

    @Test fun sameEpisodeRetryClearsStoppedMessageAsSoonAsBufferingStarts() {
        val player = RetryPlayer()
        player.update(Player.STATE_IDLE, PlaybackException("test failure", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        val stopped = playbackSnapshot(player)
        assertEquals("lecture", stopped.episodeId)
        assertNotNull(stopped.error)
        assertFalse(stopped.loading)

        player.update(Player.STATE_BUFFERING)
        val retry = playbackSnapshot(player)
        assertEquals(stopped.episodeId, retry.episodeId)
        assertNull(retry.error)
        assertTrue(retry.loading)
    }
}
