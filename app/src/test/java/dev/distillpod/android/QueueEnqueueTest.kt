package dev.distillpod.android

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.playback.enqueueMediaItem
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Enqueue must edit the playlist around the current item instead of rebuilding and re-preparing it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QueueEnqueueTest {
    private val engine = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
    private val calls = mutableListOf<String>()
    /** A playing session player is prepared; the unprepared engine supplies real playlist masking. */
    private var reportedState: Int? = Player.STATE_READY
    private val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
        calls += method.name
        if (method.name == "getPlaybackState") reportedState?.let { return@newProxyInstance it }
        try { method.invoke(engine, *args.orEmpty()) } catch (failure: InvocationTargetException) { throw failure.cause!! }
    } as Player
    private val events = mutableListOf<String>()
    private val disruptive = setOf("setMediaItem", "setMediaItems", "replaceMediaItem", "replaceMediaItems", "clearMediaItems",
        "removeMediaItem", "removeMediaItems", "prepare", "stop", "seekTo", "seekToDefaultPosition", "pause", "play", "setPlayWhenReady")

    @After fun release() { engine.release() }

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("https://example.test/$id.mp3").build()
    private fun ids() = (0 until engine.mediaItemCount).map { engine.getMediaItemAt(it).mediaId }
    private fun start(ids: List<String>, current: Int, positionMs: Long) {
        engine.setMediaItems(ids.map(::item), current, positionMs)
        engine.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) { events += "discontinuity:$reason" }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { events += "transition:$reason" }
            override fun onPlaybackStateChanged(state: Int) { events += "state:$state" }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { events += "playWhenReady:$playWhenReady" }
        })
    }
    private fun assertCurrentUndisturbed(current: MediaItem, index: Int, positionMs: Long) {
        assertSame(current, engine.currentMediaItem)
        assertEquals(index, engine.currentMediaItemIndex)
        assertEquals(positionMs, engine.currentPosition)
        assertEquals(emptyList<String>(), events)
        assertEquals(emptyList<String>(), calls.filter { it in disruptive })
    }

    @Test fun `add to queue appends without touching the current item`() {
        start(listOf("a", "b"), 0, 2000)
        val current = engine.currentMediaItem!!
        enqueueMediaItem(player, item("c"), next = false)
        assertEquals(listOf("a", "b", "c"), ids())
        assertCurrentUndisturbed(current, 0, 2000)
        assertTrue("addMediaItem" in calls)
    }

    @Test fun `play next inserts after the current item`() {
        start(listOf("a", "b", "c"), 1, 3000)
        val current = engine.currentMediaItem!!
        enqueueMediaItem(player, item("d"), next = true)
        assertEquals(listOf("a", "b", "d", "c"), ids())
        assertCurrentUndisturbed(current, 1, 3000)
    }

    @Test fun `queued items move instead of being duplicated`() {
        start(listOf("a", "b", "c"), 0, 1000)
        val current = engine.currentMediaItem!!
        enqueueMediaItem(player, item("c"), next = true)
        assertEquals(listOf("a", "c", "b"), ids())
        enqueueMediaItem(player, item("c"), next = false)
        assertEquals(listOf("a", "b", "c"), ids())
        // Already in place: nothing to change.
        enqueueMediaItem(player, item("c"), next = false)
        assertEquals(listOf("a", "b", "c"), ids())
        // An earlier item queued again moves after the current item when it should play next.
        engine.seekTo(1, 1000); events.clear(); calls.clear()
        val middle = engine.currentMediaItem!!
        enqueueMediaItem(player, item("a"), next = true)
        assertEquals(listOf("b", "a", "c"), ids())
        assertSame(middle, engine.currentMediaItem)
        assertEquals(0, engine.currentMediaItemIndex)
        assertEquals(1000, engine.currentPosition)
        assertEquals(emptyList<String>(), calls.filter { it in disruptive })
        assertTrue(events.none { it.startsWith("transition") || it.startsWith("state") })
        assertTrue("moveMediaItem" in calls)
        assertSame(current, engine.getMediaItemAt(1))
    }

    @Test fun `enqueueing the current item is a no-op`() {
        start(listOf("a", "b"), 1, 500)
        val current = engine.currentMediaItem!!
        enqueueMediaItem(player, item("b"), next = true)
        enqueueMediaItem(player, item("b"), next = false)
        assertEquals(listOf("a", "b"), ids())
        assertCurrentUndisturbed(current, 1, 500)
    }

    @Test fun `a restored but unprepared queue is prepared paused without moving its current item`() {
        reportedState = null
        start(listOf("a", "b"), 1, 4000)
        val current = engine.currentMediaItem!!
        enqueueMediaItem(player, item("c"), next = true)
        assertEquals(listOf("a", "b", "c"), ids())
        assertSame(current, engine.currentMediaItem)
        assertEquals(4000, engine.currentPosition)
        assertEquals(listOf("prepare"), calls.filter { it in disruptive })
        assertFalse(engine.playWhenReady)
    }

    @Test fun `an empty queue is prepared paused`() {
        reportedState = null
        enqueueMediaItem(player, item("a"), next = true)
        assertEquals(listOf("a"), ids())
        assertFalse(engine.playWhenReady)
        assertNotEquals(Player.STATE_IDLE, engine.playbackState)
        enqueueMediaItem(player, item("b"), next = false)
        assertEquals(listOf("a", "b"), ids())
        assertFalse(engine.playWhenReady)
    }
}
