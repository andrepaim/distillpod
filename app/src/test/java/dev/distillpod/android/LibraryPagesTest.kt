package dev.distillpod.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.data.ShowSummary
import dev.distillpod.android.features.*
import dev.distillpod.android.playback.PlaybackState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import dev.distillpod.android.ui.theme.DistillTheme

/** #146: Library, show and episode page consistency and density on a 1080x2316 phone (about 384x823 dp). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h823dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryPagesTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()
    private val actions = EpisodeActions(
        open = { calls += "open:$it" }, play = { calls += "play:$it" }, toggle = { calls += "toggle" },
        enqueue = { id, next -> calls += if (next) "next:$id" else "queue:$id" },
        markPlayed = { id, played -> calls += "played:$id:$played" },
        download = { calls += "download:$it" }, pauseDownload = { calls += "pause:$it" }, deleteDownload = { calls += "delete:$it" })
    private val show = PodcastEntity("p1", "https://example.test/one", "The Synthetic Listening Room")
    private val longTitle = "A deliberately long synthetic episode title about attention, curiosity, practice and the ideas we carry into the week"
    private fun episode(id: String, title: String = longTitle) = EpisodeEntity(id, "p1", id, title, "https://example.test/$id",
        publishedAt = Instant.parse("2026-11-03T12:00:00Z").toEpochMilli(), durationMs = 3_600_000)

    private fun SemanticsNodeInteraction.dp() = getBoundsInRoot()

    @Test fun `episode row keeps play and more actions on its main row and is compact`() {
        // Worst case: long title, progress bar and several status labels.
        val ep = episode("e1").copy(positionMs = 600_000, lastPlayedAt = 1)
        compose.setContent {
            DistillTheme { Box(Modifier.size(344.dp, 400.dp)) {
                EpisodeRow(ep, show, PlaybackState(), transcriptReady = true, captureCount = 2, showPodcast = true, deletionBlock = null, actions = actions)
            } }
        }
        val row = compose.onNodeWithTag("episode-row").dp()
        // The old layout was 185 dp tall here: a second, mostly empty row held download and the menu.
        assertTrue("row is ${row.height} tall", row.height <= 136.dp)
        // Download is not a separate button row any more.
        assertTrue(compose.onAllNodesWithContentDescription("Download $longTitle").fetchSemanticsNodes().isEmpty())
        val play = compose.onNodeWithContentDescription("Play $longTitle").dp()
        val more = compose.onNodeWithContentDescription("More actions for $longTitle").dp()
        val centre = { b: androidx.compose.ui.unit.DpRect -> (b.top + b.bottom) / 2 }
        assertTrue("play and more share a row", abs((centre(play) - centre(more)).value) < 4f)
        compose.onNodeWithText("The Synthetic Listening Room").assertIsDisplayed()
        // Status joins the date line instead of a separate row of labels.
        compose.onNodeWithText("${episodeDate(ep.publishedAt)} · 50 min left · Transcript · 2 saved").assertIsDisplayed()

        compose.onNodeWithContentDescription("More actions for $longTitle").performClick()
        listOf("Play next", "Add to queue", "Download", "Mark played").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Download").performClick()
        compose.onNodeWithContentDescription("Play $longTitle").performClick()
        compose.onNodeWithTag("episode-row").performClick()
        assertEquals(listOf("download:e1", "play:e1", "open:e1"), calls)
    }

    @Test fun `a typical row with a two-line title is under 100 dp`() {
        val ep = episode("e5", "Episode 212: patient listening").copy(localFile = "/media/e5")
        compose.setContent {
            DistillTheme { Box(Modifier.size(344.dp, 400.dp)) {
                EpisodeRow(ep, show, PlaybackState(), transcriptReady = false, captureCount = 0, showPodcast = true, deletionBlock = null, actions = actions)
            } }
        }
        val row = compose.onNodeWithTag("episode-row").dp()
        // The old layout was 154 dp for the same row.
        assertTrue("row is ${row.height} tall", row.height <= 100.dp)
    }

    @Test fun `show page rows omit the repeated show name and menu reflects download state`() {
        val downloading = episode("e2", "Short title").copy(downloadState = "downloading", downloadedBytes = 42, totalBytes = 100)
        val offline = episode("e3", "Offline title").copy(localFile = "/media/e3", completed = true)
        compose.setContent {
            DistillTheme { androidx.compose.foundation.layout.Column {
                EpisodeRow(downloading, show, PlaybackState(), false, 0, showPodcast = false, deletionBlock = null, actions = actions)
                EpisodeRow(offline, show, PlaybackState(), false, 0, showPodcast = false, deletionBlock = "Queued episodes keep their audio.", actions = actions)
            } }
        }
        assertTrue(compose.onAllNodesWithText("The Synthetic Listening Room").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("· 42%", substring = true).assertIsDisplayed()
        compose.onNodeWithText("· Played · Offline", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("More actions for Short title").performClick()
        compose.onNodeWithText("Pause download").performClick()
        compose.onNodeWithContentDescription("More actions for Offline title").performClick()
        compose.onNodeWithText("Mark unplayed").assertIsDisplayed()
        compose.onNodeWithText("Delete download").assertIsNotEnabled()
        compose.onNodeWithText("Queued episodes keep their audio.").assertIsDisplayed()
        compose.onNodeWithText("Mark unplayed").performClick()
        assertEquals(listOf("pause:e2", "played:e3:false"), calls)
    }

    @Test fun `library names each failing show with its reason and actions`() {
        val problems = listOf(
            show.copy(error = "Couldn't reach the feed. Check your connection and try again.", refreshedAt = Instant.parse("2026-11-01T12:00:00Z").toEpochMilli()),
            PodcastEntity("p2", "https://example.test/two", "Second Synthetic Show", error = "The feed address no longer works (HTTP 404)."))
        compose.setContent {
            DistillTheme { FeedProblems(problems, showPage = false, refreshing = false, onRetry = { calls += "retry:$it" },
                onOpen = { calls += "open:$it" }, onUnsubscribe = { calls += "unsubscribe:$it" }) }
        }
        compose.onNodeWithText("2 shows couldn't refresh", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Show details").performClick()
        compose.onNodeWithText("The Synthetic Listening Room").assertIsDisplayed()
        compose.onNodeWithText("Couldn't reach the feed. Check your connection and try again.").assertIsDisplayed()
        compose.onNodeWithText("Last updated ${episodeDate(problems[0].refreshedAt)}", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Second Synthetic Show").assertIsDisplayed()
        compose.onNodeWithText("The feed address no longer works (HTTP 404).").assertIsDisplayed()
        compose.onNodeWithContentDescription("Retry The Synthetic Listening Room").performClick()
        compose.onNodeWithContentDescription("Open Second Synthetic Show").performClick()
        compose.onNodeWithContentDescription("Unsubscribe from Second Synthetic Show").performClick()
        // Unfollowing syncs to other devices, so it is confirmed first.
        assertEquals(listOf("retry:p1", "open:p2"), calls)
        compose.onNode(hasText("Unsubscribe") and hasAnyAncestor(isDialog())).assertIsDisplayed().performClick()
        assertEquals(listOf("retry:p1", "open:p2", "unsubscribe:p2"), calls)
    }

    @Test fun `a single failing show is shown directly and a show page has only its own error`() {
        val failing = show.copy(error = "The feed refused access (HTTP 403).")
        compose.setContent {
            DistillTheme { androidx.compose.foundation.layout.Column {
                Box(Modifier.testTag("library")) { FeedProblems(listOf(failing), showPage = false, refreshing = true, onRetry = {}, onOpen = {}, onUnsubscribe = {}) }
                Box(Modifier.testTag("show")) { FeedProblems(listOf(failing), showPage = true, refreshing = false, onRetry = { calls += "retry:$it" }, onOpen = {}, onUnsubscribe = {}) }
            } }
        }
        compose.onNode(hasText("The Synthetic Listening Room") and hasAnyAncestor(hasTestTag("library"))).assertIsDisplayed()
        // A refresh in progress disables retry.
        compose.onNode(hasContentDescription("Retry The Synthetic Listening Room") and hasAnyAncestor(hasTestTag("library"))).assertIsNotEnabled()
        compose.onNode(hasText("This show couldn't refresh", substring = true) and hasAnyAncestor(hasTestTag("show"))).assertIsDisplayed()
        compose.onNode(hasText("The feed refused access (HTTP 403).") and hasAnyAncestor(hasTestTag("show"))).assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasContentDescription("Unsubscribe from The Synthetic Listening Room") and hasAnyAncestor(hasTestTag("show"))).fetchSemanticsNodes().isEmpty())
        compose.onNode(hasContentDescription("Retry The Synthetic Listening Room") and hasAnyAncestor(hasTestTag("show"))).performClick()
        assertEquals(listOf("retry:p1"), calls)
    }

    @Test fun `episode page puts queue and played actions near the top and the description before tools`() {
        val description = (1..40).joinToString(" ") { "Synthetic show note sentence $it." }
        val ep = episode("e4", "Episode page title").copy(description = description)
        val metadata = episodeMetadataText(ep, null)
        compose.setContent {
            DistillTheme { Box(Modifier.size(384.dp, 2000.dp)) {
                EpisodeDetail(ep, show, metadata, resumeAt = 0, actions = actions, onOpenShow = {}, onRetryDetails = {},
                    modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Transcript tools fixture", Modifier.testTag("episode-tools"))
                }
            } }
        }
        val play = compose.onNodeWithText("Play episode").dp()
        val queue = compose.onNodeWithText("Add to queue").dp()
        val played = compose.onNodeWithText("Mark played").dp()
        val heading = compose.onNodeWithText("Description").dp()
        val tools = compose.onNodeWithTag("episode-tools").dp()
        assertTrue(play.top < queue.top && queue.bottom <= heading.top && played.bottom <= heading.top)
        assertTrue("description precedes transcript and AI tools", heading.bottom <= tools.top)
        // Long show notes are collapsed so the tools stay reachable.
        compose.onNodeWithText("Show more").assertIsDisplayed().performClick()
        compose.onNodeWithText("Show less").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Mark played").performScrollTo().performClick()
        compose.onNodeWithText("Play next").performScrollTo().performClick()
        compose.onNodeWithText("Add to queue").performScrollTo().performClick()
        compose.onNodeWithText("Download", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Play episode").performScrollTo().performClick()
        assertEquals(listOf("played:e4:true", "next:e4", "queue:e4", "download:e4", "play:e4"), calls)
    }

    @Test fun `show cards use the same date format as episode rows`() {
        val now = Instant.parse("2026-11-12T15:00:00Z").toEpochMilli()
        val zone = ZoneId.of("America/Sao_Paulo")
        fun label(published: Long, count: Int = 3) = latestEpisodeLabel(ShowSummary(show, episodeCount = count, latestPublishedAt = published), now, zone, Locale.ENGLISH)
        assertEquals("Latest: 12 Nov", label(Instant.parse("2026-11-12T12:00:00Z").toEpochMilli()))
        assertEquals("Latest: 3 Feb 2019", label(Instant.parse("2019-02-03T12:00:00Z").toEpochMilli()))
        assertEquals("Episode date unavailable", label(0))
        assertEquals("No episodes yet", label(0, count = 0))
    }

    @Test fun `distilling needs audio before the current position`() {
        assertFalse(canCaptureMoment(PlaybackState()))
        assertFalse(canCaptureMoment(PlaybackState("e", positionMs = 0)))
        assertFalse(canCaptureMoment(PlaybackState("e", positionMs = 999)))
        assertTrue(canCaptureMoment(PlaybackState("e", positionMs = 1_000)))
        assertFalse(canCaptureMoment(PlaybackState(null, positionMs = 60_000)))
    }
}
