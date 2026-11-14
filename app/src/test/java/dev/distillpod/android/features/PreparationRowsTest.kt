package dev.distillpod.android.features

import dev.distillpod.android.data.EpisodeAutomationEntity
import dev.distillpod.android.data.EpisodeEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PreparationRowsTest {
    @Test fun attentionUsesAllJobsForThisShowAndOnlyRetryableEpisodes() {
        val jobs = listOf(
            EpisodeAutomationEntity("download", "show", "downloading"),
            EpisodeAutomationEntity("blocked", "show", "blocked"),
            EpisodeAutomationEntity("ready", "show", "ready"),
            EpisodeAutomationEntity("working", "show", "transcribing"),
            EpisodeAutomationEntity("other", "other-show", "blocked"),
        )
        val download = EpisodeEntity("download", "show", "guid", "Long episode title", "https://example.test/audio", downloadState = "failed")

        val rows = preparationRows("show", jobs, listOf(download), emptyList(), emptyList())

        assertEquals(listOf("download", "blocked"), rows.filter { it.retryable }.map { it.episodeId })
        assertEquals("Long episode title", rows.first().title)
        assertEquals("Download failed", rows.first().status)
        assertEquals("Ready", rows.single { it.episodeId == "ready" }.status)
        assertEquals(emptyList<PreparationRow>(), preparationRows("no-jobs", jobs, emptyList(), emptyList(), emptyList()))
        assertEquals(listOf("blocked"), preparationRows("show", jobs, listOf(download.copy(downloadState = "downloading")),
            emptyList(), emptyList()).filter { it.retryable }.map { it.episodeId })
    }
}
