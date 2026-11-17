package dev.distillpod.android

import dev.distillpod.android.data.BackupArchive
import dev.distillpod.android.work.BackupJobs
import androidx.work.ListenableWorker
import org.junit.Assert.*
import org.junit.Test

class BackupRetentionTest {
    private val day = 24L * 60 * 60 * 1000
    private fun daily(days: Int) = (0 until days).map { 1_000 * day - it * day } // newest first

    @Test fun `ten or fewer snapshots are all kept`() {
        val history = daily(10)
        assertEquals(history, BackupArchive.retain(history, { it }))
    }

    @Test fun `daily backups keep the recent days and one snapshot per older week`() {
        var history = emptyList<Long>()
        // Sixty days of automatic backups, each prepended and trimmed exactly as BackupArchive.write does.
        for (index in 59 downTo 0) history = BackupArchive.retain(listOf(1_000 * day - index * day) + history, { it })
        assertEquals(BackupArchive.KEEP, history.size)
        val ages = history.map { (history.first() - it) / day }
        assertEquals(listOf(0L, 1, 2, 3, 4), ages.take(BackupArchive.RECENT))
        // Older snapshots are spread over weeks instead of being the five days before them.
        assertTrue("Oldest kept snapshot is only ${ages.last()} days old", ages.last() >= 25)
        val weeks = ages.drop(BackupArchive.RECENT).map { it / 7 }
        assertEquals(weeks.distinct(), weeks)
    }

    @Test fun `many backups on one day drop the oldest beyond the recent ones`() {
        val sameDay = (12 downTo 1).map(Int::toLong)
        assertEquals((12 downTo 3).map(Int::toLong), BackupArchive.retain(sameDay, { it }))
    }

    @Test fun `only transient failures retry, and only twice`() {
        assertTrue(BackupJobs.afterFailure(needsUser = false, attempts = 0) is ListenableWorker.Result.Retry)
        assertTrue(BackupJobs.afterFailure(needsUser = false, attempts = 1) is ListenableWorker.Result.Retry)
        assertTrue(BackupJobs.afterFailure(needsUser = false, attempts = 2) is ListenableWorker.Result.Success)
        assertTrue(BackupJobs.afterFailure(needsUser = true, attempts = 0) is ListenableWorker.Result.Success)
    }
}
