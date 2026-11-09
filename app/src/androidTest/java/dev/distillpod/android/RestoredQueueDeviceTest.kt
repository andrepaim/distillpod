package dev.distillpod.android

import android.os.Build
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import dev.distillpod.android.sync.SyncViewEntity
import dev.distillpod.android.sync.CloudSubscription
import dev.distillpod.android.sync.projectCollection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Normal cloud projection into a running empty service, on disposable emulators only. */
@RunWith(AndroidJUnit4::class)
class RestoredQueueDeviceTest {
    @Test fun projectedQueueRestoresAndCanBeCleared()=runBlocking<Unit> {
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val graph=instrumentation.targetContext.appGraph;graph.ready.await()
        val dao=graph.db.library()
        val oldQueue=dao.queue();val oldSession=dao.playerSession()
        val oldView=graph.db.sync().view("queue_library")
        val oldTranscribe=graph.settings.flow.first().transcribeOnPlayback
        val tag="restore-queue-"+UUID.randomUUID()
        val seed="$tag-seed";val restored="$tag-restored"
        val file=File(instrumentation.targetContext.cacheDir,"$tag.wav")
        instrumentation.context.assets.open("jfk.wav").use {input->file.outputStream().use {output->input.copyTo(output)}}
        suspend fun awaitQueue(expected:List<String>,phase:String) {
            var persisted=emptyList<String>()
            try {
                withTimeout(15000) {
                    graph.playback.state.first {it.queue==expected}
                    do {persisted=dao.queue().map {it.episodeId};if(persisted!=expected)delay(50)} while(persisted!=expected)
                }
            } catch(failure:Throwable) {
                throw AssertionError("Restored queue phase '$phase' failed; expectedCount=${expected.size}, controllerCount=${graph.playback.state.value.queue.size}, persistedCount=${persisted.size}",failure)
            }
        }
        var primary:Throwable?=null
        try {
            graph.settings.flag("transcribeOnPlayback",false)
            try {withTimeout(10000) {graph.preferences.first {!it.transcribeOnPlayback}}}
            catch(failure:Throwable) {throw AssertionError("Restored queue phase automatic transcription disabled failed",failure)}
            graph.playback.pause();graph.playback.clear();awaitQueue(emptyList(),"initial clear")
            dao.putPodcast(PodcastEntity(tag,"https://example.test/$tag","Restored queue fixture"))
            listOf(seed,restored).forEach {id->dao.putEpisode(EpisodeEntity(id,tag,id,"Restored queue item","https://example.test/$id",localFile=file.absolutePath,downloadState="downloaded"))}
            // Establish that the serialized writer has processed a nonempty queue and its clear.
            graph.playback.play(seed,1000);graph.playback.pause();awaitQueue(listOf(seed),"seed persisted")
            graph.playback.clear();awaitQueue(emptyList(),"seed cleared")
            graph.db.withTransaction {
                graph.db.sync().putView(SyncViewEntity("queue_library"))
                projectCollection(graph.db,CloudSubscription("queue_library",1,mapOf("items" to restored),false,1))
            }
            assertNull(graph.importedQueue.value)
            awaitQueue(listOf(restored),"normal cloud restore")
            // Mirrors an observer-first restore followed by the normal explicit restore callback.
            graph.playback.synchronizeRestoredQueue()
            assertEquals(listOf(restored),dao.queue().map {it.episodeId})
            graph.playback.clear();awaitQueue(emptyList(),"clear restored queue")
            try {withTimeout(10000) {while(graph.activeFiles.queued.contains(file.absolutePath) || graph.activeFiles.playback==file.absolutePath)delay(50)}}
            catch(failure:Throwable) {throw AssertionError("Restored queue phase file ownership released failed; queued=${graph.activeFiles.queued.contains(file.absolutePath)}, current=${graph.activeFiles.playback==file.absolutePath}",failure)}
            assertFalse(graph.playback.state.value.playing)
            assertEquals("downloaded",dao.episode(restored)!!.downloadState)
            assertTrue(file.exists())
        } catch(failure:Throwable) {primary=failure;throw failure} finally {
            try {
                graph.playback.pause();graph.playback.clear();awaitQueue(emptyList(),"cleanup clear")
                graph.db.withTransaction {
                    dao.deletePodcast(tag);dao.clearQueue();dao.insertQueue(oldQueue)
                    if(oldSession!=null)dao.putPlayerSession(oldSession)
                    graph.db.sync().putView(oldView?:SyncViewEntity("queue_library"))
                }
                graph.settings.flag("transcribeOnPlayback",oldTranscribe)
                file.delete()
            } catch(cleanup:Throwable) {val original=primary;if(original!=null)original.addSuppressed(cleanup) else throw cleanup}
        }
    }
}
