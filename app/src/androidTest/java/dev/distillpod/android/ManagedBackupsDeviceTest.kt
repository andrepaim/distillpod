package dev.distillpod.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.distillpod.android.data.*
import dev.distillpod.android.features.*
import java.io.File
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedBackupsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun root() = DocumentPickerUi.location(device, By.text("DistillPod test files"))
    private fun save() { checkNotNull(device.wait(Until.findObject(By.text(Pattern.compile("Save", Pattern.CASE_INSENSITIVE)).enabled(true)), 10000)).click() }
    private suspend fun show(managed: ManagedBackups, legacy: BackupDocuments, onCreated: (android.net.Uri?) -> Unit = managed::created) {
        withContext(Dispatchers.Main) { compose.activity.setContent {
            DistillTheme { Surface { Box(Modifier.fillMaxSize().padding(top = 40.dp, bottom = 40.dp)) {
                ManagedBackupLaunchers(managed, onCreated); ManagedBackupScreen(managed, legacy)
            } } }
        } }
        compose.waitForIdle()
    }
    @Test fun connectOnceBackupAgainRestartAndRestoreHistoricalSnapshotWithoutBrowsing() = runBlocking<Unit> {
        val context = compose.activity.applicationContext
        context.appGraph.ready.await()
        val folder = File(context.cacheDir, "managed-device-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val sourceSettings = AppSettings(context, PreferenceDataStoreFactory.create(scope = scope) { File(folder, "source.preferences_pb") })
        val targetSettings = AppSettings(context, PreferenceDataStoreFactory.create(scope = scope) { File(folder, "target.preferences_pb") })
        val legacySource = BackupDocuments(context, LibraryBackup(source), sourceSettings, scope) {}
        val legacyTarget = BackupDocuments(context, LibraryBackup(target), targetSettings, scope) {}
        val managed = ManagedBackups(context, LibraryBackup(source), sourceSettings, scope, legacySource::review)
        var uri: android.net.Uri? = null
        try {
            source.library().putPodcast(PodcastEntity("managed-show", "https://example.test/feed", "Fixture"))
            source.library().putEpisode(EpisodeEntity("managed-episode", "managed-show", "guid", "Fixture episode", "https://example.test/audio", positionMs = 12000))
            show(managed, legacySource) { selected -> uri = selected; managed.created(selected) }
            compose.waitUntil(10000) { !managed.state.value.working }
            compose.onNodeWithText("Create backup file").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            root(); save()
            compose.waitUntil(20000) { !managed.state.value.working && (managed.state.value.history.isNotEmpty() || managed.state.value.error) }
            assertFalse(managed.state.value.message, managed.state.value.error)
            assertNotNull(uri)
            val name = managed.state.value.location!!.name
            val older = managed.state.value.history.single()
            assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission })
            source.library().putEpisode(source.library().episode("managed-episode")!!.copy(positionMs = 24000))
            compose.onNodeWithText("Back up now").performScrollTo().performClick()
            compose.waitUntil(20000) { managed.state.value.history.size == 2 || managed.state.value.error }
            assertFalse(managed.state.value.message, managed.state.value.error)
            assertEquals(context.packageName, device.currentPackageName)
            // A fresh installation connects the same file once and reads its history, without changing it.
            val fresh = ManagedBackups(context, LibraryBackup(target), targetSettings, scope, legacyTarget::review)
            show(fresh, legacyTarget)
            compose.waitUntil(10000) { !fresh.state.value.working }
            compose.onNodeWithText("Connect existing backup").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            root()
            val file = device.findObject(UiSelector().text(name).className("android.widget.TextView"))
            check(file.waitForExists(10000)); file.click()
            compose.waitUntil(20000) { fresh.state.value.history.size == 2 || fresh.state.value.error }
            assertFalse(fresh.state.value.message, fresh.state.value.error)
            assertTrue(fresh.state.value.history.none { it.local })
            assertTrue(target.library().episodes().isEmpty())
            // Recreate the coordinator using persisted settings and grants, with no picker interaction.
            val restarted = ManagedBackups(context, LibraryBackup(target), targetSettings, scope, legacyTarget::review)
            show(restarted, legacyTarget)
            compose.waitUntil(20000) { restarted.state.value.history.size == 2 || restarted.state.value.error }
            assertFalse(restarted.state.value.message, restarted.state.value.error)
            compose.onNodeWithText("Restore a backup").performScrollTo().performClick()
            compose.waitUntil(10000) { restarted.state.value.chooseBackup }
            compose.onNodeWithTag("Backup choice ${older.id}").performScrollTo().performClick()
            compose.waitUntil(10000) { legacyTarget.state.value.preview != null }
            compose.onNodeWithText("Review backup").assertIsDisplayed()
            assertTrue(target.library().episodes().isEmpty())
            compose.onNodeWithText("Merge library").performClick()
            compose.waitUntil(10000) { legacyTarget.state.value.phase.startsWith("Restored") }
            assertEquals(12000L, target.library().episode("managed-episode")!!.positionMs)
            assertEquals(context.packageName, device.currentPackageName)
        } finally {
            withContext(Dispatchers.Main) { compose.activity.setContent {} }
            uri?.let { android.provider.DocumentsContract.deleteDocument(context.contentResolver, it) }
            scope.cancel(); source.close(); target.close(); folder.deleteRecursively()
        }
    }
    @Test fun olderJsonWithAnUnexpectedMimeTypeIsVisibleAndImportsFromTheNewScreen() = runBlocking<Unit> {
        val context = compose.activity.applicationContext
        context.appGraph.ready.await()
        val folder = File(context.cacheDir, "legacy-managed-device-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = AppSettings(context, PreferenceDataStoreFactory.create(scope = scope) { File(folder, "settings.preferences_pb") })
        val legacySource = BackupDocuments(context, LibraryBackup(source), settings, scope) {}
        val legacyTarget = BackupDocuments(context, LibraryBackup(target), settings, scope) {}
        var uri: android.net.Uri? = null
        try {
            source.library().putPodcast(PodcastEntity("legacy-show", "https://example.test/legacy", "Legacy fixture"))
            withContext(Dispatchers.Main) { compose.activity.setContent {
                DistillTheme { Surface { Box(Modifier.fillMaxSize().padding(top = 40.dp, bottom = 40.dp)) {
                    BackupExportLauncher(legacySource) { selected -> uri = selected; legacySource.save(selected) }; BackupScreen(legacySource)
                } } }
            } }
            compose.onNodeWithText("Save backup to Google Drive").performClick(); root(); save()
            compose.waitUntil(20000) { legacySource.state.value.phase.startsWith("Saved and checked") || legacySource.state.value.error }
            assertFalse(legacySource.state.value.phase, legacySource.state.value.error)
            val name = BackupDocuments.Saved.parse(settings.value("lastBackup").first())!!.name
            val managed = ManagedBackups(context, LibraryBackup(target), settings, scope, legacyTarget::review)
            show(managed, legacyTarget)
            compose.waitUntil(10000) { !managed.state.value.working }
            compose.onNodeWithText("Import older backup").performScrollTo().performClick(); root()
            val file = device.findObject(UiSelector().text(name).className("android.widget.TextView"))
            check(file.waitForExists(10000)); file.click()
            compose.waitUntil(10000) { legacyTarget.state.value.preview != null || legacyTarget.state.value.error }
            assertFalse(legacyTarget.state.value.phase, legacyTarget.state.value.error)
            compose.onNodeWithText("Merge library").performClick()
            compose.waitUntil(10000) { legacyTarget.state.value.phase.startsWith("Restored") }
            assertEquals(1, target.library().podcasts().size)
        } finally {
            withContext(Dispatchers.Main) { compose.activity.setContent {} }
            uri?.let { android.provider.DocumentsContract.deleteDocument(context.contentResolver, it) }
            scope.cancel(); source.close(); target.close(); folder.deleteRecursively()
        }
    }
}
