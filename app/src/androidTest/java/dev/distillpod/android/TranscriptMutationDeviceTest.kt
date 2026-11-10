package dev.distillpod.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptMutationDeviceTest {
    @Test fun pauseCannotRecreateDeletedWork() = mutation("pause", "deleted")
    @Test fun pauseCannotReplaceANewerRevision() = mutation("pause", "replacement")
    @Test fun pausePreservesANewerCheckpoint() = mutation("pause", "checkpoint")
    @Test fun cancellationCleanupPreservesOriginalExceptionAfterDeletion() = mutation("cancel", "deleted")
    @Test fun cancellationCleanupCannotReplaceANewerRevision() = mutation("cancel", "replacement")
    @Test fun cancellationCleanupCannotUndoAPause() = mutation("cancel", "paused")
    @Test fun cancellationCleanupPreservesANewerCheckpoint() = mutation("cancel", "checkpoint")
    @Test fun missingModelCannotReplaceANewerRevision() = mutation("model", "replacement")
    @Test fun missingModelCannotUndoCompletion() = mutation("model", "ready")
    @Test fun missingModelPreservesANewerCheckpoint() = mutation("model", "checkpoint")
    @Test fun pendingPauseCannotRecreateDeletedWork() = mutation("pending", "deleted")
    @Test fun pendingPauseCannotReplaceANewerRevision() = mutation("pending", "replacement")
    @Test fun pendingPausePreservesANewerCheckpoint() = mutation("pending", "checkpoint")

    private fun mutation(operation: String, change: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val offline = context.packageName == "dev.distillpod.android.validation.lifetime81r2"
        check(offline || Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")) {
            "Physical execution requires the isolated offline lifetime validation app"
        }
        if (offline) for (permission in listOf(Manifest.permission.INTERNET, Manifest.permission.RECORD_AUDIO))
            check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED)
        TranscriptMutationFixture.run(context, operation, change)
    }
}
