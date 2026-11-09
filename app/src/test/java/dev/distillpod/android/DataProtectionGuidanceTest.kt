package dev.distillpod.android

import dev.distillpod.android.features.DataProtectionGuidance
import org.junit.Assert.*
import org.junit.Test

class DataProtectionGuidanceTest {
    @Test fun `content transfer requires configured sync and content plus expanded scope`() {
        for (configured in listOf(false, true)) for (content in listOf(false, true)) for (expanded in listOf(false, true)) {
            val text = DataProtectionGuidance.sync(configured, content, expanded)
            assertEquals(configured && content && expanded, text.contains("Eligible finished transcripts"))
            if (!configured) assertTrue(text.contains("unavailable"))
            if (configured && !expanded) assertTrue(text.contains("optional"))
        }
    }
    @Test fun `backup age handles empty old recent and future timestamps honestly`() {
        val now = 10L * 86_400_000
        assertEquals("No backup history loaded.", DataProtectionGuidance.backupAge(null, now))
        assertEquals("Backup date unavailable.", DataProtectionGuidance.backupAge(0, now))
        assertEquals("Latest available backup: less than a minute old.", DataProtectionGuidance.backupAge(now - 1, now))
        assertEquals("Latest available backup: 1 minute old.", DataProtectionGuidance.backupAge(now - 60_000, now))
        assertEquals("Latest available backup: 2 hours old.", DataProtectionGuidance.backupAge(now - 7_200_000, now))
        assertEquals("Latest available backup: 3 days old.", DataProtectionGuidance.backupAge(now - 259_200_000, now))
        assertEquals("Backup date is ahead of this phone's clock.", DataProtectionGuidance.backupAge(now + 1, now))
    }
}
