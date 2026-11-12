package dev.distillpod.android

import dev.distillpod.android.data.*
import dev.distillpod.android.work.DOWNLOAD_BLOCKED_MESSAGE
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** #140: automation runs only when state that its coordinator acts on changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShowAutomationKickTest {
    private val jobs = MutableStateFlow(listOf(EpisodeAutomationEntity("e", "p", "downloading")))
    private val shows = MutableStateFlow(listOf(ShowAutomationEntity("p", 0, true)))
    private val podcasts = MutableStateFlow(listOf(PodcastEntity("p", "https://example.test/rss", "Show")))
    private val episodes = MutableStateFlow(listOf(
        EpisodeEntity("e", "p", "g", "Episode", "https://example.test/e.mp3", downloadState = "queued", mediaFingerprint = "f"),
        EpisodeEntity("other", "p", "o", "Playing", "https://example.test/o.mp3", localFile = "/o.mp3", downloadState = "downloaded")))
    private val transcripts = MutableStateFlow(emptyList<TranscriptEntity>())
    private val analyses = MutableStateFlow(emptyList<AnalysisEntity>())
    private val connected = MutableStateFlow(false)

    private fun episode(id: String, change: (EpisodeEntity) -> EpisodeEntity) =
        episodes.update { rows -> rows.map { if (it.id == id) change(it) else it } }

    private fun kicks(block: suspend TestScope.(settled: () -> Int) -> Unit) = runTest {
        var count = 0
        backgroundScope.launch {
            automationKickRequests(jobs, shows, podcasts, episodes, transcripts, analyses, connected).collect { count++ }
        }
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        block { advanceTimeBy(1_000); runCurrent(); count }
    }

    @Test fun `pending work is checked once on start`() = kicks { settled ->
        assertEquals(1, settled())
    }

    @Test fun `position only writes do not kick`() = kicks { settled ->
        repeat(30) { second ->
            episode("other") { it.copy(positionMs = second * 1_000L, lastPlayedAt = second.toLong()) }
            episode("e") { it.copy(positionMs = second * 10L) }
            advanceTimeBy(1_000)
        }
        assertEquals(1, settled())
    }

    @Test fun `progress inside an active stage does not kick`() = kicks { settled ->
        episode("e") { it.copy(downloadState = "downloading", downloadedBytes = 10, totalBytes = 100) }; settled()
        episode("e") { it.copy(downloadedBytes = 90) }; settled()
        episode("e") { it.copy(downloadState = "queued") }
        assertEquals(1, settled())
        episode("e") { it.copy(localFile = "/e.mp3", downloadState = "downloaded", downloadedBytes = 100) }
        assertEquals(2, settled())
        transcripts.value = listOf(TranscriptEntity("e", "r", "f", "tiny", "en", state = "queued"))
        assertEquals(3, settled())
        transcripts.update { rows -> rows.map { it.copy(state = "processing", processedMs = 120_000) } }; settled()
        transcripts.update { rows -> rows.map { it.copy(state = "queued", processedMs = 120_000) } }; settled()
        transcripts.update { rows -> rows.map { it.copy(state = "processing", processedMs = 240_000) } }
        assertEquals(3, settled())
        transcripts.update { rows -> rows.map { it.copy(state = "ready") } }
        assertEquals(4, settled())
    }

    @Test fun `blockers stages and sign-in changes kick`() = kicks { settled ->
        episode("e") { it.copy(downloadState = "paused", error = DOWNLOAD_BLOCKED_MESSAGE) }
        assertEquals(2, settled())
        // The coordinator's own transition into a working or blocked stage does not wake it again.
        jobs.value = listOf(EpisodeAutomationEntity("e", "p", "blocked"))
        assertEquals(2, settled())
        jobs.value = listOf(EpisodeAutomationEntity("e", "p", "retry"))
        assertEquals(3, settled())
        connected.value = true
        assertEquals(4, settled())
        podcasts.update { rows -> rows.map { it.copy(subscribed = false) } }
        assertEquals(5, settled())
    }

    @Test fun `ready jobs and unrelated episodes do not kick`() = kicks { settled ->
        jobs.value = listOf(EpisodeAutomationEntity("e", "p", "ready"))
        settled()
        val after = settled()
        episode("other") { it.copy(downloadState = "none", localFile = null) }
        connected.value = true
        assertEquals(after, settled())
    }
}
