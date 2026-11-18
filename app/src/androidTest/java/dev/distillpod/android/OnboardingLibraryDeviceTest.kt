package dev.distillpod.android

import android.content.Context
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.distillpod.android.data.DistillationEntity
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.ModelEntity
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.data.whisperModels
import dev.distillpod.android.features.AppViewModel
import dev.distillpod.android.features.OnboardingWelcomeRoute
import dev.distillpod.android.features.SetupGuideRoute
import dev.distillpod.core.PcmWaveWriter
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Select one case with firstRunOnboarding=true after a fresh install on a disposable emulator. */
@RunWith(AndroidJUnit4::class)
class OnboardingLibraryDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val card = hasTestTag("library-setup-card")

    @Test fun signedOutListenerBrowsesPlaysAndFindsBothSetupActions() {
        val context = freshInstall()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModel(scenario)
            awaitRoute(vm, OnboardingWelcomeRoute)
            compose.onNodeWithText("Example").performScrollTo().assertExists()
            tap("Find my first podcast")
            awaitRoute(vm, "search")

            val graph = vm.graph
            val dao = graph.db.library()
            runBlocking {
                graph.settings.text("model", "base")
                val tiny = whisperModels.single { it.id == "tiny" }
                RandomAccessFile(File(graph.media.modelsDirectory, "tiny.bin"), "rw").use { it.setLength(tiny.bytes) }
                dao.putModel(ModelEntity("tiny", "ready", tiny.bytes))
                // A stale ready row shouldn't hide the selected model's download action.
                dao.putModel(ModelEntity("base", "ready", whisperModels.single { it.id == "base" }.bytes))
            }
            fixtureServer(context).use { server ->
                val input = compose.onNodeWithContentDescription("Search your library and podcasts")
                input.performTextReplacement(server.url("/feed").toString())
                input.performImeAction()
                tap("Browse episodes")
                compose.waitUntil(10_000) { vm.backStack.value.last().startsWith("podcast:") }
                val show = runBlocking { dao.podcasts().single() }
                assertFalse(show.subscribed)
                assertNull(runBlocking { graph.db.sync().approved(show.id) })
                compose.onNode(card).assertDoesNotExist()

                val episode = runBlocking { dao.episodesForPodcast(show.id).single() }
                compose.onNodeWithContentDescription("Play ${episode.title}").performClick()
                compose.waitUntil(15_000) { vm.player.value.episodeId == episode.id && vm.player.value.playing }
                tap("Library")
                awaitRoute(vm, "library")
                compose.onNode(hasText("Setup guide") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                    .assertIsDisplayed()
                tap("Download Base")
                awaitRoute(vm, "settings:transcription")
                compose.onNode(hasText("Download Base") and hasClickAction()).performScrollTo().assertIsDisplayed()
                assertTrue(vm.player.value.playing)

                device.pressBack()
                awaitRoute(vm, "library")
                tap("Connect ChatGPT")
                awaitRoute(vm, "settings:ai")
                compose.onNodeWithText("Continue with ChatGPT").performScrollTo().assertIsDisplayed()
                assertFalse(graph.account.state.value.connected)
                assertTrue(vm.player.value.playing)

                device.pressBack()
                awaitRoute(vm, "library")
                tap("Save your first moment")
                awaitRoute(vm, "player")
                runBlocking { graph.playback.pause() }
            }
        }
    }

    @Test fun saveActionUsesLibraryOrSearchAndDismissalLeavesGuideAvailable() {
        freshInstall()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModel(scenario)
            awaitRoute(vm, OnboardingWelcomeRoute)
            tap("Skip setup")
            awaitRoute(vm, "library")
            tap("Save your first moment")
            awaitRoute(vm, "search")
            device.pressBack()
            awaitRoute(vm, "library")

            val dao = vm.graph.db.library()
            val show = PodcastEntity("setup-show", "https://example.test/setup.rss", "Setup fixture show")
            val episode = EpisodeEntity("setup-episode", show.id, "fixture", "Setup fixture episode", "https://example.test/setup.mp3")
            runBlocking { dao.putPodcast(show); dao.putEpisode(episode) }
            compose.waitUntil(10_000) { vm.library.value?.visible?.singleOrNull()?.id == episode.id }

            compose.onNode(hasScrollToIndexAction()).performScrollToKey("filters")
            compose.onNodeWithContentDescription("Downloaded").performClick()
            compose.onNode(card).assertDoesNotExist()
            compose.onNodeWithContentDescription("Latest").performClick()
            awaitCard()
            tap("Save your first moment")
            awaitRoute(vm, "episode:${episode.id}")
            device.pressBack()
            awaitRoute(vm, "library")

            runBlocking { dao.putDistillation(DistillationEntity("setup-moment", episode.id, 0, 2_000)) }
            awaitCard(visible = false)
            runBlocking { dao.deleteDistillation("setup-moment") }
            awaitCard()
            tap("Dismiss")
            awaitCard(visible = false)
            assertTrue(runBlocking { vm.graph.settings.firstRunSetup.first()!!.cardDismissed })
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModel(scenario)
            awaitRoute(vm, "library")
            compose.onNode(card).assertDoesNotExist()
            compose.onNodeWithContentDescription("Settings").performClick()
            tap("About DistillPod")
            tap("Setup guide")
            awaitRoute(vm, SetupGuideRoute)
            tap("On-device transcripts")
            awaitRoute(vm, "settings:transcription")
        }
    }

    @Suppress("DEPRECATION")
    private fun freshInstall(): Context {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("firstRunOnboarding") == "true" && args.getString("firstRunUpgrade") != "true")
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")) { "Use a disposable emulator" }
        val context = instrumentation.targetContext
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(installed.firstInstallTime, installed.lastUpdateTime)
        assertNull(runBlocking { context.appGraph.settings.firstRunSetup.first() })
        return context
    }

    private fun viewModel(scenario: ActivityScenario<MainActivity>): AppViewModel {
        lateinit var vm: AppViewModel
        scenario.onActivity { activity ->
            vm = ViewModelProvider(activity, AppViewModel.factory(activity.applicationContext.appGraph))[AppViewModel::class.java]
        }
        return vm
    }

    private fun awaitRoute(vm: AppViewModel, route: String) =
        compose.waitUntil(15_000) { vm.loaded.value && vm.backStack.value.last() == route }

    private fun awaitCard(visible: Boolean = true) =
        compose.waitUntil(10_000) { compose.onAllNodes(card).fetchSemanticsNodes().isNotEmpty() == visible }

    private fun tap(label: String) = compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()

    private fun fixtureServer(context: Context): MockWebServer {
        val path = File.createTempFile("onboarding-", ".wav", context.cacheDir)
        val audio = try {
            PcmWaveWriter(path, sampleRate = 16_000, channels = 1).use { writer ->
                repeat(120) { writer.append(ByteBuffer.allocate(32_000)) }
                writer.finish()
            }
            path.readBytes()
        } finally {
            path.delete()
        }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/feed" -> MockResponse().setBody("""
                    <rss version="2.0"><channel><title>Onboarding audio</title><item><guid>onboarding-audio</guid>
                    <title>Onboarding fixture episode</title>
                    <enclosure url="${server.url("/audio")}" type="audio/wav" length="${audio.size}"/>
                    </item></channel></rss>
                """.trimIndent())
                "/audio" -> MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(audio))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        return server
    }
}
