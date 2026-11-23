package dev.distillpod.android

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/** #141: playback observers re-run on actual source changes, not on every progress write. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NarrowEpisodeObserversTest {
    @Test fun `progress writes do not re-emit playback source, ad source or browse observers`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(), AppDatabase::class.java).build()
        val dao = db.library()
        try {
            dao.putPodcast(PodcastEntity("p", "https://example.test/feed", "Show"))
            dao.putEpisode(EpisodeEntity("a", "p", "a", "A", "https://example.test/a", durationMs = 600_000, lastPlayedAt = 1,
                localFile = "/media/a.mp3", downloadState = "downloaded"))
            dao.putAnalysis(AnalysisEntity("a", "r1", state = "succeeded"))
            val raw = Collections.synchronizedList(mutableListOf<Any>())
            val source = Collections.synchronizedList(mutableListOf<Any?>())
            val ads = Collections.synchronizedList(mutableListOf<Any>())
            val browse = Collections.synchronizedList(mutableListOf<Any>())
            val jobs = listOf(
                launch(Dispatchers.IO) { dao.observeEpisodes().collect { raw.add(it) } },
                launch(Dispatchers.IO) { dao.observeLocalSource("a").collect { source.add(it) } },
                launch(Dispatchers.IO) { dao.observeAdSourceRows().collect { ads.add(it) } },
                launch(Dispatchers.IO) { dao.observeBrowseSummary().collect { browse.add(it) } },
            )
            suspend fun settle(rawCount: Int) {
                withTimeout(10_000) { while (raw.size < rawCount || source.isEmpty() || ads.isEmpty() || browse.isEmpty()) delay(20) }
                delay(300)
            }
            settle(1)
            for (second in 1..5) dao.progress("a", second * 1_000L, 600_000, false, 1 + second * 1_000L)
            settle(2)
            assertEquals(listOf<String?>("/media/a.mp3"), source.toList())
            assertEquals(1, (ads.single() as List<*>).size)
            assertEquals(1, ads.size)
            assertEquals(1, browse.size)
            dao.downloadComplete("a", "/media/a2.mp3", "sha", 10)
            settle(3)
            withTimeout(10_000) { while (source.size < 2 || ads.size < 2 || browse.size < 2) delay(20) }
            assertEquals(listOf("/media/a.mp3", "/media/a2.mp3"), source.toList())
            jobs.forEach { it.cancel() }
        } finally { db.close() }
    }

    /** #277: a feed refresh writes each show's bookkeeping; the car's browse lists must not reload for it. */
    @Test fun `feed refresh bookkeeping does not re-emit the browse podcast observer`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(), AppDatabase::class.java).build()
        val dao = db.library()
        try {
            val show = PodcastEntity("p", "https://example.test/feed", "Show", imageUrl = "https://example.test/p.png")
            dao.putPodcast(show)
            val raw = Collections.synchronizedList(mutableListOf<Any>())
            val browse = Collections.synchronizedList(mutableListOf<List<BrowsePodcast>>())
            val jobs = listOf(
                launch(Dispatchers.IO) { dao.observePodcasts().collect { raw.add(it) } },
                launch(Dispatchers.IO) { dao.observeBrowsePodcasts().collect { browse.add(it) } },
            )
            suspend fun settle(rawCount: Int) {
                withTimeout(10_000) { while (raw.size < rawCount || browse.isEmpty()) delay(20) }
                delay(300)
            }
            settle(1)
            dao.markPodcastRefreshed("p", 1_000)
            settle(2)
            dao.setPodcastError("p", "offline")
            settle(3)
            dao.putPodcast(dao.podcast("p")!!.copy(etag = "v2", lastModified = "yesterday", refreshedAt = 2_000, error = null))
            settle(4)
            assertEquals(1, browse.size)
            dao.putPodcast(dao.podcast("p")!!.copy(imageUrl = "https://example.test/p2.png"))
            settle(5)
            withTimeout(10_000) { while (browse.size < 2) delay(20) }
            assertEquals("https://example.test/p2.png", browse.last().single().imageUrl)
            jobs.forEach { it.cancel() }
        } finally { db.close() }
    }
}
