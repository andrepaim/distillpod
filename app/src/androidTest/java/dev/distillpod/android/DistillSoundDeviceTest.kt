package dev.distillpod.android

import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DistillSoundDeviceTest {
    @Test fun committedMomentStartsNativeConfirmationWithoutStartingPodcast()=runBlocking {
        check(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.FINGERPRINT.contains("generic"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val graph=context.appGraph;graph.ready.await();graph.playback.pause()
        val previous=graph.settings.flow.first().distillSound
        val id="sound-test-${UUID.randomUUID()}"
        val audio=context.getSystemService(AudioManager::class.java)
        val quietDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(audio.isMusicActive && System.nanoTime()<quietDeadline)Thread.sleep(20)
        assertFalse("Unexpected media was already playing",audio.isMusicActive)
        try {
            graph.settings.flag("distillSound",true)
            graph.db.library().putPodcast(PodcastEntity(id,"https://example.invalid/$id","Sound fixture"))
            graph.db.library().putEpisode(EpisodeEntity(id,id,id,"Sound fixture","https://example.invalid/audio"))
            val saved=graph.captures.save(id,5000,false)
            assertNotNull(graph.db.library().distillation(saved))
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(!audio.isMusicActive && System.nanoTime()<deadline)Thread.sleep(10)
            assertTrue("Native confirmation audio never became active",audio.isMusicActive)
            assertFalse(graph.playback.state.value.playing)
        } finally {
            graph.settings.flag("distillSound",previous)
            graph.db.library().deletePodcast(id)
        }
    }
}
