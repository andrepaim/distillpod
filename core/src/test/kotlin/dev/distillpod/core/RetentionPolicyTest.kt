package dev.distillpod.core

import org.junit.Assert.*
import org.junit.Test

class RetentionPolicyTest {
    private val day = 86_400_000L
    private val origin = 1_000L

    @Test fun `all rules default off even for old content`() {
        assertFalse(RetentionPolicy().audioExpired(origin, origin, Long.MAX_VALUE))
        assertFalse(RetentionPolicy().transcriptExpired(origin, Long.MAX_VALUE))
    }
    @Test fun `never played audio uses only independently enabled audio age`() {
        val p = RetentionPolicy(audioDays = 7, completedAudioDays = 1, transcriptDays = 1)
        assertFalse(p.audioExpired(origin, null, origin + 7 * day - 1))
        assertTrue(p.audioExpired(origin, null, origin + 7 * day))
        assertTrue(p.audioExpired(origin, null, origin + 7 * day + 1))
        assertTrue(p.transcriptExpired(origin, origin + day))
    }
    @Test fun `earliest enabled audio deadline wins without affecting transcript`() {
        val p = RetentionPolicy(audioDays = 30, completedAudioDays = 1)
        assertFalse(p.audioExpired(origin, origin + day, origin + 2 * day - 1))
        assertTrue(p.audioExpired(origin, origin + day, origin + 2 * day))
        assertFalse(p.transcriptExpired(origin, Long.MAX_VALUE))
        assertTrue(p.audioExpired(origin, origin + 29 * day, origin + 30 * day))
    }
    @Test fun `missing invalid and future origins fail closed`() {
        val p = RetentionPolicy(audioDays = 1, completedAudioDays = 1, transcriptDays = 1)
        for (bad in listOf(null, 0L, -1L, Long.MAX_VALUE)) {
            assertFalse(p.audioExpired(bad, bad, origin + day))
            assertFalse(p.transcriptExpired(bad, origin + day))
        }
        assertFalse(p.transcriptExpired(origin, -1))
    }
    @Test fun `expiry calculation does not overflow near maximum time`() {
        val p = RetentionPolicy(audioDays = 90)
        assertFalse(p.audioExpired(Long.MAX_VALUE - day, null, Long.MAX_VALUE))
        assertTrue(p.audioExpired(1, null, Long.MAX_VALUE))
    }
    @Test fun `policy changes apply without cached eligibility`() {
        val p = RetentionPolicy(audioDays = 30)
        val now = origin + 7 * day
        assertFalse(p.audioExpired(origin, null, now))
        assertTrue(p.copy(audioDays = 7).audioExpired(origin, null, now))
        assertFalse(p.copy(audioDays = null).audioExpired(origin, null, now))
        assertFalse(p.copy(transcriptDays = 1).audioExpired(origin, null, now))
    }
    @Test fun `new copy resets age while absent completion cannot expire it`() {
        val p = RetentionPolicy(audioDays = 7, completedAudioDays = 1)
        assertTrue(p.audioExpired(origin, null, origin + 8 * day))
        assertFalse(p.audioExpired(origin + 8 * day, null, origin + 8 * day))
    }
    @Test fun `durations must be explicit supported choices`() {
        for (days in listOf(-1, 0, 2, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { RetentionPolicy(audioDays = days) }
            assertThrows(IllegalArgumentException::class.java) { RetentionPolicy(completedAudioDays = days) }
            assertThrows(IllegalArgumentException::class.java) { RetentionPolicy(transcriptDays = days) }
        }
        for (days in listOf(1, 7, 30, 90)) RetentionPolicy(days, days, days)
    }
}
