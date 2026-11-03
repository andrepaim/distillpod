package dev.distillpod.android

import android.content.Context
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupDocumentsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun systemPickerRoundTripIntoFreshDatabaseAndSafeRepeatedMerge() = runBlocking<Unit> {
        val context = compose.activity.applicationContext
        context.appGraph.ready.await()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val folder = File(context.cacheDir, "backup-test-${UUID.randomUUID()}").apply { mkdirs() }
        val source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = AppSettings(context, PreferenceDataStoreFactory.create(scope = scope) { File(folder, "settings.preferences_pb") })
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        var queueSyncs = 0
        val documents = BackupDocuments(context, LibraryBackup(source), settings, scope) { queueSyncs++ }
        val providerTitle = InstrumentationRegistry.getArguments().getString("backupRoot") ?: "DistillPod test files"
        val transferTimeout = if (providerTitle == "Drive") 60000L else 15000L
        var savedUri: android.net.Uri? = null
        suspend fun show(docs: BackupDocuments) {
            withContext(Dispatchers.Main) { compose.activity.setContent {
                DistillTheme { Surface { Box(Modifier.fillMaxSize().padding(top = 40.dp, bottom = 40.dp)) {
                    BackupExportLauncher(docs) { uri -> savedUri = uri; docs.save(uri) }; BackupScreen(docs)
                } } }
            } }
            compose.waitForIdle()
        }
        fun providerRoot() {
            val personal = InstrumentationRegistry.getArguments().getString("backupPersonal") == "true"
            val selector = if (personal) By.text(java.util.regex.Pattern.compile(".*@gmail\\.com")) else By.text(providerTitle)
            DocumentPickerUi.location(device, selector)
            if (providerTitle == "Drive") {
                device.wait(Until.findObject(By.text("My Drive")), 5000)?.click()
                device.waitForIdle()
            }
        }
        try {
            source.library().putPodcast(PodcastEntity("fixture-show", "https://example.test/backup-feed", "Backup fixture"))
            source.library().putEpisode(EpisodeEntity("fixture-episode", "fixture-show", "guid", "Synthetic episode", "https://example.test/audio", positionMs = 12000))
            source.library().insertQueue(listOf(QueueEntryEntity("fixture-episode", 0)))
            settings.restorePortable(PortablePreferences("base", "pt", "test-model"))
            show(documents)
            compose.onNodeWithText("Save backup to Google Drive").performClick()
            compose.waitUntil(transferTimeout) { documents.state.value.awaitingDestination || documents.state.value.error }
            assertFalse(documents.state.value.phase, documents.state.value.error)
            providerRoot()
            checkNotNull(device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("Save", java.util.regex.Pattern.CASE_INSENSITIVE)).enabled(true)), 10000)).click()
            compose.waitUntil(transferTimeout) { documents.state.value.phase.startsWith("Saved and checked") || documents.state.value.error }
            assertFalse(documents.state.value.phase, documents.state.value.error)
            val receipt = BackupDocuments.Saved.parse(settings.value("lastBackup").first())!!
            assertEquals(1, receipt.podcasts); assertEquals(1, receipt.episodes)
            compose.onNodeWithText("Last saved file").performScrollTo().assertIsDisplayed()
            val imported = BackupDocuments(context, LibraryBackup(target), settings, scope) { queueSyncs++ }
            settings.text("language", "en")
            show(imported)
            compose.onNodeWithText("Import backup").performScrollTo().performClick()
            providerRoot()
            if (providerTitle == "Drive") {
                val search = device.findObject(UiSelector().description("Search"))
                check(search.waitForExists(5000)) { "Drive file search unavailable" }
                search.click()
                val input = device.findObject(UiSelector().classNameMatches("android.widget.(AutoCompleteTextView|EditText)"))
                check(input.waitForExists(5000))
                input.setText(receipt.name)
                device.pressEnter()
            }
            val backupFile = device.findObject(UiSelector().text(receipt.name).className("android.widget.TextView"))
            check(backupFile.waitForExists(transferTimeout)) { "Saved fixture not found in picker" }
            backupFile.click()
            compose.waitUntil(transferTimeout) { imported.state.value.preview != null || imported.state.value.error }
            assertFalse(imported.state.value.phase, imported.state.value.error)
            compose.onNodeWithText("Review backup").assertIsDisplayed()
            assertTrue(target.library().podcasts().isEmpty())
            compose.onNodeWithText("Also restore language and model choices").assertIsDisplayed()
            compose.onNode(isToggleable()).performClick()
            compose.onNodeWithText("Merge library").performClick()
            compose.waitUntil(transferTimeout) { imported.state.value.phase.startsWith("Restored") || imported.state.value.error }
            assertFalse(imported.state.value.phase, imported.state.value.error)
            assertEquals(12000L, target.library().episode("fixture-episode")!!.positionMs)
            assertEquals("pt", settings.flow.first().language)
            assertEquals(listOf("fixture-episode"), target.library().queue().map { it.episodeId })
            assertEquals(1, queueSyncs)
            // Repeating the exact snapshot never overwrites newer local progress.
            val bytes = LibraryBackup(source).export(PortablePreferences("base", "pt", "test-model"), "test")
            val current = target.library().episode("fixture-episode")!!
            target.library().putEpisode(current.copy(positionMs = 24000))
            val backup = LibraryBackup(target)
            assertEquals(0, backup.merge(backup.preview(bytes.inputStream())))
            assertEquals(24000L, target.library().episode("fixture-episode")!!.positionMs)
        } finally {
            withContext(Dispatchers.Main) { compose.activity.setContent {} }
            savedUri?.let { uri -> withContext(Dispatchers.IO) { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) } }
            scope.cancel(); source.close(); target.close(); folder.deleteRecursively()
        }
    }
}
