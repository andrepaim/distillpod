package dev.distillpod.android

import android.content.Context
import android.content.Intent
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import dev.distillpod.android.features.BackupFilePicker
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManagedBackupsTest {
    private class Provider(val file: File) : DocumentsProvider() {
        var readsFail = false
        var writesFail = false
        var corruptNextWrite = false
        private var pendingCorruption = false
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?) = MatrixCursor(arrayOf("root_id"))
        override fun queryDocument(id: String, projection: Array<out String>?) = MatrixCursor(arrayOf(Document.COLUMN_DISPLAY_NAME)).apply { addRow(arrayOf("DistillPod-backups.zip")) }
        override fun queryChildDocuments(id: String, projection: Array<out String>?, order: String?) = MatrixCursor(arrayOf(Document.COLUMN_DOCUMENT_ID))
        override fun openDocument(id: String, mode: String, signal: android.os.CancellationSignal?): ParcelFileDescriptor {
            if (mode.contains('w')) {
                if (writesFail) throw IOException("Storage unavailable")
                if (corruptNextWrite) { pendingCorruption = true; corruptNextWrite = false }
            } else {
                if (readsFail) throw SecurityException("Permission revoked")
                if (pendingCorruption) { file.writeText("interrupted write"); pendingCorruption = false }
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }
    }
    private suspend fun done(backups: ManagedBackups) = withTimeout(15000) { backups.state.first { !it.working } }
    private suspend fun fixture(block: suspend (Context, AppDatabase, AppSettings, CoroutineScope, ManagedBackups, Provider, Uri, File, CompletableDeferred<BackupPreview>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.cacheDir, "managed-test-${UUID.randomUUID()}").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = AppSettings(context, PreferenceDataStoreFactory.create(scope = scope) { File(folder, "test.preferences_pb") })
        val provider = Provider(File(folder, "cloud.zip").apply { createNewFile() })
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply {
            authority = "managed.fixture"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        })
        ShadowContentResolver.registerProviderInternal("managed.fixture", provider)
        val uri = Uri.parse("content://managed.fixture/document/${UUID.randomUUID()}")
        context.grantUriPermission(context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val preview = CompletableDeferred<BackupPreview>()
        val backups = ManagedBackups(context, LibraryBackup(db), settings, scope) { preview.complete(it) }
        try {
            db.library().putPodcast(PodcastEntity("show", "https://example.test/feed", "Fixture"))
            db.library().putEpisode(EpisodeEntity("episode", "show", "guid", "Episode", "https://example.test/audio", positionMs = 12000))
            block(context, db, settings, scope, backups, provider, uri, folder, preview)
        } finally { scope.cancel(); db.close(); folder.deleteRecursively() }
    }
    @Test fun `remembered file keeps two dated snapshots and restores an older snapshot without a picker`() = runBlocking {
        fixture { context, db, settings, scope, backups, provider, uri, folder, preview ->
            backups.created(uri); done(backups)
            assertFalse(backups.state.value.message, backups.state.value.error)
            assertEquals(1, backups.state.value.history.size)
            val older = backups.state.value.history.single()
            db.library().putEpisode(db.library().episode("episode")!!.copy(positionMs = 24000))
            backups.backup(); done(backups)
            assertEquals(2, backups.state.value.history.size)
            assertTrue(backups.destinations.tryReceive().isFailure)
            assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission })
            val restarted = ManagedBackups(context, LibraryBackup(db), settings, scope) { preview.complete(it) }
            restarted.refresh(choose = true); done(restarted)
            assertEquals(2, restarted.state.value.history.size)
            assertTrue(restarted.state.value.history.none { it.local })
            assertTrue(restarted.state.value.chooseBackup)
            restarted.preview(older.id, false); done(restarted)
            val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                LibraryBackup(target).merge(withTimeout(10000) { preview.await() })
                assertEquals(12000L, target.library().episode("episode")!!.positionMs)
            } finally { target.close() }
            assertTrue(provider.file.length() > 0)
        }
    }
    @Test fun `truncated cloud update rolls back to a verified previous history`() = runBlocking {
        fixture { _, _, settings, _, backups, provider, uri, folder, _ ->
            backups.created(uri); done(backups)
            val previousHash = BackupArchive.hash(provider.file)
            val connection = settings.value("managedBackup").first()
            provider.corruptNextWrite = true
            backups.backup(); done(backups)
            assertTrue(backups.state.value.error)
            assertEquals(previousHash, BackupArchive.hash(provider.file))
            assertEquals(1, BackupArchive.read(provider.file, File(folder, "check")).size)
            assertEquals(connection, settings.value("managedBackup").first())
        }
    }
    @Test fun `lost Drive access exposes a marked local recovery copy without overwriting anything`() = runBlocking {
        fixture { _, _, _, _, backups, provider, uri, _, preview ->
            backups.created(uri); done(backups)
            val previousHash = BackupArchive.hash(provider.file)
            provider.readsFail = true
            backups.refresh(choose = true); done(backups)
            assertTrue(backups.state.value.error)
            val cached = backups.state.value.history.single()
            assertTrue(cached.local)
            backups.preview(cached.id, true); done(backups)
            assertEquals(1, withTimeout(10000) { preview.await() }.episodes)
            backups.backup(); done(backups)
            assertTrue(backups.state.value.error)
            assertEquals(previousHash, BackupArchive.hash(provider.file))
        }
    }
    @Test fun `invalid existing file never replaces a working connection or library`() = runBlocking {
        fixture { _, db, settings, _, backups, provider, uri, _, _ ->
            backups.created(uri); done(backups)
            val connection = settings.value("managedBackup").first()
            provider.file.writeText("not a backup")
            backups.connect(uri); done(backups)
            assertTrue(backups.state.value.error)
            assertEquals(connection, settings.value("managedBackup").first())
            assertEquals(12000L, db.library().episode("episode")!!.positionMs)
            assertEquals("not a backup", provider.file.readText())
        }
    }
    @Test fun `reconnecting a valid history does not write to its cloud file`() = runBlocking {
        fixture { context, db, _, scope, backups, provider, uri, folder, _ ->
            backups.created(uri); done(backups)
            val hash = BackupArchive.hash(provider.file)
            provider.writesFail = true
            val newSettings = AppSettings(context, PreferenceDataStoreFactory.create(scope = scope) { File(folder, "fresh.preferences_pb") })
            val connected = ManagedBackups(context, LibraryBackup(db), newSettings, scope) {}
            connected.connect(uri); done(connected)
            assertFalse(connected.state.value.message, connected.state.value.error)
            assertEquals(1, connected.state.value.history.size)
            assertEquals(hash, BackupArchive.hash(provider.file))
        }
    }
    @Test fun `damaged cloud history is repaired from recovery and gains a new snapshot`() = runBlocking {
        fixture { _, db, _, _, backups, provider, uri, folder, _ ->
            backups.created(uri); done(backups)
            provider.file.writeText("power loss during cloud update")
            backups.backup(); done(backups)
            assertFalse(backups.state.value.message, backups.state.value.error)
            assertTrue(backups.state.value.message.startsWith("Backup repaired"))
            assertEquals(2, BackupArchive.read(provider.file, File(folder, "repaired")).size)
            assertEquals(12000L, db.library().episode("episode")!!.positionMs)
        }
    }
    @Test fun `forgetting a connection revokes access without deleting cloud or local library data`() = runBlocking {
        fixture { context, db, settings, _, backups, provider, uri, _, _ ->
            backups.created(uri); done(backups)
            val hash = BackupArchive.hash(provider.file)
            backups.disconnect(); done(backups)
            assertEquals("", settings.value("managedBackup").first())
            assertNull(backups.state.value.location)
            assertTrue(context.contentResolver.persistedUriPermissions.none { it.uri == uri })
            assertEquals(hash, BackupArchive.hash(provider.file))
            assertEquals(12000L, db.library().episode("episode")!!.positionMs)
        }
    }
    @Test fun `a future history version is never overwritten with an older recovery copy`() = runBlocking {
        fixture { _, _, _, _, backups, provider, uri, folder, _ ->
            backups.created(uri); done(backups)
            val future = File(folder, "future.zip")
            java.util.zip.ZipFile(provider.file).use { input -> java.util.zip.ZipOutputStream(future.outputStream()).use { output ->
                input.entries().asSequence().forEach { entry ->
                    output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    if (entry.name == "manifest.json") {
                        val manifest = org.json.JSONObject(input.getInputStream(entry).bufferedReader().use { it.readText() })
                        output.write(manifest.put("version", 99).toString().toByteArray())
                    } else input.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            } }
            future.copyTo(provider.file, overwrite = true)
            val hash = BackupArchive.hash(provider.file)
            backups.backup(); done(backups)
            assertTrue(backups.state.value.error)
            assertTrue(backups.state.value.message.contains("not supported"))
            assertEquals(hash, BackupArchive.hash(provider.file))
        }
    }
    @Test fun `setup supplies a default name and cancellation does not change the library`() = runBlocking {
        fixture { _, db, settings, _, backups, _, _, _, _ ->
            backups.create(); assertEquals("DistillPod-backups.zip", backups.destinations.receive())
            backups.created(null)
            assertFalse(backups.state.value.awaitingDestination)
            assertEquals("", settings.value("managedBackup").first())
            assertEquals(12000L, db.library().episode("episode")!!.positionMs)
        }
    }
    @Test fun `file picker requests all file types and starts at remembered document`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://provider/document/backup")
        val intent = BackupFilePicker { uri }.createIntent(context, arrayOf("*/*"))
        assertEquals("*/*", intent.type)
        assertArrayEquals(arrayOf("*/*"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertEquals(uri, intent.getParcelableExtra<Uri>(android.provider.DocumentsContract.EXTRA_INITIAL_URI))
        assertTrue(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
    }
}
