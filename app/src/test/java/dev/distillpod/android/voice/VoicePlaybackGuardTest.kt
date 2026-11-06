package dev.distillpod.android.voice

import androidx.media3.common.Player
import dev.distillpod.android.playback.voicePositionChanged
import org.junit.Assert.*
import org.junit.Test

class VoicePlaybackGuardTest {
    @Test fun `initial no-op controller transition does not revoke the pause lease`() {
        assertFalse(voicePositionChanged(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, null, null, 0, 0))
        assertFalse(voicePositionChanged(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, "ep", "ep", 1000, 1000))
    }
    @Test fun `seeks episode changes and actual auto transitions revoke the pause lease`() {
        assertTrue(voicePositionChanged(Player.DISCONTINUITY_REASON_SEEK, "ep", "ep", 1000, 1000))
        assertTrue(voicePositionChanged(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, "ep", "next", 0, 0))
        assertTrue(voicePositionChanged(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, "ep", "ep", 1000, 0))
    }
}
