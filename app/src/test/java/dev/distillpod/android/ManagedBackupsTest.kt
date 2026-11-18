package dev.distillpod.android

import android.content.Context
import android.content.Intent
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.data.*
import dev.distillpod.android.features.BackupFilePicker
import dev.distillpod.android.sync.SubscriptionCloudAccess
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
    private suspend fun done(backups: ManagedBackups, phase: String) = try {
        withTimeout(15000) { backups.state.first { !it.working } }
    } catch (timeout: TimeoutCancellationException) {
        val state = backups.state.value
        timeout.addSuppressed(AssertionError("Backup fixture phase=$phase, working=${state.working}, " +
            "error=${state.error}, connected=${state.location != null}, history=${state.history.size}"))
        throw timeout
    }
    private suspend fun fixture(dispatcher: CoroutineDispatcher = Dispatchers.IO, block: suspend (Context, AppDatabase, AppSettings, CoroutineScope, ManagedBackups, Provider, Uri, File, CompletableDeferred<BackupPreview>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.cacheDir, "managed-test-${UUID.randomUUID()}").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
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
        var bodyFailure: Throwable? = null
        try {
            db.library().putPodcast(PodcastEntity("show", "https://example.test/feed", "Fixture"))
            db.library().putEpisode(EpisodeEntity("episode", "show", "guid", "Episode", "https://example.test/audio", positionMs = 12000))
            block(context, db, settings, scope, backups, provider, uri, folder, preview)
        } catch (error: Throwable) {
            bodyFailure = error
            throw error
        } finally {
            try {
                withContext(NonCancellable) {
                    // State can finish before operation/finalizer cleanup; keep its resources alive.
                    withTimeout(5000) { scope.coroutineContext[Job]!!.cancelAndJoin() }
                    db.close()
                    folder.deleteRecursively()
                }
            } catch (cleanupFailure: Throwable) {
                val original = bodyFailure
                if (original != null) original.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `fixture keeps its database and files until owned cleanup finishes`() = runTest {
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleanupFinished = CompletableDeferred<Unit>()
        var database: AppDatabase? = null
        var directory: File? = null
        var owned: Job? = null
        val call = async {
            fixture(StandardTestDispatcher(testScheduler)) { _, db, _, scope, _, _, _, folder, _ ->
                database = db
                directory = folder
                owned = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() }
                    finally {
                        withContext(NonCancellable) {
                            cleanupStarted.complete(Unit)
                            releaseCleanup.await()
                            cleanupFinished.complete(Unit)
                        }
                    }
                }
            }
        }
        try {
            cleanupStarted.await()
            runCurrent()
            assertFalse("Fixture returned before its owned cleanup finished", call.isCompleted)
            assertTrue("Database closed while owned cleanup was suspended", database!!.isOpen)
            assertTrue("Fixture files removed while owned cleanup was suspended", directory!!.isDirectory)
            releaseCleanup.complete(Unit)
            call.await()
            assertTrue(cleanupFinished.isCompleted)
            assertFalse(database!!.isOpen)
            assertFalse(directory!!.exists())
        } finally {
            releaseCleanup.complete(Unit)
            call.join()
            owned?.join()
        }
    }
    @Test fun `remembered file keeps two dated snapshots and restores an older snapshot without a picker`() = runBlocking {
        fixture { context, db, settings, scope, backups, provider, uri, folder, preview ->
            backups.created(uri); done(backups, "created")
            assertFalse(backups.state.value.message, backups.state.value.error)
            assertEquals(1, backups.state.value.history.size)
            val older = backups.state.value.history.single()
            db.library().putEpisode(db.library().episode("episode")!!.copy(positionMs = 24000))
            backups.backup(); done(backups, "backup")
            assertEquals(2, backups.state.value.history.size)
            assertTrue(backups.destinations.tryReceive().isFailure)
            assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission })
            val restarted = ManagedBackups(context, LibraryBackup(db), settings, scope) { preview.complete(it) }
            restarted.refresh(choose = true); done(restarted, "refresh")
            assertEquals(2, restarted.state.value.history.size)
            assertTrue(restarted.state.value.history.none { it.local })
            assertTrue(restarted.state.value.chooseBackup)
            restarted.preview(older.id, false); done(restarted, "preview")
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
            backups.created(uri); done(backups, "created")
            val previousHash = BackupArchive.hash(provider.file)
            val connection = settings.value("managedBackup").first()
            provider.corruptNextWrite = true
            backups.backup(); done(backups, "backup")
            assertTrue(backups.state.value.error)
            assertEquals(previousHash, BackupArchive.hash(provider.file))
            assertEquals(1, BackupArchive.read(provider.file, File(folder, "check")).size)
            assertEquals(connection, settings.value("managedBackup").first())
        }
    }
    @Test fun `lost Drive access exposes a marked local recovery copy without overwriting anything`() = runBlocking {
        fixture { _, _, _, _, backups, provider, uri, _, preview ->
            backups.created(uri); done(backups, "created")
            val previousHash = BackupArchive.hash(provider.file)
            provider.readsFail = true
            backups.refresh(choose = true); done(backups, "refresh")
            assertTrue(backups.state.value.error)
            val cached = backups.state.value.history.single()
            assertTrue(cached.local)
            backups.preview(cached.id, true); done(backups, "preview")
            assertEquals(1, withTimeout(10000) { preview.await() }.episodes)
            backups.backup(); done(backups, "backup")
            assertTrue(backups.state.value.error)
            assertEquals(previousHash, BackupArchive.hash(provider.file))
        }
    }
    @Test fun `invalid existing file never replaces a working connection or library`() = runBlocking {
        fixture { _, db, settings, _, backups, provider, uri, _, _ ->
            backups.created(uri); done(backups, "created")
            val connection = settings.value("managedBackup").first()
            provider.file.writeText("not a backup")
            backups.connect(uri); done(backups, "connect")
            assertTrue(backups.state.value.error)
            assertEquals(connection, settings.value("managedBackup").first())
            assertEquals(12000L, db.library().episode("episode")!!.positionMs)
            assertEquals("not a backup", provider.file.readText())
        }
    }
    @Test fun `reconnecting a valid history does not write to its cloud file`() = runBlocking {
        fixture { context, db, _, scope, backups, provider, uri, folder, _ ->
            backups.created(uri); done(backups, "created")
            val hash = BackupArchive.hash(provider.file)
            provider.writesFail = true
            val newSettings = AppSettings(context, PreferenceDataStoreFactory.create(scope = scope) { File(folder, "fresh.preferences_pb") })
            val connected = ManagedBackups(context, LibraryBackup(db), newSettings, scope) {}
            connected.connect(uri); done(connected, "connect")
            assertFalse(connected.state.value.message, connected.state.value.error)
            assertEquals(1, connected.state.value.history.size)
            assertEquals(hash, BackupArchive.hash(provider.file))
        }
    }
    @Test fun `damaged cloud history is repaired from recovery and gains a new snapshot`() = runBlocking {
        fixture { _, db, _, _, backups, provider, uri, folder, _ ->
            backups.created(uri); done(backups, "created")
            provider.file.writeText("power loss during cloud update")
            backups.backup(); done(backups, "backup")
            assertFalse(backups.state.value.message, backups.state.value.error)
            assertTrue(backups.state.value.message.startsWith("Backup repaired"))
            assertEquals(2, BackupArchive.read(provider.file, File(folder, "repaired")).size)
            assertEquals(12000L, db.library().episode("episode")!!.positionMs)
        }
    }
    @Test fun `forgetting a connection revokes access without deleting cloud or local library data`() = runBlocking {
        fixture { context, db, settings, _, backups, provider, uri, _, _ ->
            backups.created(uri); done(backups, "created")
            val hash = BackupArchive.hash(provider.file)
            backups.disconnect(); done(backups, "disconnect")
            assertEquals("", settings.value("managedBackup").first())
            assertNull(backups.state.value.location)
            assertTrue(context.contentResolver.persistedUriPermissions.none { it.uri == uri })
            assertEquals(hash, BackupArchive.hash(provider.file))
            assertEquals(12000L, db.library().episode("episode")!!.positionMs)
        }
    }
    @Test fun `a future history version is never overwritten with an older recovery copy`() = runBlocking {
        fixture { _, _, _, _, backups, provider, uri, folder, _ ->
            backups.created(uri); done(backups, "created")
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
            backups.backup(); done(backups, "backup")
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
    @Test fun `automatic backup skips an unchanged library and saves after a change`() = runBlocking {
        fixture { _, db, _, _, backups, provider, uri, folder, _ ->
            assertEquals(ManagedBackups.Automatic.NotConnected, backups.automatic())
            backups.created(uri); done(backups, "created")
            val first = BackupArchive.hash(provider.file)
            assertEquals(ManagedBackups.Automatic.Unchanged, backups.automatic())
            assertEquals("An unchanged library must not add a snapshot", first, BackupArchive.hash(provider.file))
            assertEquals(1, backups.state.value.history.size)
            assertFalse(backups.state.value.working)
            db.library().putEpisode(db.library().episode("episode")!!.copy(positionMs = 30000))
            assertEquals(ManagedBackups.Automatic.Saved, backups.automatic())
            assertEquals(2, BackupArchive.read(provider.file, File(folder, "automatic")).size)
            val status = backups.status.first()
            assertEquals("", status.error)
            assertTrue(status.savedAt > 0 && status.checkedAt >= status.savedAt)
        }
    }
    @Test fun `canceling restore keeps automatic backup active and preserves the original snapshot`() = runBlocking {
        fixture { context, _, _, scope, origin, provider, uri, folder, _ ->
            origin.created(uri)
            done(origin, "seed original archive")
            assertFalse(origin.state.value.message, origin.state.value.error)
            val originalArchive = BackupArchive.hash(provider.file)
            val original = BackupArchive.read(provider.file, File(folder, "original")).single().record
            val priorInstrumentation = SubscriptionCloudAccess.instrumentation
            SubscriptionCloudAccess.instrumentation = true
            WorkManagerTestInitHelper.initializeTestWorkManager(context)
            val manager = WorkManager.getInstance(context)
            val graph = context.appGraph
            try {
                awaitMain { graph.ready.isCompleted }
                graph.ready.await()
                val target = graph.db.library()
                target.putPodcast(PodcastEntity("local-show", "https://example.test/local", "Local show"))
                target.putEpisode(EpisodeEntity("local-episode", "local-show", "local-guid", "Local episode",
                    "https://example.test/local-audio", positionMs = 27000))
                val backups = graph.managedBackups
                val transfers = BackupDocuments(context, graph.backup, graph.settings, scope) {}
                scope.launch(start = CoroutineStart.UNDISPATCHED) { backups.previews.collect(transfers::review) }

                backups.connect(uri)
                done(backups, "connect existing archive")
                assertFalse(backups.state.value.message, backups.state.value.error)
                assertEquals(original.id, backups.state.value.history.single().id)
                assertEquals(originalArchive, BackupArchive.hash(provider.file))
                val scheduled = manager.getWorkInfosForUniqueWork("managed-backup").get(5, TimeUnit.SECONDS).single()
                assertEquals(WorkInfo.State.ENQUEUED, scheduled.state)

                backups.preview(original.id, false)
                awaitMain { transfers.state.value.preview != null || backups.state.value.error }
                assertFalse(backups.state.value.message, backups.state.value.error)
                assertNotNull(transfers.state.value.preview)
                transfers.dismissPreview()
                assertNull(transfers.state.value.preview)
                assertEquals(listOf("local-episode"), target.episodes().map { it.id })
                assertTrue(backups.connected.first())
                assertEquals(originalArchive, BackupArchive.hash(provider.file))

                val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
                driver.setAllConstraintsMet(scheduled.id)
                awaitMain { backups.state.value.history.size == 2 || backups.state.value.error }
                assertFalse(backups.state.value.message, backups.state.value.error)
                val written = BackupArchive.read(provider.file, File(folder, "after-scheduled-run"))
                assertEquals(2, written.size)
                assertEquals(original, written.single { it.record.id == original.id }.record)
                assertNull(target.episode("episode"))

                backups.refresh(choose = true)
                done(backups, "reopen history")
                assertTrue(backups.state.value.chooseBackup)
                assertTrue(backups.state.value.history.any { it.id == original.id && !it.local })
                backups.preview(original.id, false)
                awaitMain { transfers.state.value.preview != null || backups.state.value.error }
                assertFalse(backups.state.value.message, backups.state.value.error)
                assertNotNull(transfers.state.value.preview)
                transfers.restore(restorePreferences = false)
                awaitMain { transfers.state.value.phase.startsWith("Restored") || transfers.state.value.error }
                assertFalse(transfers.state.value.phase, transfers.state.value.error)
                assertEquals(12000L, target.episode("episode")?.positionMs)
                assertEquals(27000L, target.episode("local-episode")?.positionMs)
            } finally {
                try {
                    manager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
                    graph.scope.cancel()
                    awaitMain { graph.scope.coroutineContext.job.isCompleted }
                    graph.db.close()
                    WorkManagerTestInitHelper.closeWorkDatabase()
                } finally {
                    SubscriptionCloudAccess.instrumentation = priorInstrumentation
                }
            }
        }
    }

    private suspend fun awaitMain(condition: () -> Boolean) = withTimeout(15000) {
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(10)
        }
    }

    @Test fun `automatic failure is stored for Settings and lost access waits for the user`() = runBlocking {
        fixture { _, _, _, _, backups, provider, uri, _, _ ->
            backups.created(uri); done(backups, "created")
            val hash = BackupArchive.hash(provider.file)
            provider.readsFail = true
            assertEquals(ManagedBackups.Automatic.Failed(needsUser = true), backups.automatic())
            assertTrue(backups.status.first().error.isNotBlank())
            assertEquals(hash, BackupArchive.hash(provider.file))
            provider.readsFail = false
            provider.writesFail = true
            backups.backup(); done(backups, "manual")
            assertTrue(backups.state.value.error)
            assertTrue("A failed manual backup is also reported", backups.status.first().error.isNotBlank())
            provider.writesFail = false
            assertEquals(ManagedBackups.Automatic.Unchanged, backups.automatic())
            assertEquals("A later successful check clears the failure", "", backups.status.first().error)
        }
    }
    @Test fun `automatic backup waits while another backup action is running`() = runBlocking {
        fixture { _, _, _, _, backups, _, uri, _, _ ->
            backups.created(uri); done(backups, "created")
            backups.create()
            assertTrue(backups.state.value.awaitingDestination)
            assertEquals(ManagedBackups.Automatic.Busy, backups.automatic())
            backups.created(null)
        }
    }
    @Test fun `daily schedule follows the file connection`() = runBlocking {
        fixture { context, db, settings, scope, _, _, uri, _, _ ->
            val calls = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
            val backups = ManagedBackups(context, LibraryBackup(db), settings, scope, schedule = { calls += it })
            backups.created(uri); done(backups, "created")
            assertTrue(backups.connected.first())
            assertEquals("DistillPod-backups.zip", backups.file.first())
            backups.disconnect(); done(backups, "disconnect")
            assertEquals(listOf(true, false), calls.toList())
            assertFalse(backups.connected.first())
            assertEquals(ManagedBackups.Status(), backups.status.first())
        }
    }
    @Test fun `restore previews reach the open screen when no reviewer is attached`() = runBlocking {
        fixture { context, db, settings, scope, _, _, uri, _, _ ->
            val backups = ManagedBackups(context, LibraryBackup(db), settings, scope)
            backups.created(uri); done(backups, "created")
            val received = scope.async(start = CoroutineStart.UNDISPATCHED) { backups.previews.first() }
            backups.preview(backups.state.value.history.single().id, false); done(backups, "preview")
            assertEquals(1, withTimeout(10000) { received.await() }.episodes)
        }
    }
    @Test fun `stored backup status survives a round trip and tolerates damage`() {
        val status = ManagedBackups.Status(checkedAt = 5, savedAt = 4, error = "Access lost")
        assertEquals(status, ManagedBackups.Status.parse(status.json()))
        assertEquals(ManagedBackups.Status(), ManagedBackups.Status.parse(""))
        assertEquals(ManagedBackups.Status(), ManagedBackups.Status.parse("{broken"))
    }
}
