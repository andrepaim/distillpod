package dev.distillpod.android

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.data.*
import dev.distillpod.android.features.AppViewModel
import dev.distillpod.android.features.TranscriptListItem
import dev.distillpod.android.features.TranscriptTools
import dev.distillpod.android.ui.theme.DistillTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * #336: a transcript queued behind another said only "after another transcript", so a 12-minute wait
 * looked like nothing was happening, and one still said "waiting for a charger" after the setting was
 * turned off. The episode row and Transcript tools now name what is prepared first and its progress.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueuedTranscriptStatusUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val application get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var graph: AppGraph
    private lateinit var vm: AppViewModel
    private val store = ViewModelStore()
    private val release = CompletableDeferred<Unit>()
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var chunk: Job? = null
    private fun episode(id: String, title: String) = EpisodeEntity(id, "show", "guid-$id", title, "https://example.test/$id.mp3",
        mediaFingerprint = "audio-$id", durationMs = 600_000,
        localFile = File(application.cacheDir, "$id.mp3").apply { writeBytes(byteArrayOf(1)) }.path)
    private val first = episode("first", "How sleep shapes memory, with a neuroscientist who studies dreams")
    private val waiting = episode("waiting", "Rest and effort")

    @Before fun setup() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(application,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        shadowOf(application).declareComponentUnbindable(ComponentName(application, PlaybackService::class.java))
        application.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, 80)
            .putExtra(BatteryManager.EXTRA_SCALE, 100).putExtra(BatteryManager.EXTRA_PLUGGED, 0))
        graph = application.appGraph
        graph.scope.cancel()
        withTimeout(5000) { while (!graph.scope.coroutineContext[Job]!!.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle(); delay(10)
        } }
        graph.settings.flag("onlineTranscripts", false)
        val dao = graph.db.library()
        dao.putPodcast(PodcastEntity("show", "https://example.test/feed", "Show"))
        dao.putEpisode(first)
        dao.putEpisode(waiting)
        // 6:12 of 10:00 is saved: 62%.
        dao.putTranscript(TranscriptEntity(first.id, "first-revision", first.mediaFingerprint, "tiny", "auto",
            state = "queued", processedMs = 372_000, durationMs = 600_000))
        vm = AppViewModel(graph, SavedStateHandle())
        store.put("queued", vm)
    }

    @After fun cleanup() = runBlocking {
        release.complete(Unit)
        chunk?.let { withTimeout(10_000) { it.join() } }
        val viewModelJob = if (::vm.isInitialized) vm.viewModelScope.coroutineContext.job else null
        store.clear()
        WorkManager.getInstance(application).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        viewModelJob?.awaitViewModelTeardown()
        graph.db.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    /** The first episode's chunk holds the slot until [release] completes. */
    private fun prepareFirst() = runBlocking {
        val started = CompletableDeferred<Unit>()
        chunk = work.launch {
            graph.transcription.process(first.id, "first-revision", TranscriptExecution({ false }, { started.complete(Unit); release.await() }, false))
        }
        withTimeout(10_000) { started.await() }
    }

    private fun showWaiting() = compose.setContent { DistillTheme { Column {
        TranscriptListItem(vm, waiting)
        TranscriptTools(vm, waiting)
    } } }

    private fun shows(text: String) {
        compose.waitUntil(5000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertExists()
    }

    /** The running chunk's live progress, as nativeTranscribe's callback reports it. */
    private fun transcribed(workedMs: Long) {
        val live = ReflectionHelpers.getField<MutableStateFlow<Map<String, TranscriptProgress>>>(graph.transcription, "live")
        live.update { all -> all + (first.id to all.getValue(first.id).copy(stage = TranscriptStage.TRANSCRIBING, workedMs = workedMs)) }
    }

    @Test fun `a transcript queued behind another names it and follows its progress`() {
        prepareFirst()
        runBlocking { graph.transcription.prepare(waiting.id, requested = true) }
        showWaiting()
        shows("Queued · after another transcript (62%)")
        shows("Queued · \"How sleep shapes memory, with a…\" is being prepared first (62%). Transcripts are prepared one at a time.")
        transcribed(390_000)
        shows("Queued · after another transcript (65%)")
        shows("Queued · \"How sleep shapes memory, with a…\" is being prepared first (65%). Transcripts are prepared one at a time.")
        // Once nothing else is being prepared, the transcript no longer says it waits behind one.
        release.complete(Unit)
        runBlocking { withTimeout(10_000) { chunk!!.join() } }
        shows("Queued · waiting to start")
        shows("Queued · waiting for Android to start transcription")
    }

    @Test fun `turning charging-only off replaces the charger wait while another transcript is prepared`() {
        runBlocking {
            graph.settings.flag("charging", true) // Robolectric reports no charger.
            graph.transcription.prepare(waiting.id, requested = true)
        }
        showWaiting()
        shows("Queued · waiting for a charger")
        shows("Queued · waiting for a charger because \"Only transcribe while charging\" is on")
        prepareFirst()
        vm.flag("charging", false)
        shows("Queued · after another transcript (62%)")
        shows("Queued · \"How sleep shapes memory, with a…\" is being prepared first (62%). Transcripts are prepared one at a time.")
    }
}
