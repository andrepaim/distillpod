package dev.distillpod.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.ListenableFuture
import dev.distillpod.android.auto.AutoLibraryCallback
import dev.distillpod.android.data.AppDatabase
import dev.distillpod.android.playback.PlaybackControls
import dev.distillpod.android.widget.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** #189: the home-screen widget renders session state, redraws only on changes and captures through the session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NowPlayingWidgetTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private class FakePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var current: State = State.Builder().setAvailableCommands(Player.Commands.Builder().addAllCommands().build()).build()
        override fun getState() = current
        private fun update(block: State.Builder.() -> Unit) {
            current = current.buildUpon().apply(block).build(); invalidateState(); shadowOf(Looper.getMainLooper()).idle()
        }
        fun position(ms: Long) = update { setContentPositionMs(ms) }
        fun loading() = update { setIsLoading(true) }
        fun pauseHere() = update { setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        fun seekHere(ms: Long) = update { setContentPositionMs(ms).setPositionDiscontinuity(Player.DISCONTINUITY_REASON_SEEK, ms) }
        fun load(title: String = "Episode one", show: String? = "Science", positionMs: Long = 0, playing: Boolean = false) = update {
            setPlaylist(listOf(MediaItemData.Builder("e").setMediaItem(MediaItem.Builder().setMediaId("e")
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(show).build()).build()).build()))
            setCurrentMediaItemIndex(0)
            setContentPositionMs(positionMs)
            setPlaybackState(Player.STATE_READY)
            setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        }
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @After fun reset() { NowPlayingWidget.live = null }

    @Test fun `state comes from the session player and follows the Now Playing capture rule`() {
        val player = FakePlayer()
        assertEquals(NowPlayingWidgetState.IDLE, nowPlayingWidgetState(player))
        player.load(positionMs = 0)
        assertEquals(NowPlayingWidgetState("Episode one", "Science", playing = false, captureAvailable = false), nowPlayingWidgetState(player))
        player.load(positionMs = 999)
        assertFalse(nowPlayingWidgetState(player).captureAvailable)
        player.load(positionMs = 1_000)
        assertTrue(nowPlayingWidgetState(player).captureAvailable)
        // Playing audio passes 0:01 at once; the receiver re-checks the live position before saving.
        player.load(positionMs = 0, playing = true)
        assertEquals(NowPlayingWidgetState("Episode one", "Science", playing = true, captureAvailable = true), nowPlayingWidgetState(player))
        player.load(title = " ", show = null, positionMs = 5_000)
        assertEquals(NowPlayingWidgetState("DistillPod", null, playing = false, captureAvailable = true), nowPlayingWidgetState(player))
    }

    @Test fun `updater redraws only when the rendered state changes and goes idle with the session`() {
        val player = FakePlayer()
        val published = mutableListOf<NowPlayingWidgetState>()
        val updater = NowPlayingWidgetUpdater(player) { published += it }
        updater.start()
        assertEquals(listOf(NowPlayingWidgetState.IDLE), published)
        player.load(positionMs = 30_000, playing = true)
        assertEquals(2, published.size)
        assertTrue(published.last().playing)
        assertEquals(published.last(), NowPlayingWidget.live)
        // Progress and unrelated changes do not redraw (no position ticks).
        player.position(45_000)
        player.loading()
        player.load(positionMs = 60_000, playing = true)
        assertEquals(2, published.size)
        player.pauseHere()
        assertEquals(NowPlayingWidgetState("Episode one", "Science", playing = false, captureAvailable = true), published.last())
        player.seekHere(0)
        assertFalse(published.last().captureAvailable)
        val count = published.size
        updater.stop()
        assertEquals(NowPlayingWidgetState.IDLE, published.last())
        assertEquals(count + 1, published.size)
        assertNull(NowPlayingWidget.live)
        player.load(positionMs = 9_000, playing = true)
        assertEquals("A stopped updater no longer listens", count + 1, published.size)
    }

    @Test fun `views show titles, state-aware buttons and only explicit immutable intents`() {
        fun render(state: NowPlayingWidgetState) = NowPlayingWidget.views(context, state).apply(context, FrameLayout(context))
        val playing = render(NowPlayingWidgetState("Episode one", "Science", playing = true, captureAvailable = true))
        assertEquals("Episode one", playing.findViewById<TextView>(R.id.widget_title).text.toString())
        assertEquals("Science", playing.findViewById<TextView>(R.id.widget_show).text.toString())
        assertEquals("Pause", playing.findViewById<ImageButton>(R.id.widget_toggle).contentDescription)
        val distill = playing.findViewById<ImageButton>(R.id.widget_distill)
        assertEquals("Distill last 60 seconds", distill.contentDescription)
        assertTrue(distill.isEnabled)
        val idle = render(NowPlayingWidgetState.IDLE)
        assertEquals("Nothing playing", idle.findViewById<TextView>(R.id.widget_title).text.toString())
        assertEquals("Play", idle.findViewById<ImageButton>(R.id.widget_toggle).contentDescription)
        assertFalse(idle.findViewById<ImageButton>(R.id.widget_distill).isEnabled)

        for (action in listOf(NowPlayingWidget.ACTION_TOGGLE, NowPlayingWidget.ACTION_DISTILL)) {
            val intent = shadowOf(NowPlayingWidget.actionIntent(context, action))
            assertTrue(intent.isBroadcast)
            assertEquals(ComponentName(context, NowPlayingWidgetActions::class.java), intent.savedIntent.component)
            assertEquals(action, intent.savedIntent.action)
            assertTrue(intent.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        }
        for (player in listOf(true, false)) {
            val open = shadowOf(NowPlayingWidget.openIntent(context, player))
            assertTrue(open.isActivity)
            assertEquals(ComponentName(context, MainActivity::class.java), open.savedIntent.component)
            assertEquals(player, open.savedIntent.getBooleanExtra("openPlayer", false))
            assertTrue(open.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        }
    }

    @Test fun `action receiver is private while the widget provider declares no periodic updates`() {
        val pm = context.packageManager
        assertFalse(pm.getReceiverInfo(ComponentName(context, NowPlayingWidgetActions::class.java), 0).exported)
        val provider = pm.getReceiverInfo(ComponentName(context, NowPlayingWidgetProvider::class.java), android.content.pm.PackageManager.GET_META_DATA)
        assertEquals(R.xml.now_playing_widget_info, provider.metaData.getInt(AppWidgetManager.META_DATA_APPWIDGET_PROVIDER))
        val info = context.resources.getXml(R.xml.now_playing_widget_info)
        while (info.next() != org.xmlpull.v1.XmlPullParser.START_TAG) Unit
        val ns = "http://schemas.android.com/apk/res/android"
        assertEquals(0, info.getAttributeIntValue(ns, "updatePeriodMillis", -1))
        // Home screen only: titles are never offered as a keyguard widget.
        assertEquals(android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, info.getAttributeIntValue(ns, "widgetCategory", -1))
    }

    @Test fun `placing the widget without a live session draws idle and publishing reaches placed widgets`() {
        val manager = AppWidgetManager.getInstance(context)
        NowPlayingWidget.publish(context, NowPlayingWidgetState("Ignored", "Show", true, true)) // nothing placed: no-op
        val id = shadowOf(manager).createWidget(NowPlayingWidgetProvider::class.java, R.layout.widget_now_playing)
        NowPlayingWidgetProvider().onUpdate(context, manager, intArrayOf(id))
        assertEquals("Nothing playing", shadowOf(manager).getViewFor(id).findViewById<TextView>(R.id.widget_title).text.toString())
        NowPlayingWidget.live = NowPlayingWidgetState("Live episode", "Show", playing = true, captureAvailable = true)
        NowPlayingWidgetProvider().onUpdate(context, manager, intArrayOf(id))
        assertEquals("Live episode", shadowOf(manager).getViewFor(id).findViewById<TextView>(R.id.widget_title).text.toString())
        NowPlayingWidget.publish(context, NowPlayingWidgetState("Next episode", "Show", false, false))
        assertEquals("Next episode", shadowOf(manager).getViewFor(id).findViewById<TextView>(R.id.widget_title).text.toString())
    }

    @Test fun `taps act on live state with the same capture preconditions`() {
        assertEquals(WidgetAction.TogglePlayback, widgetAction(NowPlayingWidget.ACTION_TOGGLE, false, 0))
        assertEquals(WidgetAction.Explain("Play an episode first"), widgetAction(NowPlayingWidget.ACTION_DISTILL, false, 30_000))
        assertTrue(widgetAction(NowPlayingWidget.ACTION_DISTILL, true, 999) is WidgetAction.Explain)
        assertEquals(WidgetAction.SaveMoment, widgetAction(NowPlayingWidget.ACTION_DISTILL, true, 1_000))
        assertNull(widgetAction("other", true, 30_000))
        assertEquals("Moment saved", captureResultMessage(SessionResult.RESULT_SUCCESS))
        assertEquals("Already saving a moment", captureResultMessage(SessionError.INFO_CANCELLED))
        assertEquals("Play an episode first", captureResultMessage(SessionError.ERROR_INVALID_STATE))
        assertEquals("Couldn't save moment. Try again.", captureResultMessage(SessionError.ERROR_IO))
    }

    @Test fun `widget captures use the session distill command and are attributed to the phone only from this app`() {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sources = mutableListOf<Pair<Long, String>>()
        val callback = AutoLibraryCallback(context.packageName, db, CompletableDeferred(Unit), scope,
            saveMomentWithSource = { _, position, _, source -> sources += position to source; "moment-${sources.size}" })
        val player = ExoPlayer.Builder(context).build()
        val session = MediaLibrarySession.Builder(context, player, callback).build()
        try {
            player.setMediaItem(MediaItem.Builder().setMediaId("e").setUri("https://example.test/audio").build())
            player.seekTo(42_000)
            val own = MediaSession.ControllerInfo.createTestOnlyControllerInfo(context.packageName, 1, android.os.Process.myUid(), 1, 1, true, Bundle.EMPTY)
            val car = MediaSession.ControllerInfo.createTestOnlyControllerInfo("android.auto.test", 1, 1000, 1, 1, true, Bundle.EMPTY)
            val spoofed = MediaSession.ControllerInfo.createTestOnlyControllerInfo(context.packageName, 1, android.os.Process.myUid() + 1, 1, 1, true, Bundle.EMPTY)
            for (controller in listOf(own, car, spoofed)) {
                val result = await(callback.onCustomCommand(session, controller, PlaybackControls.distill, widgetCaptureArgs))
                assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            }
            assertEquals(listOf(42_000L to "phone", 42_000L to "car", 42_000L to "car"), sources)
            player.seekTo(0)
            assertEquals(SessionError.ERROR_INVALID_STATE, await(callback.onCustomCommand(session, own, PlaybackControls.distill, widgetCaptureArgs)).resultCode)
        } finally { session.release(); player.release(); scope.cancel(); db.close() }
    }

    private fun <T> await(future: ListenableFuture<T>): T {
        val deadline = System.currentTimeMillis() + 5_000
        while (!future.isDone && System.currentTimeMillis() < deadline) { idle(); Thread.sleep(5) }
        return future.get()
    }
}
