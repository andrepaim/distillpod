package dev.distillpod.android

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.auto.AutoCatalog
import dev.distillpod.android.auto.AutoLibraryCallback
import dev.distillpod.android.auto.LibraryChangeThrottle
import dev.distillpod.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * #277: Android Auto reloads and rebuilds a whole list for every children-changed notification, including while
 * it is being scrolled. Lists are announced only when their rows change, and bursts of changes are coalesced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AutoBrowseChangesTest {
    @Test fun `only subscribed lists whose rows changed are re-sent to the host`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val show = PodcastEntity("p", "https://example.test/feed", "Science", imageUrl = "https://example.test/p.png")
        runBlocking {
            db.library().putPodcast(show)
            db.library().putEpisode(EpisodeEntity("e", "p", "e", "Episode", "https://example.test/audio", positionMs = 1000))
            db.library().insertQueue(listOf(QueueEntryEntity("e", 0)))
        }
        // The artwork cache hands out a placeholder until a cover is downloaded.
        var cached = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val callback = AutoLibraryCallback(context.packageName, db, CompletableDeferred(Unit), scope,
            artwork = { url -> Uri.parse("content://art/" + if (cached && url != null) "cover" else "placeholder") })
        val player = ExoPlayer.Builder(context).build()
        val session = MediaLibrarySession.Builder(context, player, callback).build()
        val host = MediaSession.ControllerInfo.createTestOnlyControllerInfo("android.auto.test", 1, 1000, 1, 1, true, Bundle.EMPTY)
        fun browse(parent: String, pageSize: Int = Int.MAX_VALUE) {
            callback.onSubscribe(session, host, parent, null).get(5, TimeUnit.SECONDS)
            callback.onGetChildren(session, host, parent, 0, pageSize, null).get(5, TimeUnit.SECONDS)
        }
        fun changed(): List<String> = mutableListOf<String>().also { notified ->
            runBlocking { callback.libraryChanged { parent, _ -> notified += parent } }
        }
        try {
            for (parent in listOf(AutoCatalog.ROOT, AutoCatalog.PODCASTS, AutoCatalog.QUEUE)) browse(parent)
            assertEquals("Lists the host just loaded are current", emptyList<String>(), changed())

            runBlocking {
                db.library().markPodcastRefreshed("p", 1_000)
                db.library().setPodcastError("p", "offline")
                db.library().progress("e", 5_000, 60_000, false, 120_000)
            }
            assertEquals("Refresh bookkeeping and progress do not change any row", emptyList<String>(), changed())

            cached = true
            assertEquals("A downloaded cover reloads the lists showing it", listOf(AutoCatalog.PODCASTS, AutoCatalog.QUEUE), changed().sorted())
            assertEquals("Each change is announced once", emptyList<String>(), changed())

            runBlocking { db.library().putPodcast(db.library().podcast("p")!!.copy(title = "Science Weekly")) }
            assertEquals(listOf(AutoCatalog.PODCASTS, AutoCatalog.QUEUE), changed().sorted())

            runBlocking { db.library().clearQueue() }
            assertEquals(listOf(AutoCatalog.QUEUE), changed())

            // A paging browser received only a page, so its first change is announced conservatively.
            browse(AutoCatalog.CONTINUE, pageSize = 20)
            assertEquals(listOf(AutoCatalog.CONTINUE), changed())
            assertEquals(emptyList<String>(), changed())
        } finally { session.release(); player.release(); scope.cancel(); db.close() }
    }

    // advanceUntilIdle() does not wait for backgroundScope work, so these advance virtual time explicitly.
    @Test fun `bursts of changes publish at most once per window without losing the last change`() = runTest {
        val runs = mutableListOf<Long>()
        val throttle = LibraryChangeThrottle(backgroundScope, 1000) { runs += currentTime }
        repeat(10) { throttle.signal(); delay(90) }
        advanceTimeBy(5000); runCurrent()
        assertEquals("Ten covers or feed writes within a second reload the lists once", listOf(1000L), runs)
        throttle.signal(); advanceTimeBy(5000); runCurrent()
        assertEquals(listOf(1000L, 6900L), runs)
    }

    @Test fun `a change during a publish is published afterwards and a failure does not stop publishing`() = runTest {
        val runs = mutableListOf<Long>()
        val throttle = LibraryChangeThrottle(backgroundScope, 1000) {
            runs += currentTime
            delay(500)
            if (runs.size == 1) error("database unavailable")
        }
        throttle.signal()
        advanceTimeBy(1200)
        throttle.signal()
        advanceTimeBy(5000); runCurrent()
        assertEquals(listOf(1000L, 2500L), runs)
        throttle.signal(); advanceTimeBy(5000); runCurrent()
        assertEquals(listOf(1000L, 2500L, 7200L), runs)
    }
}
