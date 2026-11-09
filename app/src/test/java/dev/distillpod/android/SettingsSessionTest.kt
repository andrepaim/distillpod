package dev.distillpod.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSessionTest {
    @Test fun repeatedSameFileSessionsPersistEachWriteAfterCompleteShutdown() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = Files.createTempDirectory("settings-session-lifecycle-").toFile()
        val file = File(folder, "test.preferences_pb")
        var expected = true
        try {
            repeat(32) {
                settingsSession(context, file) { settings ->
                    assertEquals(expected, settings.flow.first().transcribeOnPlayback)
                    expected = !expected
                    settings.flag("transcribeOnPlayback", expected)
                }
                assertTrue(file.isFile)
            }
            settingsSession(context, file) { settings ->
                assertEquals(expected, settings.flow.first().transcribeOnPlayback)
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun completedJobJoinCanReturnBeforeAnotherThreadsCompletionHandlerFinishes() = runBlocking {
        val job = SupervisorJob()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanupFinished = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        job.invokeOnCompletion {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            cleanupFinished.set(true)
        }
        val cancellation = executor.submit { job.cancel() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // DataStore closes its connection in such a completion handler.
            // Joining an already-completed Job does not wait for this blocked handler.
            withTimeout(5000) { job.join() }
            assertTrue(job.isCompleted)
            assertFalse(cleanupFinished.get())
        } finally {
            release.countDown()
            try { cancellation.get(5, TimeUnit.SECONDS) }
            finally { executor.shutdownNow() }
        }
        assertTrue(cleanupFinished.get())
    }
}
