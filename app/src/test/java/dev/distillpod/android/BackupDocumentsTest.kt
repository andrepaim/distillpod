package dev.distillpod.android

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupDocumentsTest {
    private class Provider(val folder: File) : DocumentsProvider() {
        var readsFail = false
        var writesFail = false
        var truncate = false
        val file = File(folder, "document.json")
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?) = MatrixCursor(arrayOf("root_id"))
        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(Document.COLUMN_DISPLAY_NAME)).apply { addRow(arrayOf("Cloud copy.json")) }
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?) = MatrixCursor(arrayOf(Document.COLUMN_DOCUMENT_ID))
        override fun openDocument(documentId: String, mode: String, signal: android.os.CancellationSignal?): ParcelFileDescriptor {
            if (mode.contains('w') && writesFail) throw FileNotFoundException("Provider storage full")
            if (!mode.contains('w') && readsFail) throw SecurityException("Permission revoked")
            if (!mode.contains('w') && truncate) file.writeText("incomplete")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }
        override fun deleteDocument(documentId: String) { file.delete() }
    }
    private suspend fun waitDone(documents: BackupDocuments) = withTimeout(10000) { documents.state.first { !it.working } }
    private suspend fun fixture(block: suspend (AppDatabase, AppSettings, BackupDocuments, Provider, Uri, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = AppSettings(context, PreferenceDataStoreFactory.create(scope = scope) { File(folder, "settings.preferences_pb") })
        val provider = Provider(folder)
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply {
            authority = "backup.fixture"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = "android.permission.MANAGE_DOCUMENTS"
        })
        ShadowContentResolver.registerProviderInternal("backup.fixture", provider)
        val uri = Uri.parse("content://backup.fixture/document/backup")
        context.grantUriPermission(context.packageName, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val documents = BackupDocuments(context, LibraryBackup(db), settings, scope) {}
        try { block(db, settings, documents, provider, uri, folder) }
        finally { scope.cancel(); db.close(); folder.deleteRecursively() }
    }
    private fun contextQuery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.contentResolver.query(Uri.parse("content://backup.fixture/document/backup"), arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), android.os.Bundle.EMPTY, null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals("Cloud copy.json", it.getString(0))
        }
    }
    @Test fun `manual save prepares before destination and verifies persisted bytes and metadata`() = runBlocking {
        fixture { db, settings, docs, provider, uri, _ ->
            db.library().putPodcast(PodcastEntity("show", "https://example.test/feed", "Fixture"))
            contextQuery()
            docs.prepare()
            val name = withTimeout(10000) { docs.destinations.receive() }
            assertTrue(name.startsWith("distillpod-")); assertTrue(docs.state.value.awaitingDestination)
            assertFalse(provider.file.exists())
            docs.save(uri); waitDone(docs)
            assertFalse(docs.state.value.phase, docs.state.value.error)
            val saved = BackupDocuments.Saved.parse(settings.value("lastBackup").first())!!
            assertEquals(provider.file.length(), saved.bytes); assertEquals(1, saved.podcasts)
            assertEquals("Cloud copy.json", saved.name)
            assertEquals(2, JSONObject(provider.file.readText()).getInt("version"))
        }
    }
    @Test fun `cancel cleans prepared text and leaves previous backup receipt alone`() = runBlocking {
        fixture { _, settings, docs, _, _, _ ->
            settings.text("lastBackup", "previous")
            docs.prepare(); withTimeout(10000) { docs.destinations.receive() }
            docs.save(null); waitDone(docs)
            assertEquals("previous", settings.value("lastBackup").first())
            val context = ApplicationProvider.getApplicationContext<Context>()
            withTimeout(10000) { while (File(context.cacheDir, "library-backups").listFiles().orEmpty().isNotEmpty()) delay(10) }
            docs.prepare(); assertNotNull(withTimeout(10000) { docs.destinations.receive() })
            docs.save(null); waitDone(docs)
        }
    }
    @Test fun `failed or truncated provider writes are never reported as successful`() = runBlocking {
        fixture { _, settings, docs, provider, uri, _ ->
            settings.text("lastBackup", "previous")
            for (failure in listOf("write", "truncate", "permission")) {
                provider.writesFail = failure == "write"; provider.truncate = failure == "truncate"; provider.readsFail = failure == "permission"
                docs.prepare(); withTimeout(10000) { docs.destinations.receive() }
                docs.save(uri); waitDone(docs)
                assertTrue(failure, docs.state.value.error)
                assertEquals("previous", settings.value("lastBackup").first())
                assertFalse(provider.file.exists())
            }
        }
    }
    @Test fun `invalid import cannot mutate library or preferences`() = runBlocking {
        fixture { db, settings, docs, provider, uri, _ ->
            settings.text("language", "pt")
            provider.file.writeText("not a backup")
            docs.preview(uri); waitDone(docs)
            assertTrue(docs.state.value.error); assertNull(docs.state.value.preview)
            assertTrue(db.library().podcasts().isEmpty()); assertEquals("pt", settings.flow.first().language)
        }
    }
    @Test fun `fresh target imports library and opt in preferences without device settings or jobs`() = runBlocking {
        fixture { db, settings, docs, provider, uri, _ ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                source.library().putPodcast(PodcastEntity("show", "https://example.test/feed", "Fixture"))
                provider.file.writeBytes(LibraryBackup(source).export(PortablePreferences("base", "pt", "test-model"), "0.6.0"))
                settings.flag("wifi", false); settings.flag("charging", true); settings.storage(500_000_000)
                settings.createJob("capture", "local-job")
                docs.preview(uri); waitDone(docs)
                assertNotNull(docs.state.value.phase, docs.state.value.preview?.preferences)
                docs.restore(false); waitDone(docs)
                assertEquals(1, db.library().podcasts().size); assertEquals("auto", settings.flow.first().language)
                docs.preview(uri); waitDone(docs); docs.restore(true); waitDone(docs)
                val prefs = settings.flow.first()
                assertEquals("pt", prefs.language); assertEquals("base", prefs.model); assertEquals("test-model", prefs.selectedAiModel)
                assertFalse(prefs.wifiOnly); assertTrue(prefs.chargingOnly); assertEquals(500_000_000, prefs.storageLimitBytes)
                assertEquals("queued", settings.value("capture_state").first())
                assertEquals(1, db.library().podcasts().size)
            } finally { source.close() }
        }
    }
    private fun failingSettings(context: Context): AppSettings {
        val failingStore = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = kotlinx.coroutines.flow.flowOf(androidx.datastore.preferences.core.emptyPreferences())
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences {
                throw java.io.IOException("Settings storage unavailable")
            }
        }
        return AppSettings(context, failingStore)
    }

    @Test fun `valid backup survives failure to persist its last saved receipt`() = runBlocking {
        fixture { db, _, previous, provider, uri, _ ->
            previous.prepare(); withTimeout(10000) { previous.destinations.receive() }; previous.save(null); waitDone(previous)
            val context = ApplicationProvider.getApplicationContext<Context>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val documents = BackupDocuments(context, LibraryBackup(db), failingSettings(context), scope) {}
            try {
                documents.prepare(); withTimeout(10000) { documents.destinations.receive() }
                documents.save(uri); waitDone(documents)
                assertTrue(provider.file.exists())
                assertTrue(documents.state.value.phase.startsWith("Saved and checked"))
                assertTrue(documents.state.value.phase.contains("details could not be stored"))
                assertNotNull(LibraryBackup(db).preview(provider.file.inputStream().use { it.readBytes().inputStream() }))
            } finally { scope.cancel() }
        }
    }

    @Test fun `preference write failure reports committed library and retry advice`() = runBlocking {
        fixture { db, _, _, provider, uri, _ ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            var queueSynced = false
            val documents = BackupDocuments(context, LibraryBackup(db), failingSettings(context), scope) { queueSynced = true }
            try {
                source.library().putPodcast(PodcastEntity("show", "https://example.test/feed", "Fixture"))
                provider.file.writeBytes(LibraryBackup(source).export(PortablePreferences("base", "pt", ""), "test"))
                documents.preview(uri); waitDone(documents)
                documents.restore(true); waitDone(documents)
                assertEquals(1, db.library().podcasts().size)
                assertTrue(documents.state.value.error)
                assertTrue(documents.state.value.phase.startsWith("Restored 1 records"))
                assertTrue(documents.state.value.phase.contains("Preferences could not be applied"))
                assertTrue(queueSynced)
            } finally { scope.cancel(); source.close() }
        }
    }

}
