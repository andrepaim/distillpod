package dev.distillpod.android

import android.content.Intent
import android.os.Build
import androidx.media3.session.MediaController
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs in its own instrumentation process: its application controller is deliberately closed. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CloudQueueTeardownDeviceTest {
    @Test fun stoppingServiceFailsQueuedCloudChoiceWithoutExecutingItsResolution()=runBlocking<Unit> {
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext;val graph=context.appGraph
        graph.ready.await()
        check(graph.db.sync().binding()==null)
        val db=graph.db;val dao=db.library()
        val oldQueue=dao.queue();val oldSession=dao.playerSession()
        val tag="queue-teardown-"+UUID.randomUUID();val a="$tag-a";val b="$tag-b"
        val audio=File.createTempFile("queue-teardown-",".wav",context.cacheDir)
        instrumentation.context.assets.open("jfk.wav").use {input->audio.outputStream().use {input.copyTo(it)}}
        var resolutionRan=false
        var controller:MediaController?=null
        var primary:Throwable?=null
        try {
            graph.playback.pause();graph.playback.clear();graph.playback.awaitQueueWritesProcessed()
            dao.putPodcast(PodcastEntity(tag,"https://example.test/$tag","Queue teardown fixture"))
            for(id in listOf(a,b))dao.putEpisode(EpisodeEntity(id,tag,id,"Teardown item","https://example.test/$id",localFile=audio.absolutePath,downloadState="downloaded"))
            dao.insertQueue(listOf(QueueEntryEntity(a,0),QueueEntryEntity(b,1)))
            graph.playback.synchronizeRestoredQueue()
            controller=graph.playback.sessionPlayer() as MediaController
            graph.playback.awaitQueueWritesProcessed()
            withTimeout(10000) {
                while(withContext(Dispatchers.Main){controller!!.mediaItemCount}!=2)delay(25)
            }
            val player=withContext(Dispatchers.Main){fixtureServicePlayer(a)}
            db.withTransaction {
                graph.playback.move(b,-1)
                withTimeout(10000){while(withContext(Dispatchers.Main){player.getMediaItemAt(0).mediaId}!=b)delay(25)}
                val choice=graph.playback.beginCloudQueueChoice(resolve={resolutionRan=true},imported={graph.importedQueue.value=it})
                withContext(Dispatchers.Main) {
                    controller!!.release()
                    context.stopService(Intent(context,PlaybackService::class.java))
                }
                try {withTimeout(10000){choice.await()};fail("Stopping playback must fail the pending choice")}
                catch(expected:CancellationException) {
                    assertTrue("An actual service stop must cancel the request",choice.isCancelled)
                    assertTrue(expected.message.orEmpty().contains("Playback service stopped"))
                }
            }
            assertFalse("A queued resolution must not run after service destruction",resolutionRan)
        } catch(failure:Throwable){primary=failure;throw failure}
        finally {
            try {
                withContext(Dispatchers.Main) {
                    controller?.release()
                    context.stopService(Intent(context,PlaybackService::class.java))
                }
                db.withTransaction {
                    dao.deletePodcast(tag);dao.clearQueue();dao.insertQueue(oldQueue)
                    if(oldSession!=null)dao.putPlayerSession(oldSession)
                    else db.openHelper.writableDatabase.execSQL("DELETE FROM player_session")
                }
                check(audio.delete())
            } catch(cleanup:Throwable){if(primary!=null)primary.addSuppressed(cleanup) else throw cleanup}
        }
    }
}
