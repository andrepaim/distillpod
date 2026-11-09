package dev.distillpod.android

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.distillpod.android.data.*
import dev.distillpod.android.features.AppViewModel
import dev.distillpod.android.features.ProductApp
import dev.distillpod.android.features.fileSize
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Ordinary isolated CI acceptance. Never run this fixture on an owner's phone. */
@RunWith(AndroidJUnit4::class)
class StorageSettingsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun largeTextStorageShowsActualUsageAndConfirmsSelectedPartialCleanup() = runBlocking<Unit> {
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")) { "Use a disposable test emulator" }
        val graph = compose.activity.applicationContext.appGraph
        graph.ready.await()
        val id = "storage-ui-${UUID.randomUUID()}"
        val dao = graph.db.library()
        val prefs = graph.settings.flow.first()
        val partial = File(graph.media.directory, "$id.partial")
        val model = File(graph.media.modelsDirectory, "$id.fixture")
        val baseline = graph.media.storageUsage()
        try {
            RandomAccessFile(partial, "rw").use { it.setLength(8_000_000) }
            RandomAccessFile(model, "rw").use { it.setLength(3_000_000) }
            dao.putPodcast(PodcastEntity(id, "https://example.test/$id", "Storage fixture"))
            dao.putEpisode(EpisodeEntity(id, id, id, "Selected storage fixture", "https://example.test/audio",
                downloadState = "paused", downloadedBytes = 8_000_000, positionMs = 1234))
            withContext(Dispatchers.Main) {
                val vm = ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java]
                vm.open("settings:storage")
                compose.activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(graph.context.resources.displayMetrics.density, 1.6f)) {
                        ProductApp(vm)
                    }
                }
            }
            val quotaBefore = "Audio quota used: ${fileSize(baseline.quotaBytes + 8_000_000)} of ${fileSize(prefs.storageLimitBytes)}"
            compose.waitUntil(15000) { compose.onAllNodesWithText(quotaBefore).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(quotaBefore).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Partial downloads and cache: ${fileSize(baseline.partialCacheBytes + 8_000_000)}")
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Transcription models: ${fileSize(baseline.modelBytes + 3_000_000)} · separate from the audio limit")
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Lowering the audio limit does not delete files.", substring = true)
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Manage transcription models").performScrollTo().performClick()
            compose.onNodeWithText("Transcribe when playing").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Manage downloads").performScrollTo().performClick()
            val discard = hasText("Discard paused partial") and hasAnyAncestor(hasTestTag("storage-download-$id"))
            compose.onNode(discard).performScrollTo().performClick()
            compose.onNodeWithText("Discard paused download?").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            assertTrue(partial.exists())
            assertEquals("paused", dao.episode(id)!!.downloadState)
            compose.onNode(discard).performScrollTo().performClick()
            compose.onNodeWithText("Discard partial").performClick()
            withTimeout(15000) { while (dao.episode(id)?.downloadState != "none") delay(100) }
            assertFalse(partial.exists())
            assertEquals(1234L, dao.episode(id)!!.positionMs)
            val quotaAfter = "Audio quota used: ${fileSize(baseline.quotaBytes)} of ${fileSize(prefs.storageLimitBytes)}"
            compose.waitUntil(15000) { compose.onAllNodesWithText(quotaAfter).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(quotaAfter).performScrollTo().assertIsDisplayed()
            assertTrue(model.exists())
        } finally {
            dao.deletePodcast(id)
            partial.delete()
            model.delete()
        }
    }
}
