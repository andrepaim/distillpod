package dev.distillpod.android

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import dev.distillpod.android.features.libraryMatches
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * Opt-in #179 measurement on a separate synthetic database (never the app's library): a 3,000-episode
 * library with a large transcript corpus. Run with `-e librarySearchBenchmark true`; optional
 * `librarySearchTranscripts` and `librarySearchSegments` size the corpus.
 */
@RunWith(AndroidJUnit4::class)
class LibrarySearchBenchmarkDeviceTest {
    @Test fun searchesALargeSyntheticLibrary() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("librarySearchBenchmark") == "true")
        val transcripts = args.getString("librarySearchTranscripts")?.toInt() ?: 600
        val perTranscript = args.getString("librarySearchSegments")?.toInt() ?: 700
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "library-search-benchmark.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val dao = db.library()
            val words = "the and climate policy listening history science market energy story people time music health ocean city".split(' ')
            val podcasts = (0 until 60).map { PodcastEntity("p$it", "https://example.test/$it", "Synthetic show $it ${words[it % words.size]}", "Host $it") }
            val episodes = (0 until 3000).map { n ->
                EpisodeEntity("e$n", "p${n % 60}", "g$n", "Episode $n about ${words[n % words.size]} and ${words[(n * 7) % words.size]}", "https://example.test/a$n",
                    description = (0 until 120).joinToString(" ") { words[(n + it * 3) % words.size] }, publishedAt = n.toLong())
            }
            val seeding = measureTimeMillis {
                podcasts.forEach { dao.putPodcast(it) }
                dao.putEpisodes(episodes)
                for (t in 0 until transcripts) {
                    val id = "e$t"
                    dao.putTranscript(TranscriptEntity(id, "r", "", "tiny", "en", state = "ready"))
                    dao.putSegments((0 until perTranscript).map { s ->
                        val text = (0 until 16).joinToString(" ") { words[(t * 31 + s * 7 + it * 5) % words.size] } +
                            if (t == transcripts - 1 && s == perTranscript / 2) " zephyrine" else ""
                        SegmentEntity(id, "r", s * 6_000L, s * 6_000L + 5_500, text)
                    })
                }
            }
            val search = TranscriptSearch(db)
            search.find(listOf("warmup"))
            val rare = (1..3).map { measureTimeMillis { assertEquals(1, search.find(listOf("zephyrine")).single().matches) } }
            val absent = measureTimeMillis { assertTrue(search.find(listOf("nonexistentword")).isEmpty()) }
            var commonHits = 0
            val common = measureTimeMillis { commonHits = search.find(listOf("climate")).size }
            val twoTerms = measureTimeMillis { search.find(listOf("ocean", "music")) }
            val captures = (0 until 200).map { DistillationEntity("m$it", "e$it", 0, 30_000, excerpt = "Synthetic ${words[it % words.size]} excerpt", insight = "Insight $it") }
            val memory = (1..3).map { measureTimeMillis { libraryMatches(listOf("climate"), podcasts, episodes, captures, emptyList()) } }
            // A stale query must stop soon after a newer keystroke cancels it.
            val job = launch(Dispatchers.IO) { search.find(listOf("nonexistentword")) }
            delay(30)
            val cancelStart = System.nanoTime(); job.cancelAndJoin()
            val cancelMs = (System.nanoTime() - cancelStart) / 1_000_000
            val segments = transcripts.toLong() * perTranscript
            val report = "segments=$segments seedMs=$seeding rareMs=$rare absentMs=$absent commonMs=$common commonEpisodes=$commonHits twoTermsMs=$twoTerms inMemory3000Ms=$memory cancelMs=$cancelMs"
            Log.i("LibrarySearchBenchmark", report)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("librarySearch", report) })
        } finally {
            db.close(); context.deleteDatabase(name)
        }
    }
}
