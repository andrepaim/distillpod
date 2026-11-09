package dev.distillpod.android

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated offline phone protocol. Never executes in the production application. */
@RunWith(AndroidJUnit4::class)
class CloudQueueChoicePhoneDeviceTest {
    @Test fun offlineCloudChoiceAndLaterEditsPreservePlaybackAndPersistedOrder() = checkScenario("normal")
    @Test fun offlineClearWhileCloudChoiceWaitsRemainsCleared() = checkScenario("clear")
    @Test fun offlineLaterClearCannotBeUndoneByAnOlderRoomEmission() = checkScenario("later-clear")
    @Test fun offlineCallerCancellationDoesNotStrandCloudChoice() = checkScenario("cancel-running")

    private fun checkScenario(mode:String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="dev.distillpod.android.validation.queue75")
        for(permission in listOf(Manifest.permission.INTERNET,Manifest.permission.RECORD_AUDIO))
            check(context.checkSelfPermission(permission)==PackageManager.PERMISSION_DENIED)
        runBlocking {
            val graph=context.appGraph;graph.ready.await()
            check(graph.db.library().episodes().isEmpty() && graph.db.library().queue().isEmpty()) {
                "Use a fresh offline queue-choice validation app"
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            CloudQueueOrderingFixture.scenario(mode,offlinePhone=true)
        }
    }
}
