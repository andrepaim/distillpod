package dev.distillpod.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptMutationLifetimeTest {
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
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        TranscriptMutationFixture.run(context, operation, change)
    }
}
