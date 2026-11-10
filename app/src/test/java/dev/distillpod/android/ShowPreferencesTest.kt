package dev.distillpod.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShowPreferencesTest {
    @Test fun `format sort and pins survive reopening without changing other preferences`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = Files.createTempDirectory("shows-preferences").toFile()
        val file = File(directory, "settings.preferences_pb")
        try {
            settingsSession(context, file) { settings ->
                assertEquals(ShowPreferences(), settings.shows.first())
                settings.flag("wifi", false)
                settings.showFormat(ShowFormat.VIDEO)
                settings.showSort(ShowSort.LATEST_EPISODE)
                coroutineScope {
                    launch { settings.pinShow("https://example.test/a", true) }
                    launch { settings.pinShow("https://example.test/b", true) }
                }
            }
            settingsSession(context, file) { settings ->
                assertEquals(ShowPreferences(ShowFormat.VIDEO, ShowSort.LATEST_EPISODE,
                    setOf("https://example.test/a", "https://example.test/b")), settings.shows.first())
                assertFalse(settings.flow.first().wifiOnly)
                settings.pinShow("https://example.test/a", false)
            }
            settingsSession(context, file) { settings ->
                assertEquals(setOf("https://example.test/b"), settings.shows.first().pinnedFeeds)
                assertEquals(ShowSort.LATEST_EPISODE, settings.shows.first().sort)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun `unknown saved display values fall back safely`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = Files.createTempDirectory("shows-preferences-invalid").toFile()
        try {
            settingsSession(context, File(directory, "settings.preferences_pb")) { settings ->
                settings.text("showsFormat", "future-format")
                settings.text("showsSort", "future-sort")
                assertEquals(ShowPreferences(), settings.shows.first())
            }
        } finally { directory.deleteRecursively() }
    }
}
