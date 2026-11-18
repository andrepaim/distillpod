package dev.distillpod.android

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.AppDatabase
import dev.distillpod.android.data.AppSettings
import dev.distillpod.android.data.BackupDocuments
import dev.distillpod.android.data.LibraryBackup
import dev.distillpod.android.data.ManagedBackups
import dev.distillpod.android.features.ManagedBackupScreen
import dev.distillpod.android.ui.theme.DistillTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManagedBackupDisclosureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun connectExistingBackupExplainsRecurringWritesBeforeOpeningPicker() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = kotlin.io.path.createTempDirectory("backup-disclosure").toFile()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = AppSettings(context, PreferenceDataStoreFactory.create(scope = scope) {
            File(directory, "backup.preferences_pb")
        })
        val library = LibraryBackup(database)
        val managed = ManagedBackups(context, library, settings, scope)
        val transfers = BackupDocuments(context, library, settings, scope) {}
        try {
            compose.setContent { DistillTheme { ManagedBackupScreen(managed, transfers) } }
            compose.waitUntil(5000) { !managed.state.value.working }

            val disclosure = compose.onNodeWithText(
                "Connecting this file lets DistillPod add daily backups to it. " +
                    "You’ll choose which snapshot to restore next. If you cancel the restore, automatic backup stays on."
            ).performScrollTo().assertIsDisplayed()
            val connect = compose.onNodeWithText("Connect existing backup").performScrollTo().assertIsDisplayed()

            assertTrue(disclosure.getUnclippedBoundsInRoot().bottom <= connect.getUnclippedBoundsInRoot().top)
            assertNull(managed.state.value.location)
        } finally {
            runBlocking { scope.coroutineContext.job.cancelAndJoin() }
            database.close()
            directory.deleteRecursively()
        }
    }
}
