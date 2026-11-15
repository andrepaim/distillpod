package dev.distillpod.android

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import dev.distillpod.android.sync.*
import dev.distillpod.core.sync.SyncOwner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class UnsubscribeSyncTest {
    @Test fun `stale cloud records cannot restore queue and history cleared by unsubscribe`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(), AppDatabase::class.java).build()
        try {
            val dao = db.library()
            val show = PodcastEntity("selected", "https://example.test/show", "Selected")
            val other = PodcastEntity("other", "https://example.test/other", "Other")
            dao.putPodcast(show)
            dao.putPodcast(other)
            val store = SubscriptionSyncStore(db)
            store.bind(SyncOwner("alice", "g1"), setOf(show.id,other.id))
            db.sync().putView(SyncViewEntity("__extended_scope"))
            val removed = EpisodeEntity("cut", show.id, "cut-guid", "Cut", "https://example.test/cut",
                durationMs=100_000, positionMs=70_000, completed=true, lastPlayedAt=123)
            val kept = EpisodeEntity("keep", other.id, "keep-guid", "Keep", "https://example.test/keep", positionMs=8_000)
            dao.putEpisodes(listOf(removed,kept))
            val sync = PortableLibrarySync(db)
            val identity = sync.identity(removed)!!
            val staleProgress = identity + mapOf("positionMs" to "70000", "sessionId" to "old-session",
                "deviceId" to "old-phone", "deviceSequence" to "1")
            val staleCompletion = identity + mapOf("completed" to "true", "origin" to "automatic", "sessionId" to "old-session")
            CollectionSync(db).queue(listOf(removed.id, kept.id))
            val http = okhttp3.OkHttpClient.Builder().addInterceptor { error("No network") }.build()

            PodcastRepository(db,http).unsubscribe(show.id)
            val pending = db.sync().operations("alice","g1").associateBy { it.entityKey }
            assertEquals("0",pending["progress_cut"]?.let { SubscriptionSyncStore.decode(it.payload)["positionMs"] })
            assertEquals("false",pending["completion_cut"]?.let { SubscriptionSyncStore.decode(it.payload)["completed"] })
            projectPortable(db, CloudSubscription("progress_cut",1,staleProgress,false,1))
            projectPortable(db, CloudSubscription("completion_cut",1,staleCompletion,false,2))
            projectCollection(db, CloudSubscription("queue_library",1,mapOf("items" to "cut,keep"),false,3), force=true)

            assertEquals(removed.copy(positionMs=0,completed=false,lastPlayedAt=0),dao.episode("cut"))
            assertEquals(kept,dao.episode("keep"))
            assertEquals(listOf("keep"),dao.queue().map { it.episodeId })
        } finally { db.close() }
    }
}
