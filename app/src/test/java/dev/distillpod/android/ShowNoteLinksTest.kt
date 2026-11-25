package dev.distillpod.android

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import dev.distillpod.android.features.*
import dev.distillpod.android.ui.theme.DistillTheme
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #308: links in show notes survive storage, render as accessible links, open their exact source address, and fail gracefully. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h823dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShowNoteLinksTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val buy = "https://example.org/buy?id=1&ref=a%20b"
    private val show = PodcastEntity("p1", "https://example.test/one", "The Synthetic Listening Room")
    private fun episode(description: String) = EpisodeEntity("e1", "p1", "g1", "Synthetic episode", "https://example.test/e1.mp3", description = description,
        publishedAt = 1, durationMs = 3_600_000)

    private fun SemanticsNodeInteraction.links(): List<Pair<String, String>> {
        val text = fetchSemanticsNode().config[SemanticsProperties.Text].single()
        return text.getLinkAnnotations(0, text.length).map { text.substring(it.start, it.end) to (it.item as LinkAnnotation.Url).url }
    }
    private fun SemanticsNodeInteraction.tapLink(start: Int) {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val box = layouts.first().getBoundingBox(start)
        performTouchInput { click(box.center) }
    }

    @Test fun `the About section shows source and plain links that open their exact address, collapsed and expanded`() {
        val opened = mutableListOf<String>()
        val filler = (1..40).joinToString(" ") { "Synthetic show note sentence $it." }
        val stored = dev.distillpod.core.ShowNotes.fromHtml(
            """<p>A Casa dos Malditos <a href="https://example.org/buy?id=1&amp;ref=a%20b"><strong>(link para</strong> <em>compra)</em></a></p><p>More at https://example.org/plain.</p><p>$filler</p>""")
        val ep = episode(stored)
        compose.setContent {
            DistillTheme { Box(Modifier.size(384.dp, 2000.dp)) {
                EpisodeDetail(ep, show, episodeMetadataText(ep, null), resumeAt = 0, actions = EpisodeActions(openLink = { opened += it }),
                    onOpenShow = {}, onRetryDetails = {}, modifier = Modifier.verticalScroll(rememberScrollState())) {}
            } }
        }
        val about = compose.onNodeWithText("A Casa dos Malditos", substring = true)
        val expected = listOf("(link para compra)" to buy, "https://example.org/plain" to "https://example.org/plain")
        assertEquals(expected, about.links())
        about.tapLink("A Casa dos Malditos ".length + 1)
        assertEquals(listOf(buy), opened)
        compose.onNodeWithText("More").performClick()
        assertEquals(expected, about.links())
        compose.onNodeWithText("Less").performScrollTo().performClick()
        compose.onNodeWithText("A Casa dos Malditos", substring = true).tapLink("A Casa dos Malditos ".length + 1)
        assertEquals(listOf(buy, buy), opened)
    }

    @Test fun `show notes keep unsafe destinations as plain text`() {
        compose.setContent { DistillTheme { ShowNotesText("Before [x](<javascript:alert(1)>) and javascript:alert(2) after", onLink = {}) } }
        assertTrue(compose.onNodeWithText("Before", substring = true).links().isEmpty())
    }

    @Test fun `opening a link starts a browsable view of the exact address and reports a phone without a browser`() {
        val app = context as Application
        assertTrue(openExternalLink(context, buy))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(buy, started.dataString)
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
        shadowOf(app).checkActivities(true)
        assertFalse(openExternalLink(context, buy))
    }

    @Test fun `library search reads link labels but not their destinations`() {
        val podcast = show.copy(subscribed = true)
        val ep = episode("Mergulhe mais fundo [(link para compra)](<https://link.amazon/B03dsWf9A>)")
        assertEquals(listOf("e1"), libraryMatches(listOf("compra"), listOf(podcast), listOf(ep), emptyList(), emptyList()).episodes.map { it.id })
        assertTrue(libraryMatches(listOf("amazon"), listOf(podcast), listOf(ep), emptyList(), emptyList()).episodes.isEmpty())
    }

    @Test fun `the first refresh after upgrading downloads the feed again so stored descriptions gain their links`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val requests = mutableListOf<String?>()
        val rss = """<rss><channel><title>Show</title><description><![CDATA[About <a href="https://example.org/show">the show</a>]]></description><item><guid>g1</guid><title>Synthetic episode</title><enclosure url="https://example.test/e1.mp3" type="audio/mpeg"/><description><![CDATA[<p>Buy <a href="https://example.org/buy?id=1&amp;ref=a%20b">here</a></p>]]></description></item></channel></rss>"""
        val done = mutableSetOf<String>()
        try { MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request.getHeader("If-None-Match")
                    return if (request.getHeader("If-None-Match") != null) MockResponse().setResponseCode(304) else MockResponse().setHeader("ETag", "\"v2\"").setBody(rss)
                }
            }
            val url = server.url("/feed").toString()
            // Stored by an earlier version: link labels only, with validators that would make the server answer 304.
            db.library().putPodcast(PodcastEntity("p1", url, "Show", description = "About the show", etag = "\"v1\"", lastModified = "Wed, 25 Nov 2026 03:00:00 GMT"))
            db.library().putEpisodes(listOf(episode("Buy here").copy(guid = "g1", positionMs = 600_000, lastPlayedAt = 5, localFile = "/kept.mp3", downloadState = "done")))
            val repo = PodcastRepository(db, OkHttpClient(), upgradeOnce = { key, step -> if (done.add(key)) step() })
            repo.refresh("p1")
            val refreshed = db.library().episodes().single()
            assertEquals("Buy [here](<$buy>)", refreshed.description)
            assertEquals("About [the show](<https://example.org/show>)", db.library().podcast("p1")!!.description)
            assertEquals(listOf(600_000L, 5L), listOf(refreshed.positionMs, refreshed.lastPlayedAt))
            assertEquals("/kept.mp3" to "done", refreshed.localFile to refreshed.downloadState)
            // Later refreshes use the new validators again; a 304 keeps the linked text.
            repo.refresh("p1")
            assertEquals(listOf(null, "\"v2\""), requests)
            assertEquals("Buy [here](<$buy>)", db.library().episodes().single().description)
        } } finally { db.close() }
    }
}
