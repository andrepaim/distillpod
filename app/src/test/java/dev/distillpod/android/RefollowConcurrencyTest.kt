package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #246: re-following a known feed wrote back the show row read before its transaction. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RefollowConcurrencyTest {
    @Test fun refollowByUrlKeepsAConcurrentEditToTheShowRow() = race { repo, url -> repo.subscribe(url) }

    @Test fun refollowFromADirectoryResultKeepsAConcurrentEditToTheShowRow() =
        race { repo, url -> repo.follow(PodcastCandidate("Directory title", "Directory author", url, null)) }

    /**
     * Another writer (a sync projection, a refresh finishing or an automation change) holds a write
     * transaction that edits the show while the re-follow runs. The re-follow reads the row as soon as
     * it is asked to; the writer commits right after that read.
     */
    private fun race(refollow: suspend (PodcastRepository, String) -> String) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "refollow-${System.nanoTime()}.db"
        val writer = arrayOfNulls<Thread>(1)
        val read = CountDownLatch(1)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCallback({ sql, _ ->
                if (Thread.currentThread() != writer[0] && sql.contains("FROM podcasts WHERE feedUrl")) read.countDown()
            }, Runnable::run)
            .build()
        try {
            MockWebServer().use { server ->
                // The re-follow's own refresh finds the feed unchanged.
                server.enqueue(MockResponse().setResponseCode(304))
                val url = server.url("/feed").toString()
                db.library().putPodcast(PodcastEntity("p", url, "Show", subscribed = false, originalFeedUrl = url))
                val repo = PodcastRepository(db, OkHttpClient())
                val began = CountDownLatch(1)
                val committed = CountDownLatch(1)
                val edit = Thread {
                    val sql = db.openHelper.writableDatabase
                    sql.beginTransaction()
                    try {
                        sql.execSQL("UPDATE podcasts SET title='Renamed elsewhere', imageUrl='https://example.test/art.png' WHERE id='p'")
                        began.countDown()
                        read.await(2, TimeUnit.SECONDS)
                        Thread.sleep(200)
                        sql.setTransactionSuccessful()
                    } finally { sql.endTransaction(); committed.countDown() }
                }.also { writer[0] = it }
                edit.start()
                assertTrue(began.await(5, TimeUnit.SECONDS))
                val id = async(Dispatchers.IO) { refollow(repo, url) }.await()
                assertTrue(committed.await(5, TimeUnit.SECONDS))
                edit.join()
                assertEquals("p", id)
                val after = db.library().podcast("p")!!
                assertTrue("The show is followed", after.subscribed)
                assertEquals("The concurrent edit was overwritten", "Renamed elsewhere", after.title)
                assertEquals("https://example.test/art.png", after.imageUrl)
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
