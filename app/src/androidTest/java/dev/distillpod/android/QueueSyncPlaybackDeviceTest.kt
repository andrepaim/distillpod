package dev.distillpod.android

import android.os.Build
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QueueSyncPlaybackDeviceTest {
    @Test fun applyingCloudQueuePreservesCurrentAudioAndDoesNotEchoTheTemporaryActiveItem()=runBlocking {
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"))
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val graph=context.appGraph;graph.ready.await()
        val oldQueue=graph.db.library().queue();val oldSession=graph.db.library().playerSession()
        val tag="queue-sync-"+UUID.randomUUID().toString()
        val file=File(context.cacheDir,"$tag.wav")
        instrumentation.context.assets.open("jfk.wav").use {input->file.outputStream().use {input.copyTo(it)}}
        try {
            graph.playback.pause();graph.playback.clear()
            withTimeout(10000){graph.playback.state.first {it.queue.isEmpty()}}
            graph.db.library().putPodcast(PodcastEntity(tag,"https://example.test/$tag","Queue fixture"))
            for(id in listOf("a","b","c"))graph.db.library().putEpisode(EpisodeEntity("$tag-$id",tag,id,"Queue fixture","https://example.test/$id",localFile=file.absolutePath,downloadState="downloaded"))
            graph.playback.play("$tag-a",3000);graph.playback.enqueue("$tag-b")
            withTimeout(10000){graph.playback.state.first {it.episodeId=="$tag-a" && it.playing && it.queue.size==2}}
            val before=graph.playback.state.value.positionMs
            graph.db.withTransaction {
                graph.db.library().clearQueue();graph.db.library().insertQueue(listOf(QueueEntryEntity("$tag-c",0),QueueEntryEntity("$tag-b",1)))
            }
            graph.playback.acceptCloudQueue {graph.importedQueue.value=it}
            withTimeout(10000){graph.playback.state.first {it.queue==listOf("$tag-a","$tag-c","$tag-b")}}
            delay(300)
            assertEquals("$tag-a",graph.playback.state.value.episodeId)
            assertTrue(graph.playback.state.value.playing)
            assertTrue(graph.playback.state.value.positionMs>=before && graph.playback.state.value.positionMs<before+3000)
            assertEquals(listOf("$tag-c","$tag-b"),graph.db.library().queue().map {it.episodeId})
        } finally {
            graph.playback.pause();graph.playback.clear()
            withTimeout(10000){graph.playback.state.first {it.queue.isEmpty()}}
            delay(300)
            graph.db.withTransaction {
                graph.db.library().deletePodcast(tag);graph.db.library().clearQueue();graph.db.library().insertQueue(oldQueue)
                if(oldSession!=null)graph.db.library().putPlayerSession(oldSession)
            }
            file.delete()
        }
    }
}
