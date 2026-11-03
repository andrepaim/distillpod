package dev.distillpod.android

import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupArchiveTest {
    @Test fun `history is bounded dated and excludes unsafe or corrupted entries`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val folder = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val entries = (1..12).reversed().map { index ->
                val file = File(folder, "$index.json").apply { writeText("synthetic snapshot $index") }
                BackupArchive.snapshot(file, BackupPreview("", mapOf("episodes" to index), 0, createdAt = index.toLong(), appVersion = "test"))
            }
            val archive = File(folder, "history.zip")
            assertEquals(10, BackupArchive.write(archive, entries).size)
            val read = BackupArchive.read(archive, File(folder, "read"))
            assertEquals((3..12).reversed().map(Int::toLong), read.map { it.record.at })
            assertEquals(entries.first().file.readText(), read.first().file.readText())
            val corrupt = File(folder, "corrupt.zip")
            java.util.zip.ZipFile(archive).use { source -> ZipOutputStream(corrupt.outputStream()).use { zip ->
                source.entries().asSequence().forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == read.first().record.entry) zip.write("changed".toByteArray())
                    else source.getInputStream(entry).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            } }
            assertTrue(runCatching { BackupArchive.read(corrupt, File(folder, "bad")) }.isFailure)
            val unsafe = File(folder, "unsafe.zip")
            ZipOutputStream(unsafe.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("../escaped.json")); zip.write("unsafe".toByteArray()); zip.closeEntry()
            }
            assertTrue(runCatching { BackupArchive.read(unsafe, File(folder, "unsafe")) }.isFailure)
            assertFalse(File(folder, "escaped.json").exists())
        } finally { folder.deleteRecursively() }
    }
}
