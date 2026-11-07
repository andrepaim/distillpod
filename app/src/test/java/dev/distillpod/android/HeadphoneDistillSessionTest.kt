package dev.distillpod.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.SessionResult
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.ai.AiGateway
import dev.distillpod.android.ai.AiText
import dev.distillpod.android.auto.AutoLibraryCallback
import dev.distillpod.android.data.*
import dev.distillpod.android.playback.PlaybackControls
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HeadphoneDistillSessionTest {
    private class Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val ready = CompletableDeferred<Unit>()
        var enabled = true
        var voiceBuild = true
        var voiceEnabled = false
        var questions = 0
        var failSave = false
        var failPrepare = false
        var platformTrusted = false
        val sounds = AtomicInteger()
        val preparations = AtomicInteger()
        val saves = AtomicInteger()
        val origins = java.util.concurrent.CopyOnWriteArrayList<String>()
        var failures = 0
        val repository = CaptureRepository(db, object : AiGateway {
            override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit): AiText =
                error("Headphone capture must not call AI directly")
        }, { false }, {}, {}, onSaved = { sounds.incrementAndGet() })
        val callback = AutoLibraryCallback(context.packageName, db, ready, scope,
            saveMoment = { id, position, file -> persistMoment(id,position,file,"background") },
            saveMomentWithSource = { id,position,file,source ->
                origins+=source;persistMoment(id,position,file,source)
            }, prepareMoment = {
                preparations.incrementAndGet()
                if (failPrepare) error("Preparation unavailable")
            }, headphoneDistillEnabled = { enabled }, onHeadphoneCaptureFailure = { failures++ },
            isTrustedMediaButtonCaller = { platformTrusted && it.uid == 1000 },
            voiceEnabled = { voiceBuild }, headphoneVoiceEnabled = { voiceEnabled }, onHeadphoneVoice = { questions++ })
        private suspend fun persistMoment(id:String,position:Long,file:String?,source:String):String {
            saves.incrementAndGet()
            if(failSave)error("Storage unavailable")
            return repository.save(id,position,file=="/episode.wav",source=source)
        }
        val player = ExoPlayer.Builder(context).build()
        val session = MediaLibrarySession.Builder(context, player, callback).build()
        val controller = MediaSession.ControllerInfo.createTestOnlyControllerInfo("android", 1, 1000, 1, 1, true, Bundle.EMPTY)
        init {
            runBlocking {
                db.library().putPodcast(PodcastEntity("p", "https://example.invalid/feed", "Show"))
                db.library().putEpisode(EpisodeEntity("e", "p", "e", "Episode", "https://example.invalid/audio",
                    durationMs = 120000, localFile = "/episode.wav", mediaFingerprint = "source"))
            }
            player.setMediaItem(MediaItem.Builder().setMediaId("e").setUri("file:///episode.wav").build(), 75000)
        }
        fun key(action: Int = KeyEvent.ACTION_DOWN, downTime: Long = 10, repeat: Int = 0,
                caller: MediaSession.ControllerInfo = controller, code: Int = KeyEvent.KEYCODE_MEDIA_PREVIOUS): Boolean = callback.onMediaButtonEvent(session, caller,
            Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT,
                KeyEvent(downTime, downTime, action, code, repeat)))
        fun rows() = runBlocking { db.library().distillations() }
        fun close() {
            session.release(); player.release()
            scope.cancel()
            await { scope.coroutineContext[Job]!!.isCompleted }
            db.close()
        }
    }
    companion object {
        private fun await(predicate: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000
            while (System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                if (predicate()) return
                Thread.sleep(10)
            }
            fail("Asynchronous capture did not finish")
        }
    }
    private fun fixture(block: (Fixture) -> Unit) {
        val f = Fixture()
        try { block(f) } finally { f.close() }
    }

    @Test fun `headphone capture snapshots original source before async save and survives preparation failure`() = fixture { f ->
        f.failPrepare = true
        assertTrue(f.key())
        assertTrue(f.key(KeyEvent.ACTION_UP))
        f.player.setMediaItem(MediaItem.Builder().setMediaId("other").setUri("file:///other.wav").build(), 90000)
        f.ready.complete(Unit)
        await { f.preparations.get() == 1 }
        val saved = f.rows().single()
        assertEquals(listOf("headphones"), f.origins)
        assertEquals("e", saved.episodeId)
        assertEquals(15000L, saved.startMs)
        assertEquals(75000L, saved.endMs)
        assertEquals("source", saved.fingerprint)
        assertEquals("waiting_transcript", saved.state)
        assertEquals(1, f.sounds.get())
        assertEquals(0, f.failures)
    }

    @Test fun `repeat events and overlapping custom command cannot create duplicate saves`() = fixture { f ->
        assertTrue(f.key())
        assertTrue(f.key())
        assertTrue(f.key(repeat = 1))
        assertTrue(f.key(KeyEvent.ACTION_UP))
        val overlapping = f.callback.onCustomCommand(f.session, f.controller, PlaybackControls.distill, Bundle.EMPTY)
        assertNotEquals(SessionResult.RESULT_SUCCESS, overlapping.get().resultCode)
        f.ready.complete(Unit)
        await { f.preparations.get() == 1 }
        assertEquals(1, f.rows().size)
        assertEquals(1, f.saves.get())
        assertEquals(1, f.sounds.get())
        assertEquals("e", f.player.currentMediaItem!!.mediaId)
        assertEquals(75000L, f.player.currentPosition)
    }

    @Test fun `failed save reports failure without skipping or sounding and permits later capture`() = fixture { f ->
        f.ready.complete(Unit); f.failSave = true
        f.key()
        await { f.failures == 1 }
        assertTrue(f.rows().isEmpty())
        assertEquals(0, f.sounds.get())
        assertEquals(0, f.preparations.get())
        assertEquals(75000L, f.player.currentPosition)
        f.failSave = false
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        f.key(downTime = 1010)
        await { f.preparations.get() == 1 }
        assertEquals(1, f.rows().size)
    }

    @Test fun `missing current episode reports failure and untrusted controllers cannot capture`() = fixture { f ->
        f.ready.complete(Unit)
        val caller = MediaSession.ControllerInfo.createTestOnlyControllerInfo("untrusted", 1, 1001, 1, 1, false, Bundle.EMPTY)
        assertFalse(f.key(caller = caller))
        assertEquals(0, f.saves.get())
        f.player.clearMediaItems()
        assertTrue(f.key())
        await { f.failures == 1 }
        assertEquals(0, f.sounds.get())
        assertTrue(f.rows().isEmpty())
    }

    @Test fun `disabled mapping delegates while explicit distill command still saves`() = fixture { f ->
        f.ready.complete(Unit); f.enabled = false
        assertFalse(f.key())
        val result = f.callback.onCustomCommand(f.session, f.controller, PlaybackControls.distill, Bundle.EMPTY)
        await { result.isDone }
        assertEquals(SessionResult.RESULT_SUCCESS, result.get().resultCode)
        assertEquals(f.rows().single().id, result.get().extras.getString("momentId"))
    }

    @Test fun `legacy system keys rely on platform verification when Media3 omits trust`() = fixture { f ->
        f.ready.complete(Unit)
        val system = MediaSession.ControllerInfo.createTestOnlyControllerInfo("android", 1, 1000, 0, 0, false, Bundle.EMPTY)
        assertFalse(f.key(caller = system))
        f.platformTrusted = true
        assertTrue(f.key(caller = system))
        await { f.preparations.get() == 1 }
        assertEquals(1, f.rows().size)
    }
    @Test fun `next asks once without skipping or distilling and previous still saves`() = fixture { f ->
        f.ready.complete(Unit); f.voiceEnabled = true
        assertTrue(f.key(code = KeyEvent.KEYCODE_MEDIA_NEXT))
        assertTrue(f.key(code = KeyEvent.KEYCODE_MEDIA_NEXT))
        assertTrue(f.key(repeat = 1, code = KeyEvent.KEYCODE_MEDIA_NEXT))
        assertTrue(f.key(KeyEvent.ACTION_UP, code = KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(1, f.questions); assertEquals(0, f.saves.get())
        assertEquals("e", f.player.currentMediaItem!!.mediaId); assertEquals(75000L, f.player.currentPosition)
        assertTrue(f.key())
        await { f.saves.get() == 1 }
        assertEquals(1, f.questions)
    }
    @Test fun `next delegates when preference or build flag is off`() = fixture { f ->
        assertFalse(f.key(code = KeyEvent.KEYCODE_MEDIA_NEXT))
        f.voiceEnabled = true; f.voiceBuild = false
        assertFalse(f.key(code = KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(0, f.questions)
    }
    @Test fun `next rejects untrusted callers and allows verified legacy keys`() = fixture { f ->
        f.voiceEnabled = true
        val untrusted = MediaSession.ControllerInfo.createTestOnlyControllerInfo("untrusted", 1, 1001, 0, 0, false, Bundle.EMPTY)
        assertFalse(f.key(caller = untrusted, code = KeyEvent.KEYCODE_MEDIA_NEXT))
        val system = MediaSession.ControllerInfo.createTestOnlyControllerInfo("android", 1, 1000, 0, 0, false, Bundle.EMPTY)
        assertFalse(f.key(caller = system, code = KeyEvent.KEYCODE_MEDIA_NEXT))
        f.platformTrusted = true
        assertTrue(f.key(caller = system, code = KeyEvent.KEYCODE_MEDIA_NEXT)); assertEquals(1, f.questions)
    }
}
