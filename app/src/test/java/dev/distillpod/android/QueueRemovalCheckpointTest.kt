package dev.distillpod.android

import androidx.media3.common.Player
import dev.distillpod.android.playback.shouldCheckpointRemoval
import org.junit.Assert.*
import org.junit.Test

class QueueRemovalCheckpointTest {
    @Test fun `clear and removal with successor checkpoint the removed current only`() {
        assertTrue(shouldCheckpointRemoval(Player.DISCONTINUITY_REASON_REMOVE, "current", null))
        assertTrue(shouldCheckpointRemoval(Player.DISCONTINUITY_REASON_REMOVE, "current", "next"))
        assertFalse(shouldCheckpointRemoval(Player.DISCONTINUITY_REASON_REMOVE, null, null))
    }
    @Test fun `same item source swap and reorder are not removal or completion`() {
        assertFalse(shouldCheckpointRemoval(Player.DISCONTINUITY_REASON_REMOVE, "current", "current"))
        assertFalse(shouldCheckpointRemoval(Player.DISCONTINUITY_REASON_INTERNAL, "current", "current"))
        assertFalse(shouldCheckpointRemoval(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, "current", "next"))
        assertFalse(shouldCheckpointRemoval(Player.DISCONTINUITY_REASON_SEEK, "current", "next"))
    }
}
