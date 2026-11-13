package dev.distillpod.android

import dev.distillpod.android.data.*
import dev.distillpod.android.features.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AudioCleanupControllerTest {
    private fun preview(files: Int = 1, eligible: Int = 1) = AudioCleanupPreview(
        totals = AudioCleanupTotals(downloaded = AudioCleanupCount(files, files * 5L)),
        rows = listOf(AudioCleanupRow("Synthetic episode", AudioCleanupKind.DOWNLOADED,
            if (eligible > 0) AudioCleanupStatus.ELIGIBLE else AudioCleanupStatus.PROTECTED, files, files * 5L)),
        eligibleUnits = eligible,
    )
    private fun result(files: Int = 1, terminal: Boolean = false) = AudioCleanupResult(
        removed = AudioCleanupTotals(downloaded = AudioCleanupCount(files, files * 5L)),
        completedUnits = files, totalUnits = 2, terminal = terminal)
    private fun fixture(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        val owner = SupervisorJob()
        try { block(CoroutineScope(owner + Dispatchers.Unconfined)) }
        finally { owner.cancelAndJoin() }
    }

    @Test fun reviewingDoesNotDeleteAndConfirmRunsOnce() = fixture { scope ->
        val ticket = preview()
        val finish = CompletableDeferred<AudioCleanupResult>()
        var applies = 0
        val controller = AudioCleanupController(scope, { ticket }, { selected, progress ->
            assertSame(ticket, selected); applies++; selected.publish(result()); progress(result()); finish.await()
        }, { fail("Unexpected error") })
        controller.review()
        assertEquals(AudioCleanupPhase.READY, controller.state.value.phase)
        assertEquals(0, applies)
        controller.confirm(); controller.confirm()
        assertEquals(1, applies)
        assertEquals(1, controller.state.value.result!!.removed.files)
        finish.complete(result(2, true))
        assertEquals(AudioCleanupPhase.FINISHED, controller.state.value.phase)
        assertEquals(2, controller.state.value.result!!.removed.files)
        assertEquals(1L, controller.state.value.refreshVersion)
        controller.confirm()
        assertEquals(1, applies)
    }

    @Test fun absentOrProtectedReviewCannotBeConfirmed() = fixture { scope ->
        var applies = 0
        val controller = AudioCleanupController(scope, { preview(eligible = 0) }, { _, _ -> applies++; result() }, {})
        controller.confirm()
        controller.review(); controller.confirm()
        assertEquals(0, applies)
        assertEquals(AudioCleanupPhase.READY, controller.state.value.phase)
    }

    private class LateReview {
        private var continuation: Continuation<AudioCleanupPreview>? = null
        suspend fun await(): AudioCleanupPreview = suspendCoroutine { continuation = it }
        fun complete(value: AudioCleanupPreview) {
            val pending = continuation
            continuation = null
            pending?.resume(value)
        }
    }

    @Test fun cancelledReviewCannotBeRevivedByAnUncooperativeLateResponse() = fixture { scope ->
        val late = LateReview()
        val controller = AudioCleanupController(scope, { late.await() }, { _, _ -> result() }, {})
        try {
            controller.review()
            assertEquals(AudioCleanupPhase.REVIEWING, controller.state.value.phase)
            controller.dismissReview()
            late.complete(preview())
            assertEquals(AudioCleanupPhase.IDLE, controller.state.value.phase)
            assertNull(controller.state.value.preview)
        } finally { late.complete(preview()) }
    }

    @Test fun olderReviewCannotReplaceTheNewerSnapshot() = fixture { scope ->
        val late = LateReview()
        val newer = preview(2)
        var calls = 0
        val controller = AudioCleanupController(scope, {
            if (++calls == 1) late.await() else newer
        }, { _, _ -> result() }, {})
        try {
            controller.review(); controller.review()
            assertSame(newer, controller.state.value.preview)
            late.complete(preview())
            assertSame(newer, controller.state.value.preview)
            controller.dismissReview()
            assertEquals(AudioCleanupPhase.IDLE, controller.state.value.phase)
        } finally { late.complete(preview()) }
    }

    @Test fun reviewFailureIsReportedWithoutPuttingItsPrivateMessageInUi() = fixture { scope ->
        val failure = IllegalStateException("private-episode-url-and-content")
        val reported = mutableListOf<Throwable>()
        val controller = AudioCleanupController(scope, { throw failure }, { _, _ -> result() }, reported::add)
        controller.review()
        assertEquals(AudioCleanupPhase.FAILED, controller.state.value.phase)
        assertFalse(controller.state.value.error!!.contains("private"))
        assertSame(failure, reported.single())
    }

    @Test fun cancellationRetainsAlreadyRemovedFilesAndRefreshesStorage() = fixture { scope ->
        val reported = mutableListOf<Throwable>()
        val finishes = mutableListOf<Pair<AudioCleanupResult?, Boolean>>()
        val controller = AudioCleanupController(scope, { preview() }, { ticket, progress ->
            ticket.publish(result()); progress(result()); awaitCancellation()
        }, reported::add, { s -> finishes += s.result to s.cancelled })
        controller.review(); controller.confirm(); controller.cancel()
        val state = controller.state.value
        assertEquals(AudioCleanupPhase.FINISHED, state.phase)
        assertTrue(state.cancelled)
        assertEquals(1, state.result!!.removed.files)
        assertEquals(1L, state.refreshVersion)
        assertTrue(reported.isEmpty())
        assertEquals(state.result to true, finishes.single())
    }

    @Test fun cancellingBeforeDeletionKeepsAZeroRemovalLedger() = fixture { scope ->
        val controller = AudioCleanupController(scope, { preview() }, { _, _ -> awaitCancellation() }, {})
        controller.review(); controller.confirm(); controller.cancel()
        assertTrue(controller.state.value.cancelled)
        assertEquals(0, controller.state.value.result!!.removed.files)
        assertEquals(1L, controller.state.value.refreshVersion)
    }

    @Test fun applyFailureKeepsPartialSuccessAndReportsOriginalError() = fixture { scope ->
        val failure = IllegalStateException("private-path")
        val reported = mutableListOf<Throwable>()
        val controller = AudioCleanupController(scope, { preview() }, { ticket, progress ->
            ticket.publish(result()); progress(result()); throw failure
        }, reported::add)
        controller.review(); controller.confirm()
        val state = controller.state.value
        assertEquals(AudioCleanupPhase.FAILED, state.phase)
        assertEquals(1, state.result!!.removed.files)
        assertEquals(1L, state.refreshVersion)
        assertFalse(state.error!!.contains("private"))
        assertSame(failure, reported.single())
    }

    @Test fun returnedItemFailuresReportOnceWhileProtectionIsOrdinaryFeedback() = fixture { scope ->
        val first = IllegalStateException("first")
        val second = IllegalStateException("second")
        val reported = mutableListOf<Throwable>()
        val outcome = result().copy(protectedUnits = 1, changedUnits = 1, failedUnits = 2,
            terminal = true, errors = listOf(first, second))
        val controller = AudioCleanupController(scope, { preview() }, { _, _ -> outcome }, reported::add)
        controller.review(); controller.confirm()
        assertSame(outcome, controller.state.value.result)
        assertEquals(listOf(first), reported)
        assertEquals(AudioCleanupPhase.FINISHED, controller.state.value.phase)
    }

    @Test fun leavingTheScreenDropsReviewButDoesNotUndoConfirmedWork() = fixture { scope ->
        val finish = CompletableDeferred<AudioCleanupResult>()
        val controller = AudioCleanupController(scope, { preview() }, { _, _ -> finish.await() }, {})
        controller.review(); controller.leaveScreen()
        assertEquals(AudioCleanupPhase.IDLE, controller.state.value.phase)
        controller.review(); controller.confirm()
        controller.leaveScreen(); controller.dismissReview()
        assertTrue(controller.state.value.running)
        finish.complete(result(2, true))
        assertEquals(2, controller.state.value.result!!.removed.files)
    }

    @Test fun ownerTeardownCancelsOnlyItsCleanupAndRetainsProgress() = runBlocking {
        val owner = SupervisorJob()
        val controller = AudioCleanupController(CoroutineScope(owner + Dispatchers.Unconfined), { preview() }, { ticket, progress ->
            ticket.publish(result()); progress(result()); awaitCancellation()
        }, {})
        controller.review(); controller.confirm()
        owner.cancelAndJoin()
        assertTrue(controller.state.value.cancelled)
        assertEquals(1, controller.state.value.result!!.removed.files)
    }

    @Test fun cancellationDuringSynchronousApplyTargetsTheCurrentJob() = fixture { scope ->
        lateinit var controller: AudioCleanupController
        controller = AudioCleanupController(scope, { preview() }, { ticket, progress ->
            ticket.publish(result()); progress(result())
            controller.cancel()
            currentCoroutineContext().ensureActive()
            result(2, true)
        }, {})
        controller.review(); controller.confirm()
        assertTrue("Reentrant cancellation must reach the active apply job", controller.state.value.cancelled)
        assertEquals(1, controller.state.value.result!!.removed.files)
    }
}
