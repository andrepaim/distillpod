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
    return matching.single().player as? ExoPlayer
        ?: error("Fixture session must expose the actual ExoPlayer, not a controller")
}
