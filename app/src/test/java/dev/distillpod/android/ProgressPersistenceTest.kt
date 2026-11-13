package dev.distillpod.android

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import dev.distillpod.android.playback.*
import dev.distillpod.android.sync.CollectionSync
import dev.distillpod.android.sync.PortableLibrarySync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #141: bounded local progress writes with exact boundary checkpoints. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgressPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun `continuous playback checkpoints at most once per interval`() {
        val policy = ProgressCheckpoints()
        var writes = 0
        for (second in 0..60) {
            val elapsed = second * 1_000L
            if (policy.due("e", playing = true, boundary = false, elapsed = elapsed) != null) { writes++; policy.written("e", elapsed) }
        }
        assertTrue("interval must be 10-15 s", PROGRESS_CHECKPOINT_INTERVAL_MS in 10_000L..15_000L)
        assertTrue("$writes writes in a minute", writes in 5..7)
    }

    @Test fun `boundaries and item changes are written immediately while idle snapshots are not`() {
        val policy = ProgressCheckpoints()
        assertEquals(false, policy.due("a", playing = true, boundary = false, elapsed = 0))
        policy.written("a", 0)
        assertNull(policy.due("a", playing = true, boundary = false, elapsed = 1_000))
        assertEquals(true, policy.due("a", playing = true, boundary = true, elapsed = 1_500))
        assertEquals(true, policy.due("a", playing = false, boundary = true, elapsed = 1_600))
        assertEquals(false, policy.due("b", playing = true, boundary = false, elapsed = 2_000))
        // Paused, non-boundary snapshots never write.
        assertNull(policy.due("a", playing = false, boundary = false, elapsed = 60_000))
    }

    @Test fun `a failed checkpoint is retried by the next snapshot`() {
        val policy = ProgressCheckpoints()
        policy.written("a", 0)
        policy.failed(boundary = true)
        // The pause boundary failed; the next paused snapshot carries it again as a boundary.
        assertEquals(true, policy.due("a", playing = false, boundary = false, elapsed = 1_000))
        policy.written("a", 1_000)
        assertNull(policy.due("a", playing = false, boundary = false, elapsed = 2_000))
        policy.failed(boundary = false)
        assertEquals(false, policy.due("a", playing = true, boundary = false, elapsed = 3_000))
    }

    private class Fixture(val db: AppDatabase) {
        val failNext = MutableStateFlow(false)
        val writer = PlaybackSnapshotWriter(db, { id, s ->
            if (failNext.value) { failNext.value = false; error("disk full") }
            PortableLibrarySync(db).checkpoint(id, s.position, s.duration, s.complete, s.at, "session", s.elapsed, s.boundary, active = s.playing)
        }, CollectionSync(db)::queue, MutableStateFlow<List<String>?>(null))
        fun count(table: String): Long = db.openHelper.readableDatabase.query("SELECT n FROM writes WHERE name='$table'").use { it.moveToFirst(); it.getLong(0) }
        fun reset() = db.openHelper.writableDatabase.execSQL("UPDATE writes SET n=0")
    }

    private fun open(name: String) = Room.databaseBuilder(context, AppDatabase::class.java, name).build()

    private fun fixture(name: String, block: suspend Fixture.() -> Unit) = runBlocking {
        context.deleteDatabase(name)
        val db = open(name)
        try {
            db.library().putPodcast(PodcastEntity("p", "https://example.test/feed", "Show"))
            db.library().putEpisode(EpisodeEntity("a", "p", "a", "A", "https://example.test/a", durationMs = 600_000))
            db.library().putEpisode(EpisodeEntity("b", "p", "b", "B", "https://example.test/b", durationMs = 600_000))
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TABLE writes(name TEXT PRIMARY KEY NOT NULL, n INTEGER NOT NULL)")
            for (table in listOf("episodes", "player_session")) {
                sql.execSQL("INSERT INTO writes VALUES('$table', 0)")
                for (op in listOf("INSERT", "UPDATE")) sql.execSQL(
                    "CREATE TRIGGER count_${table}_$op AFTER $op ON $table BEGIN UPDATE writes SET n=n+1 WHERE name='$table'; END")
            }
            Fixture(db).block()
        } finally { db.close() }
    }

    private fun playing(id: String, second: Long, ids: List<String> = listOf("a", "b"), speed: Float = 1f, boundary: Boolean = false, playing: Boolean = true) =
        PlaybackSnapshot(ids, id, second * 1_000, 600_000, false, speed, at = 1_000_000 + second * 1_000, boundary = boundary,
            playing = playing, elapsed = second * 1_000)

    @Test fun `a minute of playback writes episodes and the session a bounded number of times`() = fixture("bounded.db") {
        reset()
        for (second in 0L..60L) writer.write(playing("a", second))
        val episodeWrites = count("episodes")
        assertTrue("$episodeWrites episode writes per minute", episodeWrites in 5..7)
        assertEquals("unchanged session is written once", 1L, count("player_session"))
        assertEquals(60_000L, db.library().episode("a")!!.positionMs)
    }

    @Test fun `paused service does not rewrite an unchanged session`() = fixture("paused.db") {
        writer.write(playing("a", 0))
        writer.write(playing("a", 1, boundary = true, playing = false))
        reset()
        for (second in 2L..62L) writer.write(playing("a", 1, playing = false).copy(elapsed = second * 1_000))
        assertEquals(0L, count("player_session"))
        assertEquals(0L, count("episodes"))
        writer.write(playing("a", 1, speed = 1.5f, playing = false).copy(elapsed = 63_000))
        assertEquals(1L, count("player_session"))
        assertEquals(1.5f, db.library().playerSession()!!.speed)
    }

    @Test fun `pause boundary survives process death exactly`() {
        val name = "death.db"
        fixture(name) {
            for (second in 0L..24L) writer.write(playing("a", second))
            writer.write(playing("a", 24).copy(position = 24_700, boundary = true, playing = false))
        }
        // The process dies: nothing but Room's file remains.
        val reopened = open(name)
        try { runBlocking { assertEquals(24_700L, reopened.library().episode("a")!!.positionMs) } }
        finally { reopened.close() }
    }

    @Test fun `process death while playing loses at most one interval`() {
        val name = "playing-death.db"
        fixture(name) { for (second in 0L..37L) writer.write(playing("a", second)) }
        val reopened = open(name)
        try { runBlocking {
            val saved = reopened.library().episode("a")!!.positionMs
            assertTrue("saved $saved", 37_000 - saved <= PROGRESS_CHECKPOINT_INTERVAL_MS)
        } } finally { reopened.close() }
    }

    @Test fun `manual transition saves the departed item at its last position`() = fixture("manual.db") {
        for (second in 0L..13L) writer.write(playing("a", second))
        // Seek to the next item: the service sends the old item's departure boundary, then the new item.
        writer.write(PlaybackSnapshot(emptyList(), "a", 13_400, 600_000, false, 1f, boundary = true, playing = true, elapsed = 13_400))
        writer.write(playing("b", 14).copy(position = 0))
        assertEquals(13_400L, db.library().episode("a")!!.positionMs)
        assertEquals("b", db.library().playerSession()!!.episodeId)
        assertTrue(db.library().episode("b")!!.lastPlayedAt > 0)
    }

    @Test fun `failed boundary write is recovered by the next snapshot`() = fixture("failure.db") {
        writer.write(playing("a", 0))
        failNext.value = true
        writer.write(playing("a", 5).copy(position = 5_300, boundary = true, playing = false))
        assertEquals(0L, db.library().episode("a")!!.positionMs)
        writer.write(playing("a", 6, playing = false).copy(position = 5_300))
        assertEquals(5_300L, db.library().episode("a")!!.positionMs)
    }
}
