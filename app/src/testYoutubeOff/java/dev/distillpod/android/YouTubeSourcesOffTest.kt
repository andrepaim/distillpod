package dev.distillpod.android

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.data.*
import dev.distillpod.android.video.UnavailableYouTubeData
import dev.distillpod.android.video.VideocastMediaSourceFactory
import dev.distillpod.android.video.YOUTUBE_LINK_UNSUPPORTED
import dev.distillpod.android.video.YOUTUBE_UNAVAILABLE
import dev.distillpod.android.video.YouTubeUnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

/**
 * The Play preset builds without YouTube sources (#319). These tests run only in that build
 * (src/testYoutubeOff): nothing offers, fetches, plays or changes a YouTube source, and a YouTube
 * show restored from a backup stays in the library exactly as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class YouTubeSourcesOffTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    /** The canonical address a YouTube build stores (YouTubeUrl.source). */
    private val channel = "https://www.youtube.com/@distillpod/videos"
    private val watch = "https://www.youtube.com/watch?v=heoWstJTwu4"
    private val show = PodcastEntity(dev.distillpod.core.episodeId("podcast", channel), channel, "A video show", "A creator",
        "About the show", "https://i.ytimg.com/vi/heoWstJTwu4/hqdefault.jpg", refreshedAt = 1_700_000_000_000, originalFeedUrl = channel,
        websiteUrl = channel)
    private val episode = EpisodeEntity(dev.distillpod.core.episodeId(show.id, "heoWstJTwu4"), show.id, "heoWstJTwu4", "A video",
        watch, "Notes", publishedAt = 1_700_000_000_000, durationMs = 600_000, mimeType = "video/youtube", positionMs = 120_000,
        lastPlayedAt = 1_700_000_100_000, mediaFingerprint = dev.distillpod.core.episodeId("enclosure", watch), webpageUrl = watch, sourceOrder = 0)

    private fun database() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    private fun graph(block: suspend (AppGraph) -> Unit) = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val graph = AppGraph(context, CoroutineScope(SupervisorJob().apply { cancel() } + Dispatchers.Main.immediate))
        // AppJobs resolves context.appGraph internally.
        org.robolectric.util.ReflectionHelpers.setField(context, "graph\$delegate", lazyOf(graph))
        try { block(graph) } finally { graph.db.close(); WorkManagerTestInitHelper.closeWorkDatabase() }
    }

    private fun tables(backup: ByteArray) = JSONObject(JSONObject(backup.toString(Charsets.UTF_8)).getString("payload"))
    private fun JSONArray.rows() = (0 until length()).map { getJSONObject(it).toString() }.toSet()

    @Test fun `the build contains no YouTube extraction`() = graph { graph ->
        assertNull(graph.youtube)
        assertEquals(setOf("apple"), graph.podcasts.searchProviderNames)
        for (name in listOf("dev.distillpod.android.video.YouTubeSource", "dev.distillpod.android.video.AndroidYouTubeExtractor",
            "dev.distillpod.android.video.ExtractionCall", "dev.distillpod.android.video.RefreshingYouTubeDataSource",
            "com.yausername.youtubedl_android.YoutubeDL")) {
            assertThrows(name, ClassNotFoundException::class.java) { Class.forName(name) }
        }
        val raw = context.resources.getIdentifier("ytdlp", "raw", context.packageName)
        assertEquals("the extractor binary is not packaged", 0, raw)
    }

    @Test fun `search asks only the podcast directory`() = runBlocking {
        val db = database()
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"results":[{"collectionName":"Audio show","feedUrl":"https://example.test/feed"}]}"""))
                val repository = PodcastRepository(db, OkHttpClient(), server.url("/search"))
                assertEquals(listOf("https://example.test/feed"), repository.search("video podcasts").map { it.feedUrl })
                assertEquals(1, server.requestCount)
                assertEquals("/search", server.takeRequest().requestUrl!!.encodedPath)
            }
        } finally { db.close() }
    }

    @Test fun `a pasted or imported YouTube link is refused without a request or a library change`() = runBlocking {
        val db = database()
        try {
            MockWebServer().use { server ->
                val repository = PodcastRepository(db, OkHttpClient(), server.url("/search"))
                for (link in listOf(channel, watch, "https://www.youtube.com/playlist?list=PLaE_mZALZ0V3lAEAvIfZC-aKf2ATIY3uy")) {
                    for (action in listOf<suspend () -> Unit>({ repository.subscribe(link) }, { repository.browse(link) },
                        { repository.follow(PodcastCandidate("Video", "", link, null)) })) {
                        val error = runCatching { action() }.exceptionOrNull()
                        assertTrue("$link: $error", error is YouTubeUnavailableException)
                        assertEquals(YOUTUBE_LINK_UNSUPPORTED, error!!.message)
                    }
                }
                val opml = """<opml version="2.0"><body><outline type="rss" text="Video" xmlUrl="$channel"/>
                    <outline type="rss" text="Audio" xmlUrl="https://example.test/feed"/></body></opml>"""
                assertEquals(1, repository.importOpml(opml))
                assertEquals(listOf("https://example.test/feed"), db.library().podcasts().map { it.feedUrl })
                assertEquals(0, server.requestCount)
            }
        } finally { db.close() }
    }

    @Test fun `a restored YouTube show stays in the library inert and round-trips through backup unchanged`() = graph { graph ->
        val source = database()
        val original = try {
            source.library().putPodcast(show)
            source.library().putEpisode(episode)
            LibraryBackup(source).export()
        } finally { source.close() }
        graph.backup.merge(graph.backup.preview(original.inputStream()))
        val dao = graph.db.library()
        assertEquals(show, dao.podcast(show.id))
        val restored = dao.episode(episode.id)!!

        // Refresh: a direct refresh explains itself, a background refresh passes over the show.
        assertEquals(YOUTUBE_UNAVAILABLE, runCatching { graph.podcasts.refresh(show.id) }.exceptionOrNull()?.message)
        graph.podcasts.refreshAll()
        graph.podcasts.loadEpisodeDetails(episode.id)
        assertNull(graph.podcasts.episodeDetails.value[episode.id])
        // Download, transcription and playback are refused before anything changes.
        assertTrue(runCatching { graph.jobs.download(episode.id) }.exceptionOrNull() is YouTubeUnavailableException)
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork("download:${episode.id}").get(5, TimeUnit.SECONDS).isEmpty())
        assertTrue(runCatching { graph.media.ensureDownloaded(episode.id) }.exceptionOrNull() is YouTubeUnavailableException)
        assertTrue(runCatching { graph.transcription.prepare(episode.id, requested = true) }.exceptionOrNull() is YouTubeUnavailableException)
        graph.transcription.prepareAutomatically(episode.id)
        assertNull(dao.transcript(episode.id))
        assertTrue(runCatching { graph.playback.play(episode.id) }.exceptionOrNull() is YouTubeUnavailableException)
        assertTrue(runCatching { graph.playback.enqueue(episode.id) }.exceptionOrNull() is YouTubeUnavailableException)
        // Its own page can still follow it, without a request.
        assertEquals(show.id, graph.podcasts.subscribe(channel))

        assertEquals(show, dao.podcast(show.id))
        assertEquals(restored, dao.episode(episode.id))
        val before = tables(original)
        val after = tables(graph.backup.export())
        for (table in listOf("podcasts", "episodes")) assertEquals(table, before.getJSONArray(table).rows(), after.getJSONArray(table).rows())
    }

    @Test fun `a YouTube item reaching the player fails without opening anything`() {
        val factory = VideocastMediaSourceFactory(context, null, AudioFileOwnership())
        val item = MediaItem.Builder().setMediaId("episode").setUri(watch).build()
        assertEquals(item, factory.createMediaSource(item).mediaItem)
        val error = runCatching { UnavailableYouTubeData.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse(watch))) }.exceptionOrNull()
        assertTrue(error is FileNotFoundException)
        assertEquals(YOUTUBE_UNAVAILABLE, error!!.message)
    }
}
