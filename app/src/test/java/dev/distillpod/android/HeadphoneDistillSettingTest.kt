package dev.distillpod.android

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeadphoneDistillSettingTest {
    @Test fun `headphone capture defaults off and persists independently of sound`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = Files.createTempDirectory("headphone-settings").toFile()
        suspend fun session(block: suspend (AppSettings) -> Unit) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try { block(AppSettings(context, PreferenceDataStoreFactory.create(scope = scope) { File(folder, "settings.preferences_pb") })) }
            finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
        try {
            session {
                assertEquals(6, it.flow.first().refreshHours)
                assertFalse(it.flow.first().headphoneDistill)
                assertFalse(it.flow.first().headphoneVoice)
                it.refreshHours(12)
                assertTrue(runCatching { it.refreshHours(0) }.isFailure)
                assertTrue(runCatching { it.refreshHours(169) }.isFailure)
                it.flag("headphoneVoice", true)
                it.flag("headphoneDistill", true)
                it.flag("distillSound", false)
            }
            session {
                assertEquals(12, it.flow.first().refreshHours)
                assertTrue(it.flow.first().headphoneDistill)
                assertTrue(it.flow.first().headphoneVoice)
                assertFalse(it.flow.first().distillSound)
                it.flag("headphoneDistill", false)
            }
            session { assertFalse(it.flow.first().headphoneDistill); assertTrue(it.flow.first().headphoneVoice) }
        } finally { folder.deleteRecursively() }
    }
}
