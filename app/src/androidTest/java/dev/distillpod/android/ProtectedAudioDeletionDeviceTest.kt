package dev.distillpod.android

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.distillpod.android.data.*
import dev.distillpod.android.features.AppViewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic fixtures only; physical devices require the exact offline validation package. */
@RunWith(AndroidJUnit4::class)
class ProtectedAudioDeletionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun protectedAudioExplainsDisabledActionsAndRetriesAfterOwnershipRelease() = runBlocking<Unit> {
        val context = compose.activity.applicationContext
        check(context.packageName == "dev.distillpod.android.validation.issue58" ||
            Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")) {
            "Physical acceptance requires the isolated issue58 application"
        }
        val graph = context.appGraph
        graph.ready.await()
        val dao = graph.db.library()
        check(dao.queue().isEmpty() && graph.playback.state.value.queue.isEmpty()) {
            "Use an empty validation queue"
        }
        val id = "protected-audio-" + UUID.randomUUID()
        val audio = File(graph.media.directory, "$id.audio")
        val partial = File(graph.media.directory, "$id.partial")
        val before = EpisodeEntity(id, id, id, "Protected audio fixture", "https://example.test/audio",
            localFile = audio.absolutePath, downloadState = "downloaded", downloadedBytes = 5,
            etag = "fixture-etag", lastModified = "fixture-date", positionMs = 1234)
        val transcript = TranscriptEntity(id, "r", "fixture", "tiny", "en", state = "ready")
        val delete = hasText("Delete download") and hasAnyAncestor(hasTestTag("storage-download-$id"))
        fun feedback(reason: AudioDeletionBlockReason) {
            compose.waitUntil(10000) { compose.onAllNodesWithText(reason.feedback).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(reason.feedback).performScrollTo().assertIsDisplayed()
            compose.onNode(delete).performScrollTo().assertIsNotEnabled()
        }
        suspend fun retained() {
            assertEquals("audio", audio.readText())
            assertEquals("resume", partial.readText())
            assertEquals(1234L, dao.episode(id)!!.positionMs)
            assertEquals("fixture-etag", dao.episode(id)!!.etag)
        }
        suspend fun trace(stage: String, vm: AppViewModel) {
            println("Protected audio fixture $stage: reason=${vm.audioDeletionBlocks.value[id]}, " +
                "dbQueued=${dao.queue().any { it.episodeId == id }}, " +
                "controllerQueued=${id in graph.playback.state.value.queue}, " +
                "activeQueued=${audio.absolutePath in graph.media.active.queued}, " +
                "transcript=${dao.transcript(id)?.state}")
        }
        var acquired = false
        var audioAcquired = false
        try {
            audio.writeText("audio"); partial.writeText("resume")
            dao.putPodcast(PodcastEntity(id, "https://example.test/$id", "Protected audio fixture"))
            dao.putEpisode(before); dao.putTranscript(transcript)
            dao.insertQueue(listOf(QueueEntryEntity(id, 0)))
            val vm = withContext(Dispatchers.Main) {
                ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java]
                    .also { it.root("library") }
            }
            compose.waitUntil(15000) {
                vm.episodes.value.any { it.id == id } &&
                    vm.audioDeletionBlocks.value[id] == AudioDeletionBlockReason.QUEUED
            }
            compose.onNode(hasScrollToNodeAction())
                .performScrollToNode(hasContentDescription("More actions for Protected audio fixture"))
            compose.onNodeWithContentDescription("More actions for Protected audio fixture").assertIsDisplayed().performClick()
            compose.onNodeWithText("Delete download").assertIsNotEnabled()
            compose.onNodeWithText(AudioDeletionBlockReason.QUEUED.feedback).assertIsDisplayed()
            androidx.test.espresso.Espresso.pressBack()
            withContext(Dispatchers.Main) { vm.open("settings:storage") }
            compose.waitUntil(15000) { compose.onAllNodesWithText("Manage downloads").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Manage downloads").performScrollTo().performClick()
            feedback(AudioDeletionBlockReason.QUEUED)
            assertTrue(runCatching { graph.media.deleteDownload(id) }.exceptionOrNull() is AudioDeletionBlocked)
            retained()
            check(dao.queue() == listOf(QueueEntryEntity(id, 0)))
            // The real service restores DAO entries into Media3; deleting only the DAO row
            // does not release that ownership and the next service checkpoint restores it.
            withTimeout(10000) { graph.playback.state.first { id in it.queue } }
            trace("before controller clear", vm)
            graph.playback.clear()
            withTimeout(10000) {
                while (id in graph.playback.state.value.queue || dao.queue().any { it.episodeId == id } ||
                    graph.media.active.busy(audio.absolutePath)) delay(50)
            }
            trace("after controller clear", vm)
            dao.putTranscript(transcript.copy(state = "processing"))
            feedback(AudioDeletionBlockReason.TRANSCRIBING)
            retained()
            dao.putTranscript(transcript)
            dao.putEpisode(before.copy(downloadState = "downloading"))
            feedback(AudioDeletionBlockReason.DOWNLOADING)
            retained()
            dao.putEpisode(before)
            // Processing ownership is stable while held; PlaybackService owns its playback pointer.
            graph.media.active.acquire(audio)
            audioAcquired = true
            feedback(AudioDeletionBlockReason.ACTIVE)
            retained()
            graph.media.active.release(audio)
            audioAcquired = false
            compose.waitUntil(10000) { vm.audioDeletionBlocks.value[id] == null }
            compose.onNode(delete).performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithText("Delete downloaded audio?").assertIsDisplayed()
            // Ownership can change after opening confirmation; UI updates but repository remains authoritative.
            graph.media.active.acquire(partial)
            acquired = true
            compose.waitUntil(10000) { vm.audioDeletionBlocks.value[id] == AudioDeletionBlockReason.ACTIVE }
            compose.onNode(hasText("Delete download") and hasAnyAncestor(isDialog())).assertIsNotEnabled()
            compose.onNodeWithText("Cancel").performClick()
            withContext(Dispatchers.Main) {
                check("delete:$id" !in vm.busy.value)
                vm.deleteDownload(id)
            }
            // Existing disabled-action text is not proof the async command has finished.
            // Keep the owner held until this exact action's finally block clears its busy key.
            compose.waitUntil(10000) { "delete:$id" !in vm.busy.value }
            compose.waitUntil(10000) { compose.onAllNodesWithText(AudioDeletionBlockReason.ACTIVE.feedback).fetchSemanticsNodes().isNotEmpty() }
            trace("after blocked action completion", vm)
            retained()
            graph.media.active.release(partial)
            acquired = false
            compose.waitUntil(10000) { vm.audioDeletionBlocks.value[id] == null && vm.busy.value.isEmpty() }
            compose.onNode(delete).performScrollTo().assertIsEnabled().performClick()
            compose.onNode(hasText("Delete download") and hasAnyAncestor(isDialog())).performClick()
            withTimeout(10000) { while (dao.episode(id)?.localFile != null) delay(50) }
            assertFalse(audio.exists()); assertFalse(partial.exists())
            assertEquals(1234L, dao.episode(id)!!.positionMs)
            assertEquals(transcript, dao.transcript(id))
        } finally {
            if (acquired) graph.media.active.release(partial)
            if (audioAcquired) graph.media.active.release(audio)
            // Remove only this fixture from the real controller, including after a failed assertion.
            graph.playback.remove(id)
            withTimeout(10000) { graph.playback.state.first { id !in it.queue } }
            dao.deletePodcast(id)
            audio.delete(); partial.delete()
        }
    }
}
