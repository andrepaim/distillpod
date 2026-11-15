package dev.distillpod.android

import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import dev.distillpod.android.sync.*
import dev.distillpod.core.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SubscriptionSyncStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val alice=SyncOwner("alice","g1")
    private fun podcast(id:String="p1",followed:Boolean=true)=PodcastEntity(id,"https://example.test/$id","Show",subscribed=followed)
    private fun fixture(block:suspend (AppDatabase,SubscriptionSyncStore)->Unit)=runBlocking {
        context.deleteDatabase("library.db")
        val db=AppDatabase.open(context)
        try { block(db,SubscriptionSyncStore(db)) } finally { db.close() }
    }
    @Test fun `directory follow does not contact network`()=fixture { db,store ->
        store.bind(alice)
        val http=okhttp3.OkHttpClient.Builder().addInterceptor { error("Follow must not need network") }.build()
        val id=PodcastRepository(db,http).follow(PodcastCandidate("Directory show","Author","https://example.test/feed",null))
        assertEquals("Directory show",db.library().podcast(id)!!.title);assertEquals(1,store.pendingCount())
    }
    @Test fun `directory follow queues its own feed refresh`()=fixture { db,_ ->
        val requests=mutableListOf<String>()
        val http=okhttp3.OkHttpClient.Builder().addInterceptor { error("No synchronous network") }.build()
        val id=PodcastRepository(db,http,onFeedNeeded={requests.add(it)}).follow(PodcastCandidate("Show","Author","https://example.test/feed",null))
        assertEquals(listOf(id),requests)
    }
    @Test fun `unreviewed existing feed is not uploaded`()=fixture { db,store ->
        db.library().putPodcast(podcast().copy(feedUrl="https://example.test/private-secret-path"))
        store.bind(alice);assertEquals(0,store.pendingCount())
    }
    @Test fun `manual feed stays local until approved`()=fixture { _,store ->
        store.bind(alice);store.follow(podcast());assertEquals(0,store.pendingCount())
    }
    @Test fun `signed out follow stays local`()=fixture { db,store ->store.follow(podcast(),true);assertNotNull(db.library().podcast("p1"));assertEquals(0,store.pendingCount()) }
    @Test fun `bound follow saves one durable change`()=fixture { db,store ->store.bind(alice);store.follow(podcast(),true);assertTrue(db.library().podcast("p1")!!.subscribed);assertEquals(1,store.pendingCount()) }
    @Test fun `binding seeds existing followed subscriptions`()=fixture { db,store ->db.library().putPodcast(podcast());store.bind(alice,setOf("p1"));assertEquals(1,store.pendingCount());store.bind(alice);assertEquals(1,store.pendingCount()) }
    @Test fun `other account cannot adopt retained library`()=fixture { _,store ->store.bind(alice);store.pause();try {store.bind(SyncOwner("bob","g1"));fail("Must not adopt Alice library")}catch(_:IllegalStateException){};assertNull(store.activeOwner()) }
    @Test fun `offline unfollow records tombstone without deleting episodes`()=fixture { db,store ->store.bind(alice);store.follow(podcast(),true);db.library().putEpisode(EpisodeEntity("e","p1","guid","Episode","https://example.test/audio"));store.follow(podcast(followed=false),true);assertNotNull(db.library().episode("e"));assertFalse(db.library().podcast("p1")!!.subscribed);assertEquals(1,store.pendingCount()) }
    @Test fun `unsubscribe clears only the selected show queue and listening state`()=fixture { db,_ ->
        val selected = podcast("selected")
        val other = podcast("other")
        db.library().putPodcast(selected)
        db.library().putPodcast(other)
        fun episode(id: String, show: PodcastEntity, completed: Boolean = false) =
            EpisodeEntity(id, show.id, "guid-$id", id, "https://example.test/audio/$id",
                durationMs=90_000, positionMs=20_000, completed=completed, lastPlayedAt=1234)
        val a = episode("a", selected, true).copy(localFile="/synthetic/download-a", downloadState="downloaded")
        val b = episode("b", selected)
        val c = episode("c", other, true)
        val d = episode("d", other)
        db.library().putEpisodes(listOf(a,b,c,d))
        db.library().insertQueue(listOf(c,a,b,d).mapIndexed { index, item -> QueueEntryEntity(item.id,index) })
        db.library().putDistillation(DistillationEntity("saved-a", a.id, 0, 10_000, "Synthetic saved moment"))
        val http=okhttp3.OkHttpClient.Builder().addInterceptor { error("Unsubscribe must not contact the network") }.build()

        PodcastRepository(db,http).unsubscribe(selected.id)

        assertFalse(db.library().podcast(selected.id)!!.subscribed)
        assertTrue(db.library().podcast(other.id)!!.subscribed)
        assertEquals(listOf("c","d"),db.library().queue().map { it.episodeId })
        assertEquals(a.copy(positionMs=0,completed=false,lastPlayedAt=0),db.library().episode(a.id))
        assertEquals(b.copy(positionMs=0,completed=false,lastPlayedAt=0),db.library().episode(b.id))
        assertEquals(c,db.library().episode(c.id))
        assertEquals(d,db.library().episode(d.id))
        assertNotNull(db.library().distillation("saved-a"))
    }

    @Test fun `pause keeps offline edits for same owner`()=fixture { _,store ->store.bind(alice);store.pause();store.follow(podcast(),true);assertEquals(1,store.pendingCount());assertNull(store.activeOwner());store.bind(alice);assertEquals(alice,store.activeOwner()) }
    @Test fun `private locator stays local`()=fixture { db,store ->store.bind(alice);store.follow(podcast().copy(feedUrl="https://example.test/rss?token=secret"),true);assertNotNull(db.library().podcast("p1"));assertEquals(0,store.pendingCount()) }
    @Test fun `owner and pending edits survive reopen`()=fixture { db,store ->store.bind(alice);store.follow(podcast(),true);db.close();val reopened=AppDatabase.open(context);try {assertEquals(alice,SubscriptionSyncStore(reopened).activeOwner());assertEquals(1,SubscriptionSyncStore(reopened).pendingCount())}finally{reopened.close()} }
}
