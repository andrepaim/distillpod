package dev.distillpod.android

import android.os.Build
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import dev.distillpod.android.sync.*
import dev.distillpod.core.PcmWaveWriter
import dev.distillpod.core.readWhisperWave
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Deliberately holds a local writer across a real isolated explicit-cloud resolution. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CloudQueueOrderingDeviceTest {
    @Test fun olderCapturedQueueCannotRepublishAfterCompletedCloudChoice() = CloudQueueOrderingFixture.scenario("normal")
    @Test fun failedResolutionPreservesLocalQueueAndOriginalFailure() = CloudQueueOrderingFixture.scenario("failure")
    @Test fun localClearAcceptedWhileCloudChoiceWaitsWins() = CloudQueueOrderingFixture.scenario("clear")
    @Test fun localClearCannotBeUndoneByAnOlderQueueFlowEmission() = CloudQueueOrderingFixture.scenario("later-clear")
    @Test fun failedPlayerApplicationRetainsCommittedCloudOrder() = CloudQueueOrderingFixture.scenario("apply-failure")
    @Test fun cancelledResolutionDoesNotStopThePersistenceWriter() = CloudQueueOrderingFixture.scenario("resolver-cancel")
    @Test fun cancellationBeforeWriterOwnershipDoesNotResolve() = CloudQueueOrderingFixture.scenario("cancel")
    @Test fun cancellationAfterWriterOwnershipDoesNotStrandCommittedChoice() = CloudQueueOrderingFixture.scenario("cancel-running")

}

