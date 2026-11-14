package dev.distillpod.android

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.features.AppViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * The in-context notification request (#193) against Android's real permission dialog. Each phase
 * needs the permission revoked first, outside instrumentation (revoking kills the process). Permission
 * changes are restricted to an emulator or the separate notify193 validation application.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPromptDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = compose.activity
    private val packageName get() = context.packageName
    private val phase get() = InstrumentationRegistry.getArguments().getString("notificationPhase") ?: "promptDeny"
    private val permission by lazy { NotificationPermissionDevice(phase) { compose.activity } }
    private val explanation = "Show progress outside the app?"
    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun clearFlags() =
        permission.shell("pm clear-permission-flags $packageName ${Manifest.permission.POST_NOTIFICATIONS} user-set user-fixed")

    @Test fun firstDownloadOffersNotificationAccessOnce() = runBlocking {
        check(phase in setOf("promptDeny", "promptGrant")) { "Unknown phase $phase" }
        check(Build.VERSION.SDK_INT >= 33)
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic") ||
            packageName == "dev.distillpod.android.validation.notify193")
        val graph = context.applicationContext.appGraph
        graph.ready.await()
        check(!permission.granted()) { "Revoke notification permission before each phase" }
        clearFlags()
        val access = context.getSharedPreferences("notification_access", Context.MODE_PRIVATE)
        access.edit().clear().commit()
        val id = "notify193-${UUID.randomUUID()}"
        val dao = graph.db.library()
        // Synthetic and unfollowed; loopback refuses at once, so nothing leaves the device.
        dao.putPodcast(PodcastEntity(id, "http://127.0.0.1:9/$id.xml", "Notification fixture", subscribed = false))
        dao.putEpisode(EpisodeEntity(id, id, id, "Notification fixture episode", "http://127.0.0.1:9/$id.mp3"))
        val vm = withContext(Dispatchers.Main) {
            ViewModelProvider(context, AppViewModel.factory(graph))[AppViewModel::class.java].also { it.open("episode:$id") }
        }
        try {
            permission.await("episode-page") { shown("Download") }
            compose.onNodeWithText("Download").performScrollTo().performClick()
            permission.await("explanation-shown") { shown(explanation) }
            // Queued before, and regardless of, any answer.
            assertNotEquals("none", dao.episode(id)?.downloadState)
            compose.onNodeWithText("Allow notifications").performClick()
            if (phase == "promptGrant") {
                permission.permissionDecision("permission_allow_button")
                permission.await("runtime-permission-granted") { permission.granted() }
                permission.await("runtime-notifications-enabled") { permission.enabled() }
                // Effects run on the test clock: let "Download queued" expire so a link would show.
                compose.mainClock.advanceTimeBy(6000)
                assertFalse("granted needs no settings link", shown("Notifications are off"))
            } else {
                permission.permissionDecision("permission_deny_button")
                // Effects run on the test clock: "Download queued" holds the snackbar until it advances.
                permission.await("quiet-link-shown", 15000) { compose.mainClock.advanceTimeBy(250); shown("Notifications are off") }
                assertFalse(permission.granted())
                compose.onNode(hasText("Settings") and hasClickAction()).performClick()
                permission.await("settings-opened", 10000) { shown("Open notification settings") }
                // #20's recovery path: the in-context request used the one platform request.
                compose.onNodeWithText("Enable notifications").assertDoesNotExist()
            }
            // Never asked again, whatever the answer.
            withContext(Dispatchers.Main) { vm.download(id) }
            permission.await("second-start-finished") { "download:$id" !in vm.busy.value }
            compose.waitForIdle()
            assertFalse(shown(explanation))
            assertFalse(vm.notificationPrompt.offered.value)
            permission.status("in-context-$phase", "passed")
        } finally {
            // Cleanup must not replace the original failure.
            WorkManager.getInstance(context).cancelUniqueWork("download:$id")
            withContext(NonCancellable) { dao.deletePodcast(id) }
            access.edit().clear().commit()
            clearFlags()
        }
    }
}
