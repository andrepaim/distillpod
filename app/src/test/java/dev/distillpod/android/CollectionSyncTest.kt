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
class CollectionSyncTest {
    private fun fixture(block:suspend(AppDatabase,CollectionSync,SubscriptionCoordinatorTest.Cloud)->Unit)=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(),AppDatabase::class.java).build()
        try {
            SubscriptionSyncStore(db).bind(SyncOwner("alice","g1"))
            db.sync().putView(SyncViewEntity("__extended_scope",version=1))
            db.library().putPodcast(PodcastEntity("p","https://example.test/feed","Show"));db.sync().approve(SyncPublicFeedEntity("p"))
            for(id in listOf("e1","e2"))db.library().putEpisode(EpisodeEntity(id,"p",id,"Episode","https://example.test/$id"))
            block(db,CollectionSync(db),SubscriptionCoordinatorTest.Cloud())
        } finally {db.close()}
    }
    @Test fun `queue restore includes episode identities and deterministic order`()=fixture {db,sync,cloud ->
        sync.queue(listOf("e2","e1","e2"));assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
        val restored=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(),AppDatabase::class.java).build()
        try {
            SubscriptionSyncStore(restored).bind(SyncOwner("alice","g1"));assertTrue(SubscriptionSyncCoordinator(restored,cloud).run())
            assertEquals(listOf("e2","e1"),restored.library().queue().map {it.episodeId})
            assertNotNull(restored.library().episode("e1"));assertNull(restored.library().playerSession())
        } finally {restored.close()}
    }
    @Test fun `offline queue edits remain one unsent order plus identity records`()=fixture {db,sync,_ ->
        sync.queue(listOf("e1","e2"));sync.queue(listOf("e2","e1"))
        assertEquals(1,db.sync().forKey("alice","g1","queue_library").size)
        assertEquals("e2,e1",SubscriptionSyncStore.decode(db.sync().forKey("alice","g1","queue_library").single().payload)["items"])
    }
    @Test fun `independent preferences upload as separate revisions`()=fixture {db,sync,cloud ->
        sync.preference("model","base");sync.preference("language","pt")
        assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
        assertEquals(setOf("preference_model","preference_language"),cloud.rows.keys)
    }
    @Test fun `concurrent order is explicit and cloud choice preserves player session`()=fixture {db,sync,cloud ->
        sync.queue(listOf("e1","e2"));assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
        db.library().putPlayerSession(PlayerSessionEntity(episodeId="e1",speed=1.5f))
        cloud.commit(SyncOutboxEntity("other-order","alice","g1","queue_library",1,"{\"items\":\"e2,e1\"}",false))
        assertFalse(SubscriptionSyncCoordinator(db,cloud).run())
        assertEquals(listOf("e1","e2"),db.library().queue().map {it.episodeId})
        PortableLibrarySync(db).resolve("queue_library",false)
        assertEquals(listOf("e2","e1"),db.library().queue().map {it.episodeId})
        assertEquals("e1",db.library().playerSession()!!.episodeId)
        assertEquals(1.5f,db.library().playerSession()!!.speed)
    }
    @Test fun `private entries keep their place and own acknowledgement advances public queue base`()=fixture {db,sync,cloud ->
        db.library().putEpisode(db.library().episode("e1")!!.copy(audioUrl="https://example.test/private?token=secret"))
        sync.queue(listOf("e1","e2"));assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
        assertEquals(listOf("e1","e2"),db.library().queue().map {it.episodeId})
        sync.queue(listOf("e1"));assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
        assertEquals("",cloud.rows["queue_library"]!!.payload["items"])
    }
    @Test fun `private queue episode remains local and its locator never enters outbox`()=fixture {db,sync,_ ->
        db.library().putEpisode(db.library().episode("e1")!!.copy(audioUrl="https://example.test/private?token=secret"))
        sync.queue(listOf("e1","e2"))
        assertEquals(listOf("e1","e2"),db.library().queue().map {it.episodeId})
        assertTrue(db.sync().operations("alice","g1").none {it.payload.contains("secret")})
        assertEquals("e2",SubscriptionSyncStore.decode(db.sync().forKey("alice","g1","queue_library").single().payload)["items"])
    }
    @Test fun `a query-bearing queue episode keeps its local place while its progress syncs`()=fixture {db,sync,cloud ->
        db.library().putEpisode(db.library().episode("e1")!!.copy(audioUrl="https://example.test/private?token=secret"))
        sync.queue(listOf("e1","e2"));PortableLibrarySync(db).markCompleted("e1",true)
        assertTrue(db.sync().operations("alice","g1").none {it.payload.contains("secret")})
        assertEquals(setOf("episode_e2","queue_library","completion_e1"),db.sync().operations("alice","g1").map {it.entityKey}.toSet())
        assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
        // Another phone's queue order arrives; the entry the cloud queue cannot carry keeps its place here.
        cloud.commit(SyncOutboxEntity("other-order","alice","g1","queue_library",cloud.rows["queue_library"]!!.version,"{\"items\":\"e2\"}",false))
        assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
        assertEquals(setOf("e1","e2"),db.library().queue().map {it.episodeId}.toSet())
        db.sync().releasePlaybackViews()
        cloud.commit(SyncOutboxEntity("other-again","alice","g1","queue_library",cloud.rows["queue_library"]!!.version,"{\"items\":\"e2\"}",false))
        assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
        assertEquals(listOf("e2","e1"),db.library().queue().map {it.episodeId})
    }
}
