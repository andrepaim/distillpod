package dev.distillpod.android

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.AppSettings
import dev.distillpod.core.SpeedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** #183: skip silence, volume boost and speeds are device-local DataStore preferences; Room is untouched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackEffectsSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `effects default off and survive reopening`() = runTest {
        val directory = Files.createTempDirectory("playback-effects").toFile()
        val file = File(directory, "settings.preferences_pb")
        try {
            settingsSession(context, file) { settings ->
                val initial = settings.flow.first()
                assertFalse(initial.skipSilence)
                assertFalse(initial.volumeBoost)
                settings.flag("skipSilence", true)
                settings.flag("volumeBoost", true)
            }
            settingsSession(context, file) { settings ->
                val reopened = settings.flow.first()
                assertTrue(reopened.skipSilence)
                assertTrue(reopened.volumeBoost)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun `speed choices are stored atomically and survive reopening`() = runTest {
        val directory = Files.createTempDirectory("playback-speeds").toFile()
        val file = File(directory, "settings.preferences_pb")
        try {
            settingsSession(context, file) { settings ->
                assertEquals(SpeedPreferences(), settings.speeds.first())
                coroutineScope {
                    launch { settings.updateSpeeds { it.keep("show-a", 1.7f) } }
                    launch { settings.updateSpeeds { it.keep("show-b", 2.2f) } }
                }
                settings.updateSpeeds { it.chosen("show-c", 1.3f) }
            }
            settingsSession(context, file) { settings ->
                assertEquals(SpeedPreferences(1.3f, mapOf("show-a" to 1.7f, "show-b" to 2.2f)), settings.speeds.first())
                settings.updateSpeeds { it.forget("show-a") }
                assertEquals(SpeedPreferences(1.3f, mapOf("show-b" to 2.2f)), settings.speeds.first())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun `unsupported stored speeds are ignored`() = runTest {
        val directory = Files.createTempDirectory("playback-speeds-invalid").toFile()
        val file = File(directory, "settings.preferences_pb")
        try {
            val job = SupervisorJob()
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))) { file }
            store.edit {
                it[floatPreferencesKey("playbackSpeed")] = 9f
                it[stringPreferencesKey("showSpeeds")] = """{"ok":1.5,"slow":0.1,"text":"fast","nan":"NaN"}"""
            }
            assertEquals(SpeedPreferences(null, mapOf("ok" to 1.5f)), AppSettings(context, store).speeds.first())
            store.edit { it[stringPreferencesKey("showSpeeds")] = "not json" }
            assertEquals(SpeedPreferences(), AppSettings(context, store).speeds.first())
            job.cancelAndJoin(); runCurrent()
        } finally { directory.deleteRecursively() }
    }
}
