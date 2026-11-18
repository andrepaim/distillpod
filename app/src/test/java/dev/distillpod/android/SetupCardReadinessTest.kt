package dev.distillpod.android

import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.ModelEntity
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.data.whisperModels
import dev.distillpod.android.features.setupLibraryEpisode
import dev.distillpod.android.features.setupModel
import dev.distillpod.core.SetupModelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupCardReadinessTest {
    @Test fun `Tiny does not satisfy Base selection and a stale Base row can be downloaded again`() {
        val rows = listOf(ModelEntity("tiny", "ready"), ModelEntity("base", "ready"))
        var installed = setOf("tiny")
        val base = whisperModels.single { it.id == "base" }

        assertEquals(SetupModelStatus.READY, setupModel("tiny", rows) { it.id in installed }.status)
        val missing = setupModel("base", rows) { it.id in installed }
        assertEquals(base, missing.spec)
        assertEquals(SetupModelStatus.MISSING, missing.status)

        installed = setOf("tiny", "base")
        assertEquals(SetupModelStatus.READY, setupModel("base", rows) { it.id in installed }.status)
    }

    @Test fun `a transient search episode and broken enclosures are skipped for a Library episode`() {
        val subscribed = PodcastEntity("followed", "https://fixture.example/followed", "Followed")
        val transient = subscribed.copy(id = "unfollowed", subscribed = false)
        val searchResult = episode("search-result", transient.id)
        val missingAudio = episode("broken", subscribed.id, audio = "")
        val validEpisode = episode("valid", subscribed.id)
        assertEquals(validEpisode.id, setupLibraryEpisode(listOf(subscribed, transient), listOf(searchResult, missingAudio, validEpisode)) { false })
        assertNull(setupLibraryEpisode(listOf(subscribed, transient), listOf(searchResult, missingAudio)) { false })
    }

    @Test fun `an unfollowed episode with a usable offline file is still in the Library`() {
        val offline = episode("offline", "unfollowed", audio = "").copy(localFile = "recording.mp3")
        assertEquals(offline.id, setupLibraryEpisode(emptyList(), listOf(offline)) { it == "recording.mp3" })
        assertNull(setupLibraryEpisode(emptyList(), listOf(offline)) { false })
    }

    private fun episode(id: String, show: String, audio: String = "https://fixture.example/$id.mp3") =
        EpisodeEntity(id, show, id, "Fixture episode", audio)
}
