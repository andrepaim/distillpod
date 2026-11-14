package dev.distillpod.android

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import dev.distillpod.android.ai.*
import dev.distillpod.android.data.*
import dev.distillpod.android.features.AppViewModel
import dev.distillpod.android.features.ProductApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic-only acceptance. Physical devices require a separate validation application ID. */
@RunWith(AndroidJUnit4::class)
class ShowAutomationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = compose.activity.applicationContext.appGraph
    private fun open(route: String) = compose.runOnUiThread {
        ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java].open(route)
    }
    private suspend fun await(check: suspend () -> Boolean) = withTimeout(60000) { while (!check()) delay(100) }
    private fun guard() {
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic") ||
            compose.activity.packageName == "dev.distillpod.android.validation") { "Physical validation requires an isolated application ID" }
    }

    @Test fun futureEpisodeProcessingAndPlayerAdControl() = runBlocking {
        guard(); graph.ready.await()
        val dao = graph.db.library()
        val id = "automatic-device-${UUID.randomUUID()}"
        val fresh = AtomicBoolean(false)
        val audio = silentWav()
        val prefs = graph.settings.flow.first()
        val server = MockWebServer()
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/feed" -> MockResponse().setBody("""<rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel><title>Automatic validation show</title>${
                    (if (fresh.get()) listOf("old", "new") else listOf("old")).joinToString("") { episode ->
                        "<item><guid>$episode</guid><title>Automatic validation $episode</title><enclosure url=\"${server.url("/audio")}\" type=\"audio/wav\" length=\"${audio.size}\"/><podcast:transcript url=\"${server.url("/transcript")}\" type=\"text/vtt\"/></item>"
                    }}</channel></rss>""")
                "/audio" -> MockResponse().setBody(Buffer().write(audio)).setHeader("Content-Type", "audio/wav")
                "/transcript" -> MockResponse().setBody("""WEBVTT

00:00:00.000 --> 00:00:02.000
A community introduction.

00:00:02.000 --> 00:00:05.000
An explicit fixture sponsor message.

00:00:05.000 --> 00:00:12.000
People contribute to their community.
""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        var episodeId: String? = null
        try {
            graph.settings.flag("wifi", false)
            graph.settings.flag("charging", false)
            graph.settings.flag("transcribeOnPlayback", false)
            dao.putPodcast(PodcastEntity(id, server.url("/feed").toString(), "Automatic validation show"))
            open("podcast:$id")
            compose.onNodeWithText("Automatically prepare new episodes").assertDoesNotExist()
            compose.onNodeWithContentDescription("Show preparation settings").performClick()
            compose.onNodeWithText("Transcript text is sent to ChatGPT", substring = true).assertIsDisplayed()
            compose.onNode(isToggleable() and hasText("Automatically prepare new episodes")).performScrollTo().performClick()
            compose.onNodeWithText("Enable preparation").performClick()
            await { graph.db.automation().show(id)?.initialized == true }
            assertTrue(graph.db.automation().jobs().none { it.podcastId == id })
            fresh.set(true)
            graph.podcasts.refresh(id)
            episodeId = dao.episodesForPodcast(id).single { it.guid == "new" }.id
            val episode = episodeId!!
            assertEquals(1, graph.db.automation().jobs().count { it.podcastId == id })
            graph.podcasts.refresh(id)
            assertEquals(1, graph.db.automation().jobs().count { it.podcastId == id })
            await { dao.episode(episode)?.downloadState == "downloaded" && dao.transcript(episode)?.state == "ready" }
            assertEquals("publisher", dao.transcript(episode)!!.source)
            assertNull(dao.analysis(episode))
            graph.playback.play(episode, 0); graph.playback.pause()
            open("player")
            compose.onNodeWithText("Skip marked ads").assertDoesNotExist()
            val fake = object : AiGateway {
                override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit) = AiText(
                    """{"summary":"A community introduction with an explicit fixture sponsor.","chapters":[{"startMs":0,"title":"Community"}],"ads":[{"startMs":2000,"endMs":5000,"reason":"Explicit fixture sponsor message"}]}""", "synthetic-device")
            }
            val understanding = EpisodeUnderstanding(graph.db, fake, {}, {})
            val processor = AutomationProcessor(graph.db, { true }, graph.jobs::download,
                { graph.transcription.prepare(it, allowOnline = false) }, { understanding.analyze(it); understanding.runAnalysis(it) })
            processor.advance(); processor.advance()
            assertEquals("ready", graph.db.automation().jobs().single { it.episodeId == episode }.stage)
            assertEquals("succeeded", dao.analysis(episode)!!.state)
            compose.waitUntil(10000) { compose.onAllNodesWithText("Skip marked ads").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(isToggleable() and hasAnySibling(hasText("Skip marked ads"))).performScrollTo().performClick()
            await { graph.db.automation().ad(episode)?.enabled == true }
            graph.playbackEvents.lastSkipped.value = null
            graph.playback.play(episode, 0)
            await { graph.playbackEvents.lastSkipped.value?.episodeId == episode }
            assertEquals(5000L, graph.playbackEvents.lastSkipped.value!!.endMs)
            graph.playback.pause()
            compose.activityRule.scenario.recreate()
            open("player")
            compose.onNode(isToggleable() and hasAnySibling(hasText("Skip marked ads"))).performScrollTo().assertIsOn().performClick()
            await { graph.db.automation().ad(episode)?.enabled == false }
            graph.playbackEvents.lastSkipped.value = null
            graph.playback.play(episode, 0); delay(3200); graph.playback.pause()
            assertNull(graph.playbackEvents.lastSkipped.value)
            graph.automation.setEnabled(id, false)
            assertNull(graph.db.automation().show(id))
            assertTrue(graph.db.automation().jobs().none { it.podcastId == id })
        } finally {
            graph.automation.setEnabled(id, false)
            episodeId?.let { graph.playback.pause(); graph.playback.remove(it); graph.transcription.pause(it) }
            dao.episodesForPodcast(id).forEach { ep -> ep.localFile?.let { java.io.File(it).delete() } }
            dao.deletePodcast(id)
            graph.settings.flag("wifi", prefs.wifiOnly)
            graph.settings.flag("charging", prefs.chargingOnly)
            graph.settings.flag("transcribeOnPlayback", prefs.transcribeOnPlayback)
            server.shutdown()
        }
    }

    @Test fun compactHeaderSurfacesFailuresOutsideTheEpisodeFilterAndRetriesTheSelectedEpisode() = runBlocking {
        guard(); graph.ready.await()
        val dao = graph.db.library()
        val automation = graph.db.automation()
        val id = "compact-preparation-${UUID.randomUUID()}"
        val first = "$id-first"
        val second = "$id-second"
        val ready = "$id-ready"
        val longTitle = "A deliberately long synthetic title for an episode that still needs preparation"
        val showDescription = "A synthetic show description that should be visible only when expanded."
        try {
            dao.putPodcast(PodcastEntity(id, "https://example.test/$id", "Compact preparation fixture", description = showDescription))
            dao.putEpisode(EpisodeEntity(first, id, first, longTitle, "https://example.test/first", publishedAt = 3))
            dao.putEpisode(EpisodeEntity(second, id, second, "Second retry fixture", "https://example.test/second", publishedAt = 2))
            dao.putEpisode(EpisodeEntity(ready, id, ready, "Ready fixture", "https://example.test/ready", publishedAt = 1))
            // Freeze the processor without changing its behavior or making real network calls.
            automation.putShow(ShowAutomationEntity(id, 1, initialized = false))
            automation.insertJob(EpisodeAutomationEntity(first, id, "blocked"))
            automation.insertJob(EpisodeAutomationEntity(second, id, "blocked"))
            automation.insertJob(EpisodeAutomationEntity(ready, id, "ready"))
            open("podcast:$id")
            compose.waitUntil(10000) { compose.onAllNodesWithText("2 episodes need attention").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("2 episodes need attention").assertIsDisplayed()
            compose.onNodeWithText("Retry preparation").assertDoesNotExist()
            compose.onNodeWithText("Automatically prepare new episodes").assertDoesNotExist()
            compose.onNodeWithText(showDescription).assertDoesNotExist()
            compose.onNodeWithText("About this show").performClick()
            compose.onNodeWithText(showDescription).assertIsDisplayed()
            compose.onNodeWithText("Hide about this show").performClick()
            compose.onNodeWithContentDescription("More actions for $longTitle").performScrollTo().performClick()
            compose.onNodeWithText("Retry preparation").performClick()
            await { automation.jobs().any { it.episodeId == first && it.stage == "retry" } }
            compose.waitUntil(10000) { compose.onAllNodesWithText("1 episode needs attention").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Downloads").performScrollTo().performClick()
            compose.onNodeWithText("Second retry fixture").assertDoesNotExist()
            compose.runOnUiThread {
                val vm = ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java]
                compose.activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(graph.context.resources.displayMetrics.density, 2f)) {
                        Box(Modifier.width(320.dp).fillMaxHeight()) { ProductApp(vm) }
                    }
                }
            }
            compose.onNodeWithContentDescription("Show preparation settings").assertHasClickAction().assertIsDisplayed()
            compose.onNodeWithText("1 episode needs attention").performScrollTo().assertHasClickAction().performClick()
            compose.onNodeWithText("Needs attention only").assertIsSelected()
            compose.onNodeWithText("Ready fixture").assertDoesNotExist()
            compose.onNodeWithContentDescription("Retry preparation for Second retry fixture")
                .performScrollTo().assertHasClickAction().assertIsDisplayed().performClick()
            await { automation.jobs().any { it.episodeId == second && it.stage == "retry" } }
            compose.waitUntil(10000) { compose.onAllNodesWithText("No episodes need attention").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("ready", automation.jobs().single { it.episodeId == ready }.stage)
        } finally {
            graph.automation.setEnabled(id, false)
            dao.deletePodcast(id)
        }
    }

    @Test fun intervalPersistsAndUpdatesUniquePeriodicWork() = runBlocking {
        guard(); graph.ready.await()
        val prefs = graph.settings.flow.first()
        try {
            graph.settings.flag("refresh", true)
            open("settings:library")
            compose.onNodeWithText("12h").performScrollTo().performClick()
            await { graph.settings.flow.first().refreshHours == 12 }
            compose.activityRule.scenario.recreate()
            open("settings:library")
            compose.onNodeWithText("Check for new episodes every 12 hours").performScrollTo().assertIsDisplayed()
            val work = WorkManager.getInstance(graph.context).getWorkInfosForUniqueWork("refresh-periodic").get()
            assertEquals(1, work.count { !it.state.isFinished })
            assertEquals(12 * 60 * 60 * 1000L, work.single { !it.state.isFinished }.periodicityInfo!!.repeatIntervalMillis)
        } finally {
            graph.settings.refreshHours(prefs.refreshHours)
            graph.settings.flag("refresh", prefs.refresh)
            graph.jobs.configureRefresh()
        }
    }

    @Test fun durableIntentSurvivesProcessRestart() = runBlocking {
        val phase = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("automationPhase") ?: "roundtrip"
        guard()
        if (phase != "roundtrip") check(compose.activity.packageName == "dev.distillpod.android.validation")
        graph.ready.await()
        val id = "automatic-restart-fixture"
        val dao = graph.db.library()
        val file = java.io.File(graph.context.filesDir, "$id.wav")
        if (phase in listOf("stage", "roundtrip")) {
            file.writeBytes(silentWav())
            dao.putPodcast(PodcastEntity(id, "https://example.test/restart", "Restart fixture"))
            dao.putEpisode(EpisodeEntity(id, id, id, "Restart episode", "https://example.test/audio", localFile = file.path,
                mediaFingerprint = id, downloadState = "downloaded", downloadedBytes = file.length()))
            dao.putTranscript(TranscriptEntity(id, id, id, "publisher", "en", "publisher", "ready", 12000, 12000))
            graph.db.automation().putShow(ShowAutomationEntity(id, 1, true))
            graph.db.automation().insertJob(EpisodeAutomationEntity(id, id))
            graph.settings.refreshHours(3); graph.jobs.configureRefresh()
            await { graph.db.automation().jobs().any { it.episodeId == id && it.stage == "waiting_auth" } }
        }
        if (phase != "stage") {
            if (phase == "roundtrip") compose.activityRule.scenario.recreate()
            try {
                assertTrue(file.isFile)
                assertEquals(3, graph.settings.flow.first().refreshHours)
                assertEquals("waiting_auth", graph.db.automation().jobs().single { it.episodeId == id }.stage)
                assertEquals("ready", dao.transcript(id)!!.state)
                assertNull(dao.analysis(id))
                assertEquals(1, WorkManager.getInstance(graph.context).getWorkInfosForUniqueWork("refresh-periodic").get().count { !it.state.isFinished })
            } finally {
                graph.automation.setEnabled(id, false); dao.deletePodcast(id); file.delete()
                graph.settings.refreshHours(6); graph.jobs.configureRefresh()
            }
        }
    }

    private fun silentWav(): ByteArray {
        val size = 16000 * 2 * 12
        return ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
        }.array()
    }
}
