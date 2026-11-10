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

    private suspend fun fixture(transcriptFactory: ((Context, WorkerParameters) -> ListenableWorker)? = null, block: suspend (AppGraph, AppViewModel) -> Unit) {
        val heldWorkers = java.util.Collections.synchronizedList(mutableListOf<HoldingWorker>())
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(context: Context, name: String, params: WorkerParameters): ListenableWorker? =
                    if (name == TranscriptWorker::class.java.name && transcriptFactory != null) transcriptFactory(context, params)
                    else if (name == LibraryWorker::class.java.name || name == TranscriptWorker::class.java.name)
                        HoldingWorker(context, params).also { heldWorkers += it } else null
            }).build())
        // Policy actions are explicit here; application startup/recovery must not race setup.
        // Match the existing isolated-graph fixtures: DEFAULT-start jobs never begin.
        val inactive = SupervisorJob().apply { cancel() }
        val graph = AppGraph(context, CoroutineScope(inactive + Dispatchers.Main.immediate))
        assertTrue(inactive.isCompleted)
        assertFalse(graph.ready.isCompleted)
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

    @Test fun `queued transcript keeps new network policy after a capture changes next chunk`() = runBlocking {
        fixture { graph, vm ->
            val dao = graph.db.library()
            val ep = episode("priority-remote").copy(durationMs = 600000)
            dao.putEpisode(ep)
            dao.putTranscript(TranscriptEntity(ep.id, "priority-revision", ep.mediaFingerprint, "tiny", "en", durationMs = 600000))
            graph.settings.flag("wifi", false)
            graph.settings.flag("charging", true)
            graph.transcription.recover()
            val name = "transcript:priority-remote:priority-revision:0"
            val before = active(name)
            assertEquals(WorkInfo.State.ENQUEUED, before.state)
            assertEquals(NetworkType.CONNECTED, before.constraints.requiredNetworkType)
            dao.putDistillation(DistillationEntity("priority-capture", ep.id, 470000, 480000,
                revision = "priority-revision", fingerprint = ep.mediaFingerprint))
            toggle(vm, true)
            val after = active(name)
            assertEquals(before.id, after.id)
            assertEquals(WorkInfo.State.ENQUEUED, after.state)
            assertEquals(NetworkType.UNMETERED, after.constraints.requiredNetworkType)
        }
    }

    @Test fun `checkpoint movement updates all current chunks but not superseded or cancelled work`() = runBlocking {
        fixture { graph, vm ->
            val dao = graph.db.library()
            val ep = episode("checkpoints").copy(durationMs = 600000)
            dao.putEpisode(ep)
            dao.putTranscript(TranscriptEntity(ep.id, "current", ep.mediaFingerprint, "tiny", "en", durationMs = 600000))
            graph.settings.flag("wifi", false)
            graph.settings.flag("charging", true)
            graph.transcription.recover()
            val first = active("transcript:checkpoints:current:0")
            dao.putCoverage(CoverageEntity(ep.id, "current", 0, 120000))
            graph.transcription.recover()
            val second = active("transcript:checkpoints:current:120000")
            fun request(revision: String) = OneTimeWorkRequestBuilder<TranscriptWorker>()
                .setInputData(workDataOf("episode" to ep.id, "revision" to revision))
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(20, TimeUnit.MINUTES)
                .addTag("transcript:${ep.id}").addTag("transcript-revision:${ep.id}:$revision").build()
            val stale = request("superseded")
            val cancelled = request("current")
            manager.enqueue(stale).result.get(); manager.enqueue(cancelled).result.get()
            manager.cancelWorkById(cancelled.id).result.get()
            for (enabled in listOf(true, false, true)) {
                toggle(vm, enabled)
                for (original in listOf(first, second)) {
                    val updated = manager.getWorkInfoById(original.id).get()!!
                    assertEquals(if (enabled) NetworkType.UNMETERED else NetworkType.CONNECTED, updated.constraints.requiredNetworkType)
                    assertEquals(WorkInfo.State.ENQUEUED, updated.state)
                    assertTrue(updated.constraints.requiresCharging())
                }
                val untouched = manager.getWorkInfoById(stale.id).get()!!
                assertEquals(NetworkType.CONNECTED, untouched.constraints.requiredNetworkType)
                assertEquals(0, untouched.generation)
                assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(cancelled.id).get()!!.state)
                assertEquals(1, dao.coverage(ep.id, "current").size)
            }
            graph.transcription.pause(ep.id)
            toggle(vm, false)
            assertTrue(manager.getWorkInfosByTag("transcript:${ep.id}").get().all { it.state == WorkInfo.State.CANCELLED })
            assertEquals("paused", dao.transcript(ep.id)!!.state)
        }
    }

    @Test fun `legacy requests migrate once without losing checkpoints or reviving old revisions`() = runBlocking {
        fixture { graph, vm ->
            val dao = graph.db.library()
            val ep = episode("legacy").copy(durationMs = 240000)
            dao.putEpisode(ep)
            dao.putTranscript(TranscriptEntity(ep.id, "current", ep.mediaFingerprint, "tiny", "en", durationMs = 240000, processedMs = 120000))
            dao.putCoverage(CoverageEntity(ep.id, "current", 0, 120000))
            dao.putSegments(listOf(SegmentEntity(ep.id, "current", 0, 1000, "Saved text", 0)))
            graph.settings.flag("charging", true)
            val legacy = listOf("current", "superseded").map { revision ->
                OneTimeWorkRequestBuilder<TranscriptWorker>()
                    .setInputData(workDataOf("episode" to ep.id, "revision" to revision))
                    .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .addTag("transcript:${ep.id}").build().also {
                        // Old start is outside the revised duration: do not guess a range of names.
                        manager.enqueueUniqueWork("transcript:${ep.id}:$revision:480000", ExistingWorkPolicy.KEEP, it).result.get()
                    }
            }
            toggle(vm, true)
            legacy.forEach { assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(it.id).get()!!.state) }
            val replacement = active("transcript:legacy:current:120000")
            assertEquals(NetworkType.UNMETERED, replacement.constraints.requiredNetworkType)
            assertTrue(replacement.constraints.requiresCharging())
            assertTrue(replacement.constraints.requiresBatteryNotLow())
            assertTrue("transcript-revision:legacy:current" in replacement.tags)
            assertEquals(120000L, dao.transcript(ep.id)!!.processedMs)
            assertEquals("Saved text", dao.segments(ep.id, "current").single().text)
            assertEquals(120000L, dao.coverage(ep.id, "current").single().endMs)
            toggle(vm, false)
            assertEquals(replacement.id, active("transcript:legacy:current:120000").id)
            assertEquals(1, manager.getWorkInfosByTag("transcript:legacy").get().count { !it.state.isFinished })
        }
    }

    @Test fun `legacy migration waits for the active repository chunk before cancelling requests`() = runBlocking {
        fixture { graph, _ ->
            val dao = graph.db.library()
            val ep = episode("legacy-active").copy(durationMs = 240000)
            dao.putEpisode(ep)
            val row = TranscriptEntity(ep.id, "current", ep.mediaFingerprint, "tiny", "en", durationMs = 240000, lookupOnline = true)
            dao.putTranscript(row)
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val repository = TranscriptionRepository(graph) { _, _ ->
                entered.complete(Unit)
                finish.await()
                // Leave a pending recovery checkpoint after this synthetic active chunk.
                dao.putTranscript(row.copy(processedMs = 120000))
                dao.putCoverage(CoverageEntity(ep.id, row.revision, 0, 120000))
                true
            }
            val legacy = OneTimeWorkRequestBuilder<TranscriptWorker>()
                .setInputData(workDataOf("episode" to ep.id, "revision" to row.revision))
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("transcript:${ep.id}").build()
            manager.enqueueUniqueWork("transcript:${ep.id}:current:0", ExistingWorkPolicy.KEEP, legacy).result.get()
            coroutineScope {
                val activeChunk = launch(Dispatchers.IO) { repository.process(ep.id, row.revision, TranscriptExecution({ false }, {}, false)) }
                entered.await()
                val policy = async(Dispatchers.IO) { repository.configureNetworkPolicy() }
                delay(100)
                assertFalse(policy.isCompleted)
                assertFalse(manager.getWorkInfoById(legacy.id).get()!!.state.isFinished)
                finish.complete(Unit)
                activeChunk.join(); policy.await()
            }
            assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(legacy.id).get()!!.state)
            assertEquals(NetworkType.UNMETERED, active("transcript:${ep.id}:current:120000").constraints.requiredNetworkType)
            assertEquals(120000L, dao.transcript(ep.id)!!.processedMs)
        }
    }

    @Test fun `pause while legacy migration waits cancels active and queued writers without revival`() = runBlocking {
        lateinit var repository: TranscriptionRepository
        fixture(transcriptFactory = { context, params ->
            object : CoroutineWorker(context, params) {
                override suspend fun doWork(): Result {
                    repository.process("legacy-paused", "current", TranscriptExecution({ isStopped }, {}, false))
                    return Result.success()
                }
            }
        }) { graph, _ ->
            val dao = graph.db.library()
            val ep = episode("legacy-paused").copy(durationMs = 240000)
            dao.putEpisode(ep)
            dao.putTranscript(TranscriptEntity(ep.id, "current", ep.mediaFingerprint, "tiny", "en", durationMs = 240000, lookupOnline = true))
            val entered = CompletableDeferred<Unit>()
            var writers = 0
            repository = TranscriptionRepository(graph) { _, _ ->
                writers++
                entered.complete(Unit)
                awaitCancellation()
            }
            coroutineScope {
                val activeChunk = launch(Dispatchers.IO) { repository.process(ep.id, "current", TranscriptExecution({ false }, {}, false)) }
                entered.await()
                val legacy = OneTimeWorkRequestBuilder<TranscriptWorker>()
                    .setInputData(workDataOf("episode" to ep.id, "revision" to "current"))
                    .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                    .addTag("transcript:${ep.id}").build()
                manager.enqueueUniqueWork("transcript:${ep.id}:current:0", ExistingWorkPolicy.KEEP, legacy).result.get()
                WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(legacy.id)
                settle { manager.getWorkInfoById(legacy.id).get()!!.state == WorkInfo.State.RUNNING }
                val policy = async(Dispatchers.IO) { repository.configureNetworkPolicy() }
                delay(100)
                assertFalse(policy.isCompleted)
                repository.pause(ep.id)
                activeChunk.join(); policy.await()
                settle { manager.getWorkInfoById(legacy.id).get()!!.state == WorkInfo.State.CANCELLED }
            }
            assertEquals(1, writers)
            assertEquals("paused", dao.transcript(ep.id)!!.state)
            assertTrue(manager.getWorkInfosByTag("transcript:${ep.id}").get().all { it.state == WorkInfo.State.CANCELLED })
        }
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
                    .addTag("transcript:$kind").addTag("transcript-revision:$kind:saved-revision").build()
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
