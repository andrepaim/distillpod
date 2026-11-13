package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import dev.distillpod.android.features.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #179: one Search field finds the listener's own shows, episodes, transcript passages and saved moments on the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySearchTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = db.library()
    @After fun close() = db.close()

    private val followed = PodcastEntity("p1", "https://example.test/one", "Signal and Story", "Studio North", imageUrl = "https://example.test/one.png")
    private val unfollowed = PodcastEntity("p2", "https://example.test/two", "Old Habits", "Someone", subscribed = false)
    private val channel = PodcastEntity("yt", "https://www.youtube.com/playlist?list=PL1234567890", "Video Lab", "Channel")
    private val podcasts = listOf(followed, unfollowed, channel)
    private val episodes = listOf(
        EpisodeEntity("e-title", "p1", "g1", "Climate policy explained", "https://example.test/1", publishedAt = 10),
        EpisodeEntity("e-newer", "p1", "g2", "Policy notes on climate", "https://example.test/2", publishedAt = 30),
        EpisodeEntity("e-desc", "p1", "g3", "Weekly roundup", "https://example.test/3", description = "We discuss climate policy at length.", publishedAt = 50),
        EpisodeEntity("e-stranger", "p2", "g4", "Climate policy elsewhere", "https://example.test/4", publishedAt = 60),
        EpisodeEntity("e-played", "p2", "g5", "Climate policy I played", "https://example.test/5", publishedAt = 5, lastPlayedAt = 7),
        EpisodeEntity("e-video", "yt", "g6", "Climate policy on video", "https://www.youtube.com/watch?v=aaaaaaaaaaa", publishedAt = 1),
    )

    @Test fun `terms need two characters, skip feed addresses and are bounded`() {
        assertNull(librarySearchTerms(" a "))
        assertNull(librarySearchTerms("https://example.test/feed.xml"))
        assertEquals(listOf("climate", "policy"), librarySearchTerms("  Climate   POLICY climate "))
        assertEquals(6, librarySearchTerms("a b c d e f g h")!!.size)
        assertTrue(librarySearchTerms("x".repeat(500))!!.single().length <= 200)
    }

    @Test fun `episodes come from the library, need every term and rank title phrase above notes`() {
        val matches = libraryMatches(listOf("climate", "policy"), podcasts, episodes, emptyList(), emptyList())
        // Followed shows plus episodes that were downloaded or played; the unfollowed, untouched one is not "yours".
        assertEquals(listOf("e-title", "e-played", "e-video", "e-newer", "e-desc"), matches.episodes.map { it.id })
        assertEquals("Signal and Story", matches.episodes.first().showTitle)
        assertTrue(matches.episodes.single { it.id == "e-video" }.video)
        assertTrue(libraryMatches(listOf("climate", "absent"), podcasts, episodes, emptyList(), emptyList()).episodes.isEmpty())
    }

    @Test fun `shows are followed shows matching title or author`() {
        val matches = libraryMatches(listOf("studio"), podcasts, episodes, emptyList(), emptyList())
        assertEquals(listOf("p1"), matches.shows.map { it.id })
        assertTrue(libraryMatches(listOf("habits"), podcasts, episodes, emptyList(), emptyList()).shows.isEmpty())
    }

    @Test fun `moments match their text or source titles, insight matches first`() {
        val captures = listOf(
            DistillationEntity("m-title", "e-desc", 1_000, 2_000, excerpt = "Unrelated words.", createdAt = 3),
            DistillationEntity("m-insight", "e-title", 61_000, 70_000, excerpt = "Text", insight = "Roundup thinking matters", createdAt = 2))
        val matches = libraryMatches(listOf("roundup"), podcasts, episodes, captures, emptyList())
        assertEquals(listOf("m-insight", "m-title"), matches.moments.map { it.id })
        assertEquals(61_000, matches.moments.first().startMs)
        assertEquals("Climate policy explained", matches.moments.first().episodeTitle)
    }

    @Test fun `passages rank by match count then recency and carry a snippet around the match`() {
        val long = "Opening words. ".repeat(20) + "Here the carbon tax finally comes up." + " Closing words.".repeat(20)
        val hits = listOf(TranscriptHit("e-title", 1, 5_000, 9_000, long), TranscriptHit("e-desc", 4, 61_000, 64_000, "carbon"),
            TranscriptHit("e-newer", 1, 1_000, 2_000, "carbon"), TranscriptHit("missing", 9, 0, 1, "carbon"))
        val matches = libraryMatches(listOf("carbon"), podcasts, episodes, emptyList(), hits)
        assertEquals(listOf("e-desc", "e-newer", "e-title"), matches.passages.map { it.episodeId })
        val snippet = matches.passages.last().snippet
        assertTrue(snippet, snippet.contains("carbon tax") && snippet.startsWith("…") && snippet.endsWith("…") && snippet.length < 220)
    }

    @Test fun `transcript routes carry an optional passage position`() {
        assertEquals("transcript:abc@61000", transcriptRoute("abc", 61_000))
        assertEquals(TranscriptTarget("abc", 61_000), transcriptTarget("transcript:abc@61000"))
        assertEquals(TranscriptTarget("abc", null), transcriptTarget("transcript:abc"))
        assertEquals(TranscriptTarget("a@b", null), transcriptTarget("transcript:a@b"))
    }

    @Test fun `the focused passage is the one containing the position, else the last before it`() {
        val segments = listOf(SegmentEntity("e", "r", 0, 9_000, "a"), SegmentEntity("e", "r", 10_000, 19_000, "b"), SegmentEntity("e", "r", 30_000, 39_000, "c"))
        assertEquals(1, transcriptFocusIndex(segments, 10_000))
        assertEquals(1, transcriptFocusIndex(segments, 25_000))
        assertEquals(0, transcriptFocusIndex(segments, 0))
        assertEquals(-1, transcriptFocusIndex(emptyList(), 5))
    }

    @Test fun `visible rows are collapsed to a few and capped when expanded`() {
        val rows = (1..120).toList()
        assertEquals(3, visibleLibraryRows(rows, expanded = false).size)
        assertEquals(LIBRARY_SECTION_CAP, visibleLibraryRows(rows, expanded = true).size)
    }

    private suspend fun transcript(id: String, revision: String, vararg texts: String, state: String = "ready") {
        dao.putTranscript(TranscriptEntity(id, revision, "fp", "tiny", "en", state = state))
        dao.putSegments(texts.mapIndexed { index, text -> SegmentEntity(id, revision, index * 10_000L, index * 10_000L + 9_000, text) })
    }

    private suspend fun seed() {
        podcasts.forEach { dao.putPodcast(it) }
        dao.putEpisodes(episodes)
    }

    @Test fun `transcript search reads only the current revision of existing transcripts`() = runBlocking<Unit> {
        seed()
        transcript("e-title", "current", "Intro", "The carbon tax returns", "More about CARBON pricing")
        dao.putSegments(listOf(SegmentEntity("e-title", "old", 0, 1_000, "carbon from a superseded revision")))
        transcript("e-desc", "r1", "Nothing relevant")
        // A deleted (or #60-suppressed) transcript leaves no transcript row; stray segments must not resurface.
        dao.putSegments(listOf(SegmentEntity("e-newer", "gone", 0, 1_000, "carbon in deleted text")))
        val hits = TranscriptSearch(db).find(listOf("carbon"))
        assertEquals(listOf(TranscriptHit("e-title", 2, 10_000, 19_000, "The carbon tax returns")), hits)
    }

    @Test fun `every term must appear in one passage and wildcards match literally`() = runBlocking<Unit> {
        seed()
        transcript("e-title", "r", "carbon here", "tax there", "carbon and tax together", "100% sure", "1000 sure", "snake_case", "snakeXcase")
        val search = TranscriptSearch(db)
        assertEquals(listOf(20_000L), search.find(listOf("tax", "carbon")).map { it.startMs })
        assertEquals(1, search.find(listOf("0%")).single().matches)
        assertEquals(1, search.find(listOf("e_c")).single().matches)
    }

    @Test fun `accented words match regardless of the case of their first letter`() = runBlocking<Unit> {
        seed()
        transcript("e-title", "r", "Água limpa", "ÉTICA na ciência", "sem acento")
        val search = TranscriptSearch(db)
        assertEquals(1, search.find(listOf("água")).single().matches)
        assertEquals(1, search.find(listOf("ética")).single().matches)
        assertEquals(1, search.find(listOf("ciência")).single().matches)
    }

    @Test fun `ranges cover the table and merge counts per episode`() = runBlocking<Unit> {
        seed()
        transcript("e-title", "r", *Array(25) { if (it % 2 == 0) "carbon $it" else "other $it" })
        transcript("e-desc", "r", *Array(7) { "carbon $it" })
        var ranges = 0
        val hits = TranscriptSearch(db, rowsPerRange = 4) { ranges++ }.find(listOf("carbon"))
        assertTrue("expected several ranges, got $ranges", ranges >= 8)
        assertEquals(mapOf("e-title" to 13, "e-desc" to 7), hits.associate { it.episodeId to it.matches })
        assertEquals(0L, hits.single { it.episodeId == "e-title" }.startMs)
        assertEquals(hits.toSet(), TranscriptSearch(db).find(listOf("carbon")).toSet())
    }

    @Test fun `a cancelled search stops between ranges`() = runBlocking<Unit> {
        seed()
        transcript("e-title", "r", *Array(40) { "carbon $it" })
        var ranges = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            TranscriptSearch(db, rowsPerRange = 2) {
                ranges++
                if (ranges == 2) { started.complete(Unit); runBlocking { release.await() } }
            }.find(listOf("carbon"))
            fail("A cancelled search must not finish")
        }
        started.await()
        job.cancel(); release.complete(Unit); job.join()
        assertEquals(2, ranges)
    }

    @Test fun `typing is debounced, stale searches are cancelled and never published`() = runTest {
        val queries = MutableStateFlow("")
        val calls = mutableListOf<List<String>>()
        val cancelled = mutableListOf<List<String>>()
        val states = mutableListOf<LibrarySearchState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            librarySearchStates(queries, debounceMs = 250) { terms ->
                calls += terms
                try { delay(1_000) } catch (e: CancellationException) { cancelled += terms; throw e }
                LibraryMatches(terms)
            }.toList(states)
        }
        queries.value = "cl"; advanceTimeBy(100)
        queries.value = "cli"; advanceTimeBy(100)
        queries.value = "climate"; advanceTimeBy(300)
        assertEquals(listOf(listOf("climate")), calls)
        assertTrue(states.last().loading)
        queries.value = "climate tax"; advanceTimeBy(300)
        assertEquals(listOf(listOf("climate")), cancelled)
        advanceUntilIdle()
        val finished = states.filter { !it.loading && it.matches != null }
        assertEquals(listOf(listOf("climate", "tax")), finished.map { it.matches!!.terms })
        assertEquals(listOf("climate", "tax"), states.last().terms)
        queries.value = "c"; advanceUntilIdle()
        assertEquals(LibrarySearchState(), states.last())
        job.cancel()
    }

    @Test fun `results stay visible while the next search loads and failures are reported`() = runTest {
        val queries = MutableStateFlow("tax")
        val states = mutableListOf<LibrarySearchState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            librarySearchStates(queries, debounceMs = 50) { terms ->
                if (terms == listOf("broken")) error("disk") else LibraryMatches(terms)
            }.toList(states)
        }
        advanceUntilIdle()
        queries.value = "taxes"
        assertTrue(states.last().loading)
        assertEquals(listOf("tax"), states.last().matches?.terms)
        advanceUntilIdle()
        queries.value = "broken"; advanceUntilIdle()
        assertTrue(states.last().failed)
        assertNull(states.last().matches)
        job.cancel()
    }
}
