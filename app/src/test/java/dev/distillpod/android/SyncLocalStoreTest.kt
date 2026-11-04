package dev.distillpod.android

import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import dev.distillpod.android.sync.SyncLocalStore
import dev.distillpod.core.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SyncLocalStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private suspend fun withDb(block: suspend (AppDatabase) -> Unit) {
        val db = AppDatabase.open(context)
        try { block(db) } finally { db.close() }
    }
    private fun mutation(id: String="op1", uid: String="alice") = SyncMutation(id, SyncOwner(uid,"g1"),"show1",0,mapOf("subscribed" to "true"))
    @Test fun `library write and pending change survive reopen`() = runBlocking {
        context.deleteDatabase("library.db")
        withDb { db ->
            SyncLocalStore(db).record(mutation()) { db.library().putPodcast(PodcastEntity("show1","https://example.test/rss","Show")) }
        }
        withDb { db ->
            assertNotNull(db.library().podcast("show1"))
            assertEquals(listOf("op1"),SyncLocalStore(db).pending("alice","g1"))
        }
    }
    @Test fun `failed local mutation leaves no intent or library change`() = runBlocking {
        context.deleteDatabase("library.db")
        withDb { db ->
            val store=SyncLocalStore(db)
            try { store.record(mutation()) { db.library().putPodcast(PodcastEntity("show1","https://example.test/rss","Show"));error("disk failure") };fail() } catch (_: IllegalStateException) { }
            assertNull(db.library().podcast("show1"));assertTrue(store.pending("alice","g1").isEmpty())
        }
    }
    @Test fun `other account cannot acknowledge pending change`() = runBlocking {
        context.deleteDatabase("library.db")
        withDb { db ->
            val store=SyncLocalStore(db);store.record(mutation()) {}
            store.acknowledge("bob","g1","op1");store.acknowledge("alice","oldGeneration","op1")
            assertEquals(listOf("op1"),store.pending("alice","g1"))
            store.acknowledge("alice","g1","op1");assertTrue(store.pending("alice","g1").isEmpty())
        }
    }
    @Test fun `ack cannot erase later edit`() = runBlocking {
        context.deleteDatabase("library.db")
        withDb { db ->
            val store=SyncLocalStore(db);store.record(mutation()) {};store.record(mutation("op2")) {}
            store.acknowledge("alice","g1","op1")
            assertEquals(listOf("op2"),store.pending("alice","g1"))
        }
    }
    @Test fun `duplicate intent rejects and rolls back second library write`() = runBlocking {
        context.deleteDatabase("library.db")
        withDb { db ->
            val store=SyncLocalStore(db);store.record(mutation()) {}
            try { store.record(mutation()) {db.library().putPodcast(PodcastEntity("show1","https://example.test/rss","Show"))};fail() } catch (_: android.database.sqlite.SQLiteConstraintException) { }
            assertNull(db.library().podcast("show1"))
            assertEquals(listOf("op1"),store.pending("alice","g1"))
        }
    }
}
