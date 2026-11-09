package dev.distillpod.android

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.playback.clearPlaybackQueue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ClearPlaybackQueueTest {
    @Test fun `clear stops play intent and later additions remain paused`() {
        val player=ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        try {
            val item=MediaItem.fromUri("https://example.test/audio")
            player.setMediaItem(item)
            player.playWhenReady=true
            clearPlaybackQueue(player)
            assertEquals(0,player.mediaItemCount)
            assertFalse(player.playWhenReady)
            player.addMediaItem(item)
            assertFalse(player.playWhenReady)
            clearPlaybackQueue(player)
            clearPlaybackQueue(player)
            assertEquals(0,player.mediaItemCount)
            assertFalse(player.playWhenReady)
        } finally { player.release() }
    }
}
