package dev.distillpod.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import dev.distillpod.android.ai.*
import dev.distillpod.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductFlowDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun subscriptionDownloadTranscriptCaptureAndReplaySurviveRecreation() = runBlocking {
        check(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.FINGERPRINT.contains("generic")) {
            "Use a disposable test emulator"
        }
        val graph = compose.activity.applicationContext.appGraph
        graph.ready.await()
        val originalWifiOnly = graph.settings.flow.first().wifiOnly
        val dao = graph.db.library()
        val audio = InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { it.readBytes() }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/feed" -> MockResponse().setHeader("Content-Type", "application/rss+xml").setBody("""
                    <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel>
                    <title>Device validation show</title><item><guid>device-fixture</guid><title>Device validation episode</title>
                    <enclosure url="${server.url("/audio")}" type="audio/wav" length="${audio.size}"/>
                    <itunes:duration>11</itunes:duration><podcast:transcript url="${server.url("/text")}" type="text/vtt"/>
                    </item></channel></rss>
                """.trimIndent())
                "/audio" -> MockResponse().setHeader("Content-Type", "audio/wav").setHeader("ETag", "\"fixture-v1\"").setBody(Buffer().write(audio))
                "/text" -> MockResponse().setBody("WEBVTT\n\n00:00:00.000 --> 00:00:11.000\nAsk not what your country can do for you, ask what you can do for your country.\n")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        var podcast: String? = null
        var episode: String? = null
        var bodyFailure: Throwable? = null
        try {
            // This loopback flow tests the product pipeline, independent of whether
            // the emulator's real default connection is metered. Policy has its own suite.
            graph.settings.flag("wifi", false)
            graph.jobs.configureNetworkPolicy()
            podcast = graph.podcasts.subscribe(server.url("/feed").toString())
            episode = dao.episodesForPodcast(podcast).single().id
            compose.waitUntil(10000) { compose.onAllNodesWithText("Device validation episode").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Play Device validation episode").performClick()
            await("playback starts") { graph.playback.state.value.episodeId == episode && graph.playback.state.value.playing }
            graph.playback.pause()
            graph.jobs.download(episode)
            await("episode download completes") { dao.episode(episode)?.downloadState == "downloaded" }
            assertTrue(java.io.File(dao.episode(episode)!!.localFile!!).isFile)
            graph.transcription.prepare(episode)
            await("publisher transcript is ready") { dao.transcript(episode)?.state == "ready" }
            val gateway = object : AiGateway {
                override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit) =
                    AiText(if(instructions==MomentGeneration.reviewPrompt) """{"passageComplete":true,"takeawaySupported":true,"relevant":true,"quoteFaithful":true,"needsContext":false,"issues":[]}""" else
                        """{"outcome":"ready","passageStart":0,"passageEnd":0,"takeaway":"The speaker urges people to contribute to their community.","quoteStart":null,"quoteEnd":null}""", "device-test")
            }
            val requests = mutableListOf<String>()
            val captures = CaptureRepository(graph.db, gateway, { true }, { error("Coverage should already exist") }, requests::add)
            val capture = captures.capture(episode, 10000, true)
            assertEquals(listOf(capture), requests)
            captures.run(capture)
            assertEquals("succeeded", dao.distillation(capture)!!.state)
            val restored = Room.inMemoryDatabaseBuilder(graph.context, AppDatabase::class.java).build()
            try {
                val backup = LibraryBackup(restored)
                backup.merge(backup.preview(graph.backup.export().inputStream()))
                assertEquals(dao.distillation(capture)!!.quote, restored.library().distillation(capture)!!.quote)
                assertEquals(dao.transcript(episode)!!.revision, restored.library().transcript(episode)!!.revision)
                assertNull(restored.library().episode(episode)!!.localFile)
            } finally { restored.close() }
            compose.onNodeWithText("Saved", useUnmergedTree = true).performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("The speaker urges people to contribute to their community.", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("The speaker urges people to contribute to their community.", substring = true).assertIsDisplayed().performClick()
            compose.onNodeWithText("Replay source · ${dev.distillpod.android.features.timestamp(dao.distillation(capture)!!.sourceStartMs)}").performScrollTo().performClick()
            await("saved source replay starts") { graph.playback.state.value.episodeId == episode && graph.playback.state.value.playing }
            assertEquals(dao.episode(episode)!!.localFile, graph.playback.captureSource().third)
            assertEquals(10000L, dao.distillation(capture)!!.endMs)
        } catch (error: Throwable) {
            bodyFailure = error
            throw error
        } finally { withContext(NonCancellable) {
            var cleanupFailure: Throwable? = null
            fun record(error: Throwable) {
                val first = cleanupFailure
                if (first == null) cleanupFailure = error else first.addSuppressed(error)
            }
            try {
                episode?.let { fixtureId ->
                    graph.playback.pause(); graph.playback.remove(fixtureId)
                    if (dao.transcript(fixtureId)?.state in listOf("queued", "processing")) graph.transcription.pause(fixtureId)
                    withContext(Dispatchers.IO) {
                        androidx.work.WorkManager.getInstance(graph.context).cancelUniqueWork("download:$fixtureId")
                            .result.get(10, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    await("playback and persisted queue release the fixture download") {
                        fixtureId !in graph.playback.state.value.queue && dao.queue().none { queued -> queued.episodeId == fixtureId } &&
                            !graph.activeFiles.busy(dao.episode(fixtureId)?.localFile ?: "")
                    }
                    graph.media.deleteDownload(fixtureId)
                }
                podcast?.let { dao.deletePodcast(it) }
            } catch (error: Throwable) { record(error) }
            try {
                graph.settings.flag("wifi", originalWifiOnly)
                graph.jobs.configureNetworkPolicy()
            } catch (error: Throwable) { record(error) }
            try { server.shutdown() } catch (error: Throwable) { record(error) }
            cleanupFailure?.let { cleanup ->
                val original = bodyFailure
                if (original != null) original.addSuppressed(cleanup) else throw cleanup
            }
        } }
    }

    private suspend fun await(stage: String, predicate: suspend () -> Boolean) {
        try { withTimeout(45000) { while (!predicate()) delay(100) } }
        catch (timeout: TimeoutCancellationException) { throw AssertionError("Timed out waiting for: $stage", timeout) }
    }
}
