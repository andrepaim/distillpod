package dev.distillpod.android

import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession

/** Test-only coupling to pinned Media3 1.5.1; no production access hook or controller fallback. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun fixtureServicePlayer(episodeId:String):ExoPlayer {
    check(Looper.myLooper()==Looper.getMainLooper())
    val lockField=MediaSession::class.java.getDeclaredField("STATIC_LOCK").apply { isAccessible=true }
    val sessionsField=MediaSession::class.java.getDeclaredField("SESSION_ID_TO_SESSION_MAP").apply { isAccessible=true }
    val sessions=synchronized(lockField.get(null)!!) {
        val registry=sessionsField.get(null) as Map<*,*>
        registry.values.map { it as MediaSession }
    }
    // Never call player methods while holding Media3's registry lock.
    val matching=sessions.filter { it.player.currentMediaItem?.mediaId==episodeId }
    check(matching.size==1) { "Expected exactly one service session for the synthetic queue item" }
    val sessionPlayer = matching.single().player
    val engine = if (sessionPlayer is dev.distillpod.android.playback.OwnedAudioPlayer)
        sessionPlayer.wrappedPlayer else sessionPlayer
    return engine as? ExoPlayer
        ?: error("Fixture session must expose the actual ExoPlayer, not a controller")
}

/** Observe the actual service player while [edit] runs: the playing item must not be re-prepared or seek. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun assertEnqueueKeepsPlaying(episodeId:String,edit:suspend ()->Unit) {
    val main=kotlinx.coroutines.Dispatchers.Main
    val disruptions=mutableListOf<String>()
    val listener=object:androidx.media3.common.Player.Listener {
        override fun onPositionDiscontinuity(old:androidx.media3.common.Player.PositionInfo,new:androidx.media3.common.Player.PositionInfo,reason:Int) { disruptions+="discontinuity:$reason" }
        override fun onMediaItemTransition(item:androidx.media3.common.MediaItem?,reason:Int) { disruptions+="transition:$reason" }
        override fun onPlaybackStateChanged(state:Int) { disruptions+="state:$state" }
        override fun onPlayWhenReadyChanged(playWhenReady:Boolean,reason:Int) { disruptions+="playWhenReady:$playWhenReady" }
    }
    val (engine,item,before)=kotlinx.coroutines.withContext(main) {
        val engine=fixtureServicePlayer(episodeId)
        check(engine.isPlaying) { "Start playback before checking enqueue" }
        engine.addListener(listener)
        Triple(engine,engine.currentMediaItem,engine.currentPosition)
    }
    try {
        edit()
        kotlinx.coroutines.withContext(main) {
            org.junit.Assert.assertSame("The current item must keep its prepared source",item,engine.currentMediaItem)
            org.junit.Assert.assertTrue(engine.isPlaying)
            org.junit.Assert.assertTrue("Playback must continue forward",engine.currentPosition>=before)
            org.junit.Assert.assertEquals(emptyList<String>(),disruptions.toList())
        }
    } finally {
        kotlinx.coroutines.withContext(main) { engine.removeListener(listener) }
    }
}
