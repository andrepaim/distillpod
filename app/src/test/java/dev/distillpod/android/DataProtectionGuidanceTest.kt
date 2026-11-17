package dev.distillpod.android

import dev.distillpod.android.data.ManagedBackups
import dev.distillpod.android.features.DataProtectionGuidance
import dev.distillpod.android.features.settingsSyncValue
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
    @Test fun `backup line reports off, failure, age and an unknown time`() {
        val now = 10L * 86_400_000
        assertEquals("Off", DataProtectionGuidance.backupLine(false, ManagedBackups.Status(savedAt = now), now))
        assertEquals("Last backup failed", DataProtectionGuidance.backupLine(true, ManagedBackups.Status(savedAt = now - 1, error = "Access lost"), now))
        assertEquals("Backed up just now", DataProtectionGuidance.backupLine(true, ManagedBackups.Status(savedAt = now - 1), now))
        assertEquals("Backed up 3 h ago", DataProtectionGuidance.backupLine(true, ManagedBackups.Status(savedAt = now - 3 * 3_600_000), now))
        assertEquals("Backed up 2 d ago", DataProtectionGuidance.backupLine(true, ManagedBackups.Status(savedAt = now - 2 * 86_400_000), now))
        assertEquals("On", DataProtectionGuidance.backupLine(true, ManagedBackups.Status(), now))
        assertEquals("On", DataProtectionGuidance.backupLine(true, ManagedBackups.Status(savedAt = now + 1), now))
    }
    @Test fun `settings row shows a failed backup first, then bound sync, then the backup`() {
        assertEquals("Needs attention", settingsSyncValue(true, true, "Synced 2 min ago", "Last backup failed"))
        assertEquals("Needs attention", settingsSyncValue(false, false, "Unavailable", "Last backup failed"))
        assertEquals("Synced 2 min ago", settingsSyncValue(true, true, "Synced 2 min ago", "Backed up 3 h ago"))
        assertEquals("Backed up 3 h ago", settingsSyncValue(true, false, "Off", "Backed up 3 h ago"))
        assertEquals("Backed up 3 h ago", settingsSyncValue(false, false, "Unavailable", "Backed up 3 h ago"))
        assertEquals("Off", settingsSyncValue(false, false, "Unavailable", "Off"))
    }
}
