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
    @Test fun olderCapturedQueueCannotRepublishAfterCompletedCloudChoice() = runBlocking<Unit> {
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val graph=instrumentation.targetContext.appGraph
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
            var before=0L
            db.withTransaction {
                // The writer cannot commit the older [a,b] snapshot while this transaction is held.
                graph.playback.enqueue(b)
                withTimeout(10000){while(actual()!=listOf(a,b))delay(25)}
                before=withContext(Dispatchers.Main){player!!.currentPosition}
                choice=graph.playback.beginCloudQueueChoice(
                    resolve={portable.resolve("queue_library",false)},
                    imported={graph.importedQueue.value=it})
            }
            withTimeout(15000){choice.await()}
            withTimeout(10000){while(actual()!=listOf(a,c,b))delay(25)}
            graph.playback.awaitQueueWritesProcessed()
            assertEquals("A processed older boundary must not replace the chosen persisted order",listOf(c,b),dao.queue().map {it.episodeId})
            assertEquals(listOf(a,c,b),actual())
            withContext(Dispatchers.Main){
                assertEquals(a,player!!.currentMediaItem!!.mediaId)
                assertTrue(player!!.isPlaying)
                assertTrue(player!!.currentPosition>=before)
            }
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
