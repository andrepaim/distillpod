package dev.distillpod.android

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import dev.distillpod.android.data.*
import dev.distillpod.android.features.AppViewModel
import dev.distillpod.android.work.LibraryWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic acceptance only. Physical runs use an isolated validation application. */
@RunWith(AndroidJUnit4::class)
class DownloadNetworkPolicyDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = compose.activity.applicationContext.appGraph
    private val manager get() = WorkManager.getInstance(graph.context)
    private val vm get() = ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java]
    private fun guard() {
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic") ||
            compose.activity.packageName == "dev.distillpod.android.validation.issue19") {
            "Physical tests require the isolated issue19 validation application"
        }
    }
    private suspend fun await(check: suspend () -> Boolean) = withTimeout(30000) { while (!check()) delay(50) }
    private fun work(name: String) = manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)
    private fun active(name: String) = work(name).single { !it.state.isFinished }
    private fun openStorage() = compose.runOnUiThread { vm.open("settings:storage") }
    private suspend fun toggle(enabled: Boolean) {
        val control = compose.onNodeWithContentDescription("Wi-Fi / unmetered downloads only")
        control.performScrollTo()
        if (enabled) control.assertIsOff() else control.assertIsOn()
        control.performClick()
        await { graph.settings.flow.first().wifiOnly == enabled && "settings" !in vm.busy.value }
        if (enabled) control.assertIsOn() else control.assertIsOff()
    }

    @Test fun queuedAudioModelsAndTranscriptsAdoptBothPolicyDirectionsWithoutLosingProgress() = runBlocking {
        guard(); graph.ready.await()
        val dao = graph.db.library()
        val prefix = "policy-${UUID.randomUUID()}"
        val prefs = graph.settings.flow.first()
        val files = mutableListOf<File>()
        val names = mutableListOf<String>()
        try {
            graph.settings.flag("refresh", false)
            manager.cancelUniqueWork("refresh-periodic").result.get()
            graph.settings.flag("wifi", true)
            dao.putPodcast(PodcastEntity(prefix, "https://example.test/$prefix", "Network fixture"))
            val audioPartial = File(graph.media.directory, "$prefix.partial").apply { writeText("saved audio prefix"); files += this }
            val modelPartial = File(graph.media.modelsDirectory, "tiny.partial").apply { writeText("saved model prefix"); files += this }
            dao.putEpisode(EpisodeEntity(prefix, prefix, prefix, "Fixture", "https://example.test/audio", downloadState = "queued",
                downloadedBytes = audioPartial.length(), etag = "audio-validator"))
            dao.putModel(ModelEntity("tiny", "queued", modelPartial.length(), "model-validator"))
            for ((kind, id) in listOf("download" to prefix, "model" to "tiny")) {
                val request = OneTimeWorkRequestBuilder<LibraryWorker>()
                    .setInputData(workDataOf("kind" to kind, "id" to id)).addTag("$kind:$id")
                    .setInitialDelay(1, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build()
                val name = "$kind:$id"; names += name
                manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).result.get()
            }
            val local = File(graph.context.filesDir, "$prefix-local.audio").apply { writeText("local"); files += this }
            for (kind in listOf("remote", "publisher", "lookup", "local")) {
                val id = "$prefix-$kind"
                dao.putEpisode(EpisodeEntity(id, prefix, id, "Fixture", "https://example.test/audio",
                    localFile = if (kind == "remote") null else local.path))
                dao.putTranscript(TranscriptEntity(id, "saved-revision", "fixture", "tiny", "en",
                    source = if (kind == "publisher") "publisher" else "whisper", processedMs = 120000, lookupOnline = kind == "lookup"))
                val request = OneTimeWorkRequestBuilder<TranscriptWorker>()
                    .setInputData(workDataOf("episode" to id, "revision" to "saved-revision")).addTag("transcript:$id")
                    .setInitialDelay(1, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true)
                        .setRequiredNetworkType(if (kind in listOf("local", "lookup")) NetworkType.NOT_REQUIRED else NetworkType.UNMETERED).build()).build()
                val name = "transcript:$id:saved-revision:0"; names += name
                manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).result.get()
            }
            val ids = names.associateWith { active(it).id }
            openStorage()
            for (enabled in listOf(false, true)) {
                toggle(enabled)
                for (name in names) {
                    val info = active(name)
                    assertEquals(ids[name], info.id)
                    assertEquals(TimeUnit.DAYS.toMillis(1), info.initialDelayMillis)
                    val localOnly = name.contains("-local:")
                    assertEquals(if (localOnly) NetworkType.NOT_REQUIRED else if (enabled) NetworkType.UNMETERED else NetworkType.CONNECTED,
                        info.constraints.requiredNetworkType)
                    if (name.startsWith("transcript:")) {
                        assertTrue(info.constraints.requiresCharging()); assertTrue(info.constraints.requiresBatteryNotLow())
                        val row = dao.transcript(name.removePrefix("transcript:").substringBefore(":saved-revision:"))!!
                        assertEquals("saved-revision", row.revision); assertEquals(120000L, row.processedMs)
                    }
                }
            }
            assertEquals("saved audio prefix", audioPartial.readText())
            assertEquals("saved model prefix", modelPartial.readText())
            assertEquals("audio-validator", dao.episode(prefix)!!.etag)
            assertEquals("model-validator", dao.model("tiny")!!.etag)
            for (name in names) manager.cancelUniqueWork(name).result.get()
            toggle(false)
            assertTrue(names.all { name -> work(name).all { it.state == WorkInfo.State.CANCELLED } })
        } finally {
            for (name in names) manager.cancelUniqueWork(name).result.get()
            dao.deletePodcast(prefix); dao.putModel(ModelEntity("tiny"))
            files.forEach { it.delete() }
            graph.settings.flag("wifi", prefs.wifiOnly); graph.settings.flag("refresh", prefs.refresh)
            graph.jobs.configureNetworkPolicy()
        }
    }

    @Test fun runningAudioFinishesOneValidatedRangeTransferAcrossPolicyChanges() = runBlocking {
        guard(); graph.ready.await()
        val dao = graph.db.library()
        val id = "policy-transfer-${UUID.randomUUID()}"
        val prefs = graph.settings.flow.first()
        val release = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(release.await(25, TimeUnit.SECONDS)) { "Policy update did not release the synthetic transfer" }
                return MockResponse().setResponseCode(206).setBody("world").setHeader("ETag", "\"fixture-v1\"")
                    .setHeader("Content-Range", "bytes 6-10/11")
            }
        }
        server.start()
        val partial = File(graph.media.directory, "$id.partial").apply { writeText("hello ") }
        var completed: File? = null
        try {
            graph.settings.flag("refresh", false)
            manager.cancelUniqueWork("refresh-periodic").result.get()
            graph.settings.flag("wifi", false); graph.jobs.configureNetworkPolicy()
            dao.putPodcast(PodcastEntity(id, server.url("/feed").toString(), "Network fixture"))
            dao.putEpisode(EpisodeEntity(id, id, id, "Fixture", server.url("/audio").toString(),
                downloadState = "paused", downloadedBytes = 6, totalBytes = 11, etag = "\"fixture-v1\""))
            graph.jobs.download(id)
            val request = server.takeRequest(15, TimeUnit.SECONDS) ?: error("No audio request")
            assertEquals("bytes=6-", request.getHeader("Range"))
            assertEquals("\"fixture-v1\"", request.getHeader("If-Range"))
            val original = active("download:$id")
            assertEquals(WorkInfo.State.RUNNING, original.state)
            openStorage()
            for (enabled in listOf(true, false)) {
                toggle(enabled)
                val info = active("download:$id")
                assertEquals(original.id, info.id)
                assertEquals(WorkInfo.State.RUNNING, info.state)
                assertEquals(if (enabled) NetworkType.UNMETERED else NetworkType.CONNECTED, info.constraints.requiredNetworkType)
                assertEquals(1, server.requestCount)
            }
            release.countDown()
            await { dao.episode(id)?.downloadState == "downloaded" && work("download:$id").single().state == WorkInfo.State.SUCCEEDED }
            completed = File(dao.episode(id)!!.localFile!!)
            assertEquals("hello world", completed!!.readText())
            assertEquals(11L, dao.episode(id)!!.downloadedBytes)
            assertEquals(1, server.requestCount)
            assertFalse(partial.exists())
        } finally {
            release.countDown()
            manager.cancelUniqueWork("download:$id").result.get()
            dao.deletePodcast(id); partial.delete(); completed?.delete()
            graph.settings.flag("wifi", prefs.wifiOnly); graph.settings.flag("refresh", prefs.refresh)
            graph.jobs.configureNetworkPolicy()
            server.shutdown()
        }
    }
}
