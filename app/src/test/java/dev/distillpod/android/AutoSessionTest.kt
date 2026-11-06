package dev.distillpod.android

import android.content.Context
import android.os.Bundle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import dev.distillpod.android.auto.*
import dev.distillpod.android.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AutoSessionTest {
    private fun fixture(playbackReady:CompletableDeferred<Unit>?=null,voice:Boolean=false,openVoice:()->Boolean={false},block:(AutoLibraryCallback,MediaLibrarySession,MediaSession.ControllerInfo)->Unit) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        runBlocking {
            db.library().putPodcast(PodcastEntity("p","https://example.com/feed","Science"))
            db.library().putEpisode(EpisodeEntity("e","p","e","Episode","https://example.com/audio",positionMs=12000))
            db.library().insertQueue(listOf(QueueEntryEntity("e",0)))
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val ready=CompletableDeferred(Unit)
        val callback=AutoLibraryCallback(context.packageName,db,ready,scope,playbackReady?:ready,voiceEnabled={voice},openCarVoice=openVoice)
        val player=ExoPlayer.Builder(context).build()
        val session=MediaLibrarySession.Builder(context,player,callback).build()
        val controller=MediaSession.ControllerInfo.createTestOnlyControllerInfo("android.auto.test",1,1000,1,1,true,Bundle.EMPTY)
        try {block(callback,session,controller)}finally{session.release();player.release();scope.cancel();db.close()}
    }
    @Test fun `enabled voice advertises Ask in both modern and legacy car layouts`() = fixture(voice=true) { c,s,b ->
        val result = c.onConnect(s,b)
        val ask = dev.distillpod.android.voice.CarVoiceEntry.ASK
        assertTrue(result.availableSessionCommands.contains(ask))
        assertEquals("Ask about this", result.customLayout!!.single { it.sessionCommand == ask }.displayName.toString())
        assertTrue(result.mediaButtonPreferences!!.any { it.sessionCommand == ask })
    }
    @Test fun `disabled voice does not advertise an unusable car action`() = fixture { c,s,b ->
        val result = c.onConnect(s,b)
        assertFalse(result.availableSessionCommands.contains(dev.distillpod.android.voice.CarVoiceEntry.ASK))
        assertFalse(result.customLayout!!.any { it.sessionCommand == dev.distillpod.android.voice.CarVoiceEntry.ASK })
    }
    @Test fun `Ask dispatches to host bound car entry point`() {
        var calls = 0
        fixture(voice=true,openVoice={ calls++; true }) { c,s,b ->
            val result = c.onCustomCommand(s,b,dev.distillpod.android.voice.CarVoiceEntry.ASK,Bundle.EMPTY).get()
            assertEquals(SessionResult.RESULT_SUCCESS,result.resultCode)
            assertEquals(1,calls)
        }
    }
    @Test fun `unavailable car host reports unsupported without starting playback`() = fixture(voice=true) { c,s,b ->
        val result = c.onCustomCommand(s,b,dev.distillpod.android.voice.CarVoiceEntry.ASK,Bundle.EMPTY).get()
        assertEquals(SessionError.ERROR_NOT_SUPPORTED,result.resultCode)
        assertFalse(s.player.playWhenReady)
    }
    @Test fun `car token bridge is restricted to this application`() = fixture { c,s,b ->
        val command = dev.distillpod.android.voice.CarVoiceEntry.TOKEN
        assertFalse(c.onConnect(s,b).availableSessionCommands.contains(command))
        assertEquals(SessionError.ERROR_PERMISSION_DENIED,c.onCustomCommand(s,b,command,Bundle.EMPTY).get().resultCode)
        val internal = MediaSession.ControllerInfo.createTestOnlyControllerInfo(ApplicationProvider.getApplicationContext<Context>().packageName,1,1000,1,1,true,Bundle.EMPTY)
        assertTrue(c.onConnect(s,internal).availableSessionCommands.contains(command))
        val result = c.onCustomCommand(s,internal,command,Bundle.EMPTY).get()
        assertEquals(SessionResult.RESULT_SUCCESS,result.resultCode)
        @Suppress("DEPRECATION")
        val token = result.extras.getParcelable<android.media.session.MediaSession.Token>(dev.distillpod.android.voice.CarVoiceEntry.TOKEN_EXTRA)
        assertEquals(s.platformToken,token)
    }
    @Test fun `untrusted external controllers cannot connect`()=fixture {c,s,_ ->
        val caller=MediaSession.ControllerInfo.createTestOnlyControllerInfo("untrusted",1,1000,1,1,false,Bundle.EMPTY)
        assertFalse(c.onConnect(s,caller).isAccepted)
    }
    @Test fun `selection waits for startup restoration before resolving`() {
        val restored=CompletableDeferred<Unit>()
        fixture(restored) {c,s,b ->
            val pending=c.onSetMediaItems(s,b,listOf(MediaItem.Builder().setMediaId("e").build()),0,androidx.media3.common.C.TIME_UNSET)
            assertFalse(pending.isDone)
            restored.complete(Unit)
            assertEquals("e",pending.get(5,TimeUnit.SECONDS).mediaItems.single().mediaId)
        }
    }
    @Test fun `canceling a request waiting for startup does not dispatch it later`() {
        val restored=CompletableDeferred<Unit>()
        fixture(restored) {c,s,b ->
            val pending=c.onSetMediaItems(s,b,listOf(MediaItem.Builder().setMediaId("e").build()),0,androidx.media3.common.C.TIME_UNSET)
            assertTrue(pending.cancel(false));restored.complete(Unit)
            assertTrue(pending.isCancelled)
        }
    }
    @Test fun `trusted car controllers can execute advertised legacy seek actions`()=fixture {c,s,b ->
        val commands=c.onConnect(s,b).availableSessionCommands
        assertTrue(commands.contains(dev.distillpod.android.playback.PlaybackControls.back))
        assertTrue(commands.contains(dev.distillpod.android.playback.PlaybackControls.forward))
    }
    @Test fun `ID-only requests resolve actual audio and saved position`()=fixture { c,s,b ->
        val result=c.onSetMediaItems(s,b,listOf(MediaItem.Builder().setMediaId("e").build()),0,androidx.media3.common.C.TIME_UNSET).get(5,TimeUnit.SECONDS)
        assertEquals(12000L,result.startPositionMs)
        assertEquals("https://example.com/audio",result.mediaItems.single().localConfiguration!!.uri.toString())
    }
    @Test fun `external supplied URL cannot override authoritative episode source`()=fixture { c,s,b ->
        val result=c.onAddMediaItems(s,b,listOf(MediaItem.Builder().setMediaId("e").setUri("file:///private").build())).get(5,TimeUnit.SECONDS)
        assertEquals("https://example.com/audio",result.single().localConfiguration!!.uri.toString())
    }
    @Test fun `voice request metadata resolves a matching episode`()=fixture { c,s,b ->
        val request=MediaItem.Builder().setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("Science").build()).build()
        val result=c.onSetMediaItems(s,b,listOf(request),0,androidx.media3.common.C.TIME_UNSET).get(5,TimeUnit.SECONDS)
        assertEquals("e",result.mediaItems.single().mediaId)
    }
    @Test fun `library subscription is accepted and resumption restores saved queue`()=fixture { c,s,b ->
        assertEquals(LibraryResult.RESULT_SUCCESS,c.onSubscribe(s,b,AutoCatalog.ROOT,null).get(5,TimeUnit.SECONDS).resultCode)
        assertEquals(12000L,c.onPlaybackResumption(s,b).get(5,TimeUnit.SECONDS).startPositionMs)
    }
}
