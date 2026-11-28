package dev.distillpod.android

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #336: turning "Only transcribe while charging" off updated the queued requests but not the wait their
 * rows showed, so a transcript behind the one that took the slot kept "Queued · waiting for a charger".
 * A queued row's wait now follows settings changes and the transcript being prepared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptionWaitRefreshTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    // Downloaded audio: local transcription needs no network, so only power and the queue decide.
    private fun episode(n: Int) = EpisodeEntity("refresh-episode-$n", "refresh-show", "guid-$n", "Episode $n",
        "https://example.test/$n.mp3", mediaFingerprint = "audio-$n", durationMs = 10000,
        localFile = File(context.cacheDir, "refresh-$n.mp3").apply { writeBytes(byteArrayOf(1)) }.path)

    private fun battery(plugged: Boolean) = context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
        .putExtra(BatteryManager.EXTRA_LEVEL, 80).putExtra(BatteryManager.EXTRA_SCALE, 100)
        .putExtra(BatteryManager.EXTRA_PLUGGED, if (plugged) BatteryManager.BATTERY_PLUGGED_AC else 0))

    private suspend fun fixture(block: suspend CoroutineScope.(AppGraph) -> Unit) = coroutineScope {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        battery(plugged = false)
        // Explicit repository calls only: no startup recovery or worker runs.
        val graph = AppGraph(context, CoroutineScope(SupervisorJob().apply { cancel() } + Dispatchers.Main.immediate))
        try {
            graph.settings.flag("onlineTranscripts", false)
            graph.settings.flag("charging", false)
            graph.db.library().putPodcast(PodcastEntity("refresh-show", "https://example.test/feed", "Show"))
            for (n in 1..3) graph.db.library().putEpisode(episode(n))
            block(graph)
        } finally { graph.db.close() }
    }

    private suspend fun revision(graph: AppGraph, n: Int) = graph.db.library().transcript(episode(n).id)!!.revision

    /** A chunk of episode [n] that holds the slot until [release] completes. */
    private suspend fun CoroutineScope.preparing(graph: AppGraph, n: Int, release: CompletableDeferred<Unit>): Job {
        val started = CompletableDeferred<Unit>()
        val revision = revision(graph, n)
        val chunk = launch(Dispatchers.IO) {
            graph.transcription.process(episode(n).id, revision, TranscriptExecution({ false }, { started.complete(Unit); release.await() }, false))
        }
        withTimeout(10_000) { started.await() }
        return chunk
    }

    /** As Settings does: the flag, then the queued requests follow it (#301). */
    private suspend fun setting(graph: AppGraph, key: String, value: Boolean) {
        graph.settings.flag(key, value)
        graph.transcription.configureNetworkPolicy()
    }

    @Test fun `turning charging-only off shows a queued transcript behind the one being prepared`() = runBlocking<Unit> {
        fixture { graph ->
            val transcription = graph.transcription
            graph.settings.flag("charging", true) // Robolectric reports no charger.
            transcription.prepare(episode(1).id)
            transcription.prepare(episode(2).id, requested = true)
            assertNull(transcription.nextForeground())
            assertEquals("charging_required", transcription.waitReasons.value[revision(graph, 2)])
            // Another transcript takes the slot, as on the phone at 06:31:20.
            val release = CompletableDeferred<Unit>()
            val chunk = preparing(graph, 1, release)
            setting(graph, "charging", false)
            assertEquals("queued_behind", transcription.waitReasons.value[revision(graph, 2)])
            release.complete(Unit)
            withTimeout(10_000) { chunk.join() }
        }
    }

    @Test fun `turning charging-only off with nothing being prepared leaves the transcripts waiting to start`() = runBlocking<Unit> {
        fixture { graph ->
            val transcription = graph.transcription
            graph.settings.flag("charging", true)
            transcription.prepare(episode(1).id)
            transcription.prepare(episode(2).id)
            assertNull(transcription.nextForeground())
            setting(graph, "charging", false)
            assertEquals(listOf(1, 2).map { revision(graph, it) }.associateWith { "scheduled" },
                transcription.waitReasons.value.filterKeys { it in listOf(revision(graph, 1), revision(graph, 2)) })
        }
    }

    @Test fun `turning charging-only on shows the charger wait instead of the queue`() = runBlocking<Unit> {
        fixture { graph ->
            val transcription = graph.transcription
            transcription.prepare(episode(1).id)
            val release = CompletableDeferred<Unit>()
            val chunk = preparing(graph, 1, release)
            transcription.prepare(episode(2).id, requested = true)
            assertEquals("queued_behind", transcription.waitReasons.value[revision(graph, 2)])
            setting(graph, "charging", true)
            assertEquals("charging_required", transcription.waitReasons.value[revision(graph, 2)])
            // The transcript being prepared shows its own progress, not a wait.
            assertNull(transcription.waitReasons.value[revision(graph, 1)])
            release.complete(Unit)
            withTimeout(10_000) { chunk.join() }
        }
    }

    @Test fun `a setting that does not change a wait leaves it as it was`() = runBlocking<Unit> {
        fixture { graph ->
            val transcription = graph.transcription
            transcription.prepare(episode(1).id)
            transcription.recover(refused = true) // Android refused the session: it waits for a start Android allows.
            val revision = revision(graph, 1)
            assertEquals("restricted", transcription.waitReasons.value[revision])
            // Downloaded audio needs no network, so the Wi-Fi setting changes nothing here.
            setting(graph, "wifi", true)
            assertEquals("restricted", transcription.waitReasons.value[revision])
            setting(graph, "charging", true)
            assertEquals("charging_required", transcription.waitReasons.value[revision])
        }
    }

    @Test fun `a transcript that starts shows the ones whose charger wait ended behind it`() = runBlocking<Unit> {
        fixture { graph ->
            val transcription = graph.transcription
            graph.settings.flag("charging", true)
            transcription.prepare(episode(1).id)
            transcription.prepare(episode(2).id)
            assertNull(transcription.nextForeground())
            assertEquals("charging_required", transcription.waitReasons.value[revision(graph, 2)])
            battery(plugged = true) // Nothing reschedules on its own when the charger is connected.
            val release = CompletableDeferred<Unit>()
            val chunk = preparing(graph, 1, release)
            assertEquals("queued_behind", transcription.waitReasons.value[revision(graph, 2)])
            release.complete(Unit)
            withTimeout(10_000) { chunk.join() }
        }
    }

    @Test fun `the end of a chunk records what the queued transcripts wait for now`() = runBlocking<Unit> {
        fixture { graph ->
            val transcription = graph.transcription
            graph.settings.flag("charging", true)
            transcription.prepare(episode(1).id)
            transcription.prepare(episode(2).id)
            assertNull(transcription.nextForeground())
            val release = CompletableDeferred<Unit>()
            val chunk = preparing(graph, 1, release)
            assertEquals("charging_required", transcription.waitReasons.value[revision(graph, 2)])
            battery(plugged = true)
            release.complete(Unit)
            withTimeout(10_000) { chunk.join() }
            // The chunk ended at the model check (none is installed) and nothing else is being prepared.
            assertEquals("waiting_model", graph.db.library().transcript(episode(1).id)!!.state)
            assertEquals("scheduled", transcription.waitReasons.value[revision(graph, 2)])
        }
    }

    @Test fun `opening the app shows waits that ended in the background`() = runBlocking<Unit> {
        fixture { graph ->
            val transcription = graph.transcription
            graph.settings.flag("charging", true)
            transcription.prepare(episode(1).id)
            transcription.prepare(episode(2).id)
            assertNull(transcription.nextForeground())
            battery(plugged = true)
            transcription.resumeQueued()
            // The first goes to the session; the other one no longer waits for a charger.
            assertNotEquals("charging_required", transcription.waitReasons.value[revision(graph, 2)])
        }
    }
}
