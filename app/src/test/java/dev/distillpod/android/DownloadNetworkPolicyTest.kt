package dev.distillpod.android

import android.content.Context
import android.content.ComponentName
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.data.*
import dev.distillpod.android.features.AppViewModel
import dev.distillpod.android.work.LibraryWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/** Exercise the Settings action against real WorkManager scheduling and cancellation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadNetworkPolicyTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val manager get() = WorkManager.getInstance(context)

    private suspend fun fixture(block: suspend (AppGraph, AppViewModel) -> Unit) {
        val heldWorkers = java.util.Collections.synchronizedList(mutableListOf<HoldingWorker>())
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(context: Context, name: String, params: WorkerParameters): ListenableWorker? =
                    if (name == LibraryWorker::class.java.name || name == TranscriptWorker::class.java.name)
                        HoldingWorker(context, params).also { heldWorkers += it } else null
            }).build())
        val graph = context.appGraph
        graph.scope.cancel()
        settle { graph.scope.coroutineContext[Job]!!.isCompleted }
        graph.ready.complete(Unit)
        graph.settings.flag("refresh", false)
        graph.settings.flag("wifi", true)
        graph.db.library().putPodcast(PodcastEntity("policy-show", "https://example.test/feed", "Fixture"))
        graph.db.library().putEpisode(episode("policy-audio"))
        shadowOf(context as android.app.Application).declareComponentUnbindable(ComponentName(context, PlaybackService::class.java))
        val vm = AppViewModel(graph, SavedStateHandle())
        val store = ViewModelStore().apply { put("settings", vm) }
        try { block(graph, vm) }
        catch (error: Throwable) {
            org.robolectric.shadows.ShadowLog.getLogs().filter { it.tag.startsWith("WM-") }.forEach {
                println("${it.tag}: ${it.msg}"); it.throwable?.printStackTrace(System.out)
            }
            throw error
        }
        finally {
            store.clear()
            manager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            graph.db.close()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

    private fun episode(id: String) = EpisodeEntity(id, "policy-show", id, "Fixture", "https://example.test/$id",
        mediaFingerprint = "fixture-audio")
    private fun work(name: String) = manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)
    private fun active(name: String) = work(name).single { !it.state.isFinished }
    private suspend fun settle(check: suspend () -> Boolean) = withTimeout(10000) {
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (check()) break
            delay(10)
        }
    }
    private suspend fun toggle(vm: AppViewModel, enabled: Boolean) {
        vm.flag("wifi", enabled)
        settle { "settings" !in vm.busy.value }
        assertEquals(enabled, vm.graph.settings.flow.first().wifiOnly)
    }

    @Test fun `both toggle directions update existing audio and model work without replacing progress`() = runBlocking {
        fixture { graph, vm ->
            val dao = graph.db.library()
            val audioPartial = File(graph.media.directory, "policy-audio.partial").apply { writeText("audio prefix") }
            val modelPartial = File(graph.media.modelsDirectory, "tiny.partial").apply { writeText("model prefix") }
            dao.putEpisode(episode("policy-audio").copy(downloadedBytes = audioPartial.length(), etag = "audio-validator"))
            dao.putModel(ModelEntity("tiny", "paused", modelPartial.length(), "model-validator"))
            graph.jobs.download("policy-audio"); graph.jobs.model("tiny")
            val audio = active("download:policy-audio"); val model = active("model:tiny")
            assertEquals(NetworkType.UNMETERED, audio.constraints.requiredNetworkType)
            for (enabled in listOf(false, true, false, true)) {
                toggle(vm, enabled)
                val expected = if (enabled) NetworkType.UNMETERED else NetworkType.CONNECTED
                assertEquals(expected, active("download:policy-audio").constraints.requiredNetworkType)
                assertEquals(expected, active("model:tiny").constraints.requiredNetworkType)
                assertEquals(audio.id, active("download:policy-audio").id)
                assertEquals(model.id, active("model:tiny").id)
                assertEquals("audio prefix", audioPartial.readText())
                assertEquals("model prefix", modelPartial.readText())
                assertEquals("audio-validator", dao.episode("policy-audio")!!.etag)
                assertEquals("model-validator", dao.model("tiny")!!.etag)
            }
        }
    }

    @Test fun `queued transcription changes network constraints while preserving charging checkpoints and local work`() = runBlocking {
        fixture { graph, vm ->
            val local = File(graph.context.filesDir, "policy-local.audio").apply { writeText("downloaded") }
            val revisions = mutableMapOf<String, java.util.UUID>()
            for (kind in listOf("remote", "publisher", "lookup", "local")) {
                val ep = episode(kind).copy(localFile = if (kind == "remote") null else local.path)
                graph.db.library().putEpisode(ep)
                graph.db.library().putTranscript(TranscriptEntity(kind, "saved-revision", ep.mediaFingerprint, "tiny", "en",
                    source = if (kind == "publisher") "publisher" else "whisper", processedMs = 120000, lookupOnline = kind == "lookup"))
                val request = OneTimeWorkRequestBuilder<TranscriptWorker>()
                    .setInputData(workDataOf("episode" to kind, "revision" to "saved-revision"))
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresCharging(true)
                        .setRequiredNetworkType(if (kind in listOf("local", "lookup")) NetworkType.NOT_REQUIRED else NetworkType.UNMETERED).build())
                    .addTag("transcript:$kind").build()
                manager.enqueueUniqueWork("transcript:$kind:saved-revision:0", ExistingWorkPolicy.KEEP, request).result.get()
                revisions[kind] = request.id
            }
            for (enabled in listOf(false, true)) {
                toggle(vm, enabled)
                for ((kind, id) in revisions) {
                    val info = active("transcript:$kind:saved-revision:0")
                    assertEquals(id, info.id)
                    assertEquals(if (kind == "local") NetworkType.NOT_REQUIRED else if (enabled) NetworkType.UNMETERED else NetworkType.CONNECTED,
                        info.constraints.requiredNetworkType)
                    assertTrue(info.constraints.requiresCharging())
                    assertTrue(info.constraints.requiresBatteryNotLow())
                    assertEquals(120000L, graph.db.library().transcript(kind)!!.processedMs)
                    assertEquals("saved-revision", graph.db.library().transcript(kind)!!.revision)
                }
            }
        }
    }

    @Test fun `policy updates never resurrect cancelled work and repeated requests keep one transfer`() = runBlocking {
        fixture { graph, vm ->
            graph.jobs.download("policy-audio"); graph.jobs.model("tiny")
            val audio = active("download:policy-audio"); val model = active("model:tiny")
            graph.jobs.pauseDownload("policy-audio"); graph.jobs.pauseModel("tiny")
            settle { work("download:policy-audio").all { it.state.isFinished } && work("model:tiny").all { it.state.isFinished } }
            toggle(vm, false)
            assertTrue(work("download:policy-audio").all { it.state == WorkInfo.State.CANCELLED })
            assertTrue(work("model:tiny").all { it.state == WorkInfo.State.CANCELLED })
            repeat(3) {
                graph.jobs.download("policy-audio"); graph.jobs.model("tiny")
                toggle(vm, it % 2 == 0)
            }
            assertNotEquals(audio.id, active("download:policy-audio").id)
            assertNotEquals(model.id, active("model:tiny").id)
            assertEquals(NetworkType.UNMETERED, active("download:policy-audio").constraints.requiredNetworkType)
            assertEquals(NetworkType.UNMETERED, active("model:tiny").constraints.requiredNetworkType)
            assertEquals(1, work("download:policy-audio").count { !it.state.isFinished })
            assertEquals(1, work("model:tiny").count { !it.state.isFinished })
        }
    }

    @Test fun `recovery constrains online lookup even when its audio is already local`() = runBlocking {
        fixture { graph, vm ->
            val local = File(graph.context.filesDir, "lookup-local.audio").apply { writeText("local fixture") }
            for (lookup in listOf(false, true)) {
                val id = "lookup-$lookup"
                val ep = episode(id).copy(localFile = local.path)
                graph.db.library().putEpisode(ep)
                graph.db.library().putTranscript(TranscriptEntity(id, "lookup-revision", ep.mediaFingerprint, "tiny", "en", lookupOnline = lookup))
            }
            graph.transcription.recover()
            val name = "transcript:lookup-true:lookup-revision:0"
            val original = active(name)
            assertEquals(NetworkType.UNMETERED, original.constraints.requiredNetworkType)
            assertEquals(NetworkType.NOT_REQUIRED, active("transcript:lookup-false:lookup-revision:0").constraints.requiredNetworkType)
            for (enabled in listOf(false, true)) {
                toggle(vm, enabled)
                assertEquals(if (enabled) NetworkType.UNMETERED else NetworkType.CONNECTED, active(name).constraints.requiredNetworkType)
                assertEquals(original.id, active(name).id)
                assertTrue(graph.db.library().transcript("lookup-true")!!.lookupOnline)
            }
        }
    }

    @Test fun `an active attempt is not cancelled or duplicated when the policy changes`() = runBlocking {
        fixture { graph, vm ->
            graph.jobs.download("policy-audio"); graph.jobs.model("tiny")
            val names = listOf("download:policy-audio", "model:tiny")
            val ids = names.associateWith { active(it).id }
            val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
            ids.values.forEach { driver.setAllConstraintsMet(it) }
            settle { names.all { name ->
                val infos = work(name)
                check(infos.none { it.state.isFinished }) { "$name unexpectedly finished: $infos" }
                infos.any { it.state == WorkInfo.State.RUNNING }
            } }
            toggle(vm, false)
            for (name in names) {
                val info = active(name)
                assertEquals(ids[name], info.id)
                assertEquals(WorkInfo.State.RUNNING, info.state)
                assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
                assertTrue(info.generation > 0)
                assertEquals(1, work(name).size)
            }
        }
    }

    @Test fun `simultaneous enqueue policy changes and cancellation converge without duplicate or resurrected jobs`() = runBlocking {
        fixture { graph, _ ->
            repeat(8) { round ->
                val enabled = round % 2 == 0
                coroutineScope {
                    launch(Dispatchers.IO) { graph.jobs.download("policy-audio"); graph.jobs.model("tiny") }
                    launch(Dispatchers.IO) { graph.settings.flag("wifi", enabled); graph.jobs.configureNetworkPolicy() }
                }
                val expected = if (enabled) NetworkType.UNMETERED else NetworkType.CONNECTED
                for (name in listOf("download:policy-audio", "model:tiny")) {
                    assertEquals(expected, active(name).constraints.requiredNetworkType)
                    assertEquals(1, work(name).count { !it.state.isFinished })
                }
                coroutineScope {
                    launch(Dispatchers.IO) { graph.jobs.configureNetworkPolicy() }
                    launch(Dispatchers.IO) { graph.jobs.pauseDownload("policy-audio"); graph.jobs.pauseModel("tiny") }
                }
                settle { listOf("download:policy-audio", "model:tiny").all { name -> work(name).all { it.state.isFinished } } }
                graph.jobs.configureNetworkPolicy()
                assertTrue(work("download:policy-audio").all { it.state == WorkInfo.State.CANCELLED })
                assertTrue(work("model:tiny").all { it.state == WorkInfo.State.CANCELLED })
            }
        }
    }
}

private class HoldingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Keep a reachable completion source while the fake transfer is suspended.
    private val completion = CompletableDeferred<Result>()
    override suspend fun doWork(): Result = completion.await()
}