internal object CloudQueueOrderingFixture {
    fun scenario(mode:String, offlinePhone:Boolean=false) = runBlocking<Unit> {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        if(offlinePhone) {
            check(context.packageName=="dev.distillpod.android.validation.queue75")
            for(permission in listOf(android.Manifest.permission.INTERNET,android.Manifest.permission.RECORD_AUDIO))
                check(context.checkSelfPermission(permission)==android.content.pm.PackageManager.PERMISSION_DENIED)
        } else check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"))
        val graph=context.appGraph
        graph.ready.await()
        val db=graph.db; val dao=db.library(); val sync=db.sync()
        check(sync.binding()==null) { "Use an unbound disposable fixture application" }
        val oldQueue=dao.queue(); val oldSession=dao.playerSession(); val oldView=sync.view("queue_library")
        val tag="queue75-"+UUID.randomUUID(); val a="$tag-a"; val b="$tag-b"; val c="$tag-c"
        val uid="$tag-owner"; val generation="isolated"
        val audio=File.createTempFile("queue75-", ".wav", graph.context.cacheDir)
        val samples=readWhisperWave(instrumentation.context.assets.open("jfk.wav").use {it.readBytes()})
        val pcm=ByteBuffer.allocate(samples.size*2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach {pcm.putShort((it*32768).toInt().coerceIn(-32768,32767).toShort())}
        PcmWaveWriter(audio,16000,1).use {writer->repeat(20){writer.append(ByteBuffer.wrap(pcm.array()))};writer.finish()}
        var player:ExoPlayer?=null
        var volume:Float?=null
        var primary:Throwable?=null
        suspend fun actual():List<String> = withContext(Dispatchers.Main) {
            val p=checkNotNull(player)
            (0 until p.mediaItemCount).map {p.getMediaItemAt(it).mediaId}
        }
        try {
            graph.playback.pause();graph.playback.clear()
            graph.playback.awaitQueueWritesProcessed()
            dao.putPodcast(PodcastEntity(tag,"https://example.test/$tag","Queue ordering fixture"))
            for(id in listOf(a,b,c))dao.putEpisode(EpisodeEntity(id,tag,id,"Queue ordering item","https://example.test/$id",
                durationMs=220000,localFile=audio.absolutePath,downloadState="downloaded",mediaFingerprint=id))
            graph.playback.play(a,3000)
            withTimeout(15000) {
                while(true) {
                    if(graph.playback.state.value.episodeId==a && dao.queue().map {it.episodeId}==listOf(a)) {
                        player=withContext(Dispatchers.Main){fixtureServicePlayer(a)}
                        if(withContext(Dispatchers.Main){player!!.isPlaying})break
                    }
                    delay(25)
                }
            }
            withContext(Dispatchers.Main){volume=player!!.volume;player!!.volume=0f}
            graph.playback.awaitQueueWritesProcessed()
            sync.putBinding(SyncBindingEntity(uid=uid,generation=generation,enabled=false,lease=tag))
            sync.approve(SyncPublicFeedEntity(tag))
            sync.putView(SyncViewEntity("queue_library",version=1,active=true))
            sync.enqueue(SyncOutboxEntity(tag,uid,generation,"queue_library",1,
                JSONObject(mapOf("items" to a)).toString(),false,state="conflict"))
            sync.putRemote(SyncRemoteEntity(uid,generation,"queue_library",2,
                JSONObject(mapOf("items" to "$c,$b")).toString(),false,2))
            val portable=PortableLibrarySync(db,isolatedDemo=true)
            lateinit var choice:Deferred<Unit>
            val resolving=CompletableDeferred<Unit>()
            val release=CompletableDeferred<Unit>()
            var resolved=false
            var before=0L
            db.withTransaction {
                // The writer cannot commit the older [a,b] snapshot while this transaction is held.
                graph.playback.enqueue(b)
                withTimeout(10000){while(actual()!=listOf(a,b))delay(25)}
                before=withContext(Dispatchers.Main){player!!.currentPosition}
                choice=graph.playback.beginCloudQueueChoice(
                    resolve={
                        resolving.complete(Unit)
                        if(mode=="cancel-running")withTimeout(15000){release.await()}
                        if(mode=="failure")error("synthetic resolution failure")
                        if(mode=="resolver-cancel")throw CancellationException("synthetic resolver cancellation")
                        portable.resolve("queue_library",false);resolved=true
                    },
                    imported={if(mode=="apply-failure")error("synthetic player application failure") else graph.importedQueue.value=it})
                if(mode=="cancel")choice.cancel()
                if(mode=="clear") {
                    graph.playback.clear()
                    withTimeout(10000){while(actual().isNotEmpty())delay(25)}
                }
            }
            if(mode=="cancel-running") {
                withTimeout(15000){resolving.await()}
                choice.cancel();release.complete(Unit)
            }
            if(mode in listOf("failure","apply-failure")) {
                try {withTimeout(15000){choice.await()};fail("Expected original resolver failure")}
                catch(expected:IllegalStateException){assertEquals(if(mode=="failure")"synthetic resolution failure" else "synthetic player application failure",expected.message)}
            } else if(mode.startsWith("cancel") || mode=="resolver-cancel") {
                try {choice.await();fail("Caller cancellation must be visible")}
                catch(expected:CancellationException) {assertTrue(choice.isCancelled)}
            } else withTimeout(15000){choice.await()}
            if(mode in listOf("failure","cancel","clear","apply-failure","resolver-cancel")) {
                graph.playback.awaitQueueWritesProcessed()
                val expected=if(mode=="clear")emptyList() else listOf(a,b)
                assertEquals("Actual service order after $mode",expected,actual())
                assertEquals("Persisted order after $mode",if(mode=="apply-failure")listOf(c,b) else expected,dao.queue().map {it.episodeId})
                assertEquals(mode in listOf("clear","apply-failure"),resolved)
                if(mode!="clear")withContext(Dispatchers.Main){assertTrue(player!!.isPlaying)}
                return@runBlocking
            }
            withTimeout(10000){while(actual()!=listOf(a,c,b))delay(25)}
            graph.playback.awaitQueueWritesProcessed()
            assertEquals("A processed older boundary must not replace the chosen persisted order",listOf(c,b),dao.queue().map {it.episodeId})
            assertEquals(listOf(a,c,b),actual())
            withContext(Dispatchers.Main){
                assertEquals(a,player!!.currentMediaItem!!.mediaId)
                assertTrue(player!!.isPlaying)
                assertTrue(player!!.currentPosition>=before)
            }
            // Later intentional edits still publish, including removal of the temporary active item.
            graph.playback.move(b,-1)
            withTimeout(10000){while(actual()!=listOf(a,b,c))delay(25)}
            graph.playback.awaitQueueWritesProcessed()
            assertEquals(listOf(a,b,c),dao.queue().map {it.episodeId})
            if(mode=="later-clear") {
                db.withTransaction {
                    graph.playback.move(c,-1)
                    withTimeout(10000){while(actual()!=listOf(a,c,b))delay(25)}
                    graph.playback.clear()
                    withTimeout(10000){while(actual().isNotEmpty())delay(25)}
                }
            } else graph.playback.clear()
            graph.playback.awaitQueueWritesProcessed()
            assertTrue("Actual service must stay cleared after an older write/Room emission",actual().isEmpty())
            assertTrue("Persisted queue must stay cleared",dao.queue().isEmpty())
        } catch(failure:Throwable){primary=failure;throw failure}
        finally {
            try {
                graph.playback.pause();graph.playback.clear()
                graph.playback.awaitQueueWritesProcessed()
                player?.let {p->withContext(Dispatchers.Main){volume?.let {p.volume=it}}}
                db.withTransaction {
                    dao.deletePodcast(tag);dao.clearQueue();dao.insertQueue(oldQueue)
                    if(oldSession!=null)dao.putPlayerSession(oldSession)
                    else db.openHelper.writableDatabase.execSQL("DELETE FROM player_session")
                    for(table in listOf("sync_outbox","sync_remote"))db.openHelper.writableDatabase.execSQL(
                        "DELETE FROM $table WHERE uid=? AND generation=?",arrayOf(uid,generation))
                    db.openHelper.writableDatabase.execSQL("DELETE FROM sync_binding WHERE uid=?",arrayOf(uid))
                    db.openHelper.writableDatabase.execSQL("DELETE FROM sync_public_feeds WHERE podcastId=?",arrayOf(tag))
                    db.openHelper.writableDatabase.execSQL("DELETE FROM sync_views WHERE entityKey='queue_library'")
                    if(oldView!=null)sync.putView(oldView)
                }
                check(audio.delete())
            } catch(cleanup:Throwable){if(primary!=null)primary.addSuppressed(cleanup) else throw cleanup}
        }
    }
}
