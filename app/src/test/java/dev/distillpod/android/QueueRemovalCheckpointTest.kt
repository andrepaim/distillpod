package dev.distillpod.android

import androidx.media3.common.Player
import dev.distillpod.android.playback.shouldCheckpointDeparture
import org.junit.Assert.*
import org.junit.Test

class QueueRemovalCheckpointTest {
    @Test fun `clear and removal with successor checkpoint the removed current only`() {
        assertTrue(shouldCheckpointDeparture(Player.DISCONTINUITY_REASON_REMOVE, "current", null))
        assertTrue(shouldCheckpointDeparture(Player.DISCONTINUITY_REASON_REMOVE, "current", "next"))
        assertFalse(shouldCheckpointDeparture(Player.DISCONTINUITY_REASON_REMOVE, null, null))
    }
    @Test fun `manual next, previous and skipped items checkpoint the departed item`() {
        // Periodic progress checkpoints are sparse (#141), so leaving an item records its position.
        assertTrue(shouldCheckpointDeparture(Player.DISCONTINUITY_REASON_SEEK, "current", "next"))
        assertTrue(shouldCheckpointDeparture(Player.DISCONTINUITY_REASON_SKIP, "current", "next"))
    }
    @Test fun `same item source swap and reorder are not removal or completion`() {
        assertFalse(shouldCheckpointDeparture(Player.DISCONTINUITY_REASON_REMOVE, "current", "current"))
        assertFalse(shouldCheckpointDeparture(Player.DISCONTINUITY_REASON_INTERNAL, "current", "current"))
        assertFalse(shouldCheckpointDeparture(Player.DISCONTINUITY_REASON_SEEK, "current", "current"))
        assertFalse(shouldCheckpointDeparture(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, "current", "next"))
    }
}
