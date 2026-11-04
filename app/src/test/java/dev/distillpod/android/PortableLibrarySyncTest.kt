package dev.distillpod.android

import androidx.room.Room
import androidx.room.withTransaction
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
class PortableLibrarySyncTest {
    private fun fixture(block:suspend(AppDatabase,PortableLibrarySync,SubscriptionCoordinatorTest.Cloud)->Unit)=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(),AppDatabase::class.java).build()
        try {
            SubscriptionSyncStore(db).bind(SyncOwner("alice","g1"))
            db.library().putPodcast(PodcastEntity("p","https://example.test/feed","Show"))
            db.sync().approve(SyncPublicFeedEntity("p"))
            db.library().putEpisode(EpisodeEntity("e","p","guid","Episode","https://example.test/audio",durationMs=100000,localFile="/local/audio",downloadState="downloaded",mediaFingerprint="sha"))
            block(db,PortableLibrarySync(db),SubscriptionCoordinatorTest.Cloud())
        } finally {db.close()}
    }
    @Test fun `frequent local progress coalesces cloud checkpoints at thirty seconds`()=fixture {db,sync,_ ->
        sync.checkpoint("e",1000,100000,false,1,"sessionA",1000)
        val first=db.sync().operations("alice","g1").single().operationId
        sync.checkpoint("e",2000,100000,false,2,"sessionA",2000)
        assertEquals(2000L,db.library().episode("e")!!.positionMs)
        assertEquals(first,db.sync().operations("alice","g1").single().operationId)
        sync.checkpoint("e",31000,100000,false,3,"sessionA",31000)
        assertNotEquals(first,db.sync().operations("alice","g1").single().operationId)
        assertEquals("31000",SubscriptionSyncStore.decode(db.sync().operations("alice","g1").single().payload)["positionMs"])
    }
    @Test fun `seek boundary preserves rewind instead of largest position`()=fixture {db,sync,_ ->
        sync.checkpoint("e",80000,100000,false,1,"sessionA",1000)
        sync.checkpoint("e",10000,100000,false,2,"sessionA",2000,true)
        assertEquals("10000",SubscriptionSyncStore.decode(db.sync().operations("alice","g1").single().payload)["positionMs"])
    }
    @Test fun `restore brings minimal episode and moment without downloads or jobs`()=fixture {db,sync,cloud ->
        sync.checkpoint("e",12000,100000,false,1,"sessionA",1000)
        sync.putMoment(DistillationEntity("moment1","e",1000,11000,"Source words",quote="Source",insight="Saved idea",revision="r1",fingerprint="sha",state="succeeded",model="model"))
        assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
        val restored=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(),AppDatabase::class.java).build()
        try {
            SubscriptionSyncStore(restored).bind(SyncOwner("alice","g1"))
            assertTrue(SubscriptionSyncCoordinator(restored,cloud).run())
            val ep=restored.library().episode("e")!!
            assertEquals(12000L,ep.positionMs);assertNull(ep.localFile);assertEquals("none",ep.downloadState)
            val m=restored.library().distillation("moment1")!!
            assertEquals("Saved idea",m.insight);assertEquals("succeeded",m.state);assertEquals("r1",m.revision);assertEquals("model",m.model)
            assertTrue(restored.library().transcripts().isEmpty())
            assertEquals(0,restored.sync().count())
        } finally {restored.close()}
    }
    @Test fun `active local session keeps its position and diverging remote candidate`()=fixture {db,sync,cloud ->
        sync.checkpoint("e",70000,100000,false,1,"sessionA",1000)
        assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
        val key="progress_e";val remote=cloud.rows[key]!!
        cloud.commit(SyncOutboxEntity("other-session","alice","g1",key,1,org.json.JSONObject(remote.payload+mapOf("positionMs" to "90000","sessionId" to "sessionB")).toString(),false))
        assertFalse(SubscriptionSyncCoordinator(db,cloud).run())
        assertEquals(70000L,db.library().episode("e")!!.positionMs)
        sync.checkpoint("e",10000,100000,false,2,"sessionA",2000,true)
        assertFalse(SubscriptionSyncCoordinator(db,cloud).run())
        assertEquals("conflict",db.sync().operations("alice","g1").single().state)
        assertEquals(10000L,db.library().episode("e")!!.positionMs)
        sync.resolve(key,false)
        assertEquals(90000L,db.library().episode("e")!!.positionMs)
        assertEquals("/local/audio",db.library().episode("e")!!.localFile)
    }
    @Test fun `cloud pulled under pending moment edit does not become its causal base`()=fixture {db,sync,cloud ->
        val moment=DistillationEntity("m","e",0,10000,"Original",state="saved")
        sync.putMoment(moment);SubscriptionSyncCoordinator(db,cloud).run()
        sync.putMoment(moment.copy(excerpt="Offline A"))
        val remote=cloud.rows["moment_m"]!!
        cloud.commit(SyncOutboxEntity("other-edit","alice","g1","moment_m",1,org.json.JSONObject(remote.payload+mapOf("excerpt" to "Other B")).toString(),false))
        SubscriptionSyncCoordinator(db,cloud).run()
        sync.putMoment(moment.copy(excerpt="Offline A revised"))
        assertTrue(db.sync().forKey("alice","g1","moment_m").all {it.baseVersion==1L})
        assertFalse(SubscriptionSyncCoordinator(db,cloud).run())
        sync.resolve("moment_m",true)
        assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
        assertEquals("Offline A revised",cloud.rows["moment_m"]!!.payload["excerpt"])
    }
    @Test fun `deleted moment cannot be recreated by stale AI completion`()=fixture {db,sync,cloud ->
        val moment=DistillationEntity("m","e",0,10000,"Original",state="running",attempt="a1")
        sync.putMoment(moment);SubscriptionSyncCoordinator(db,cloud).run()
        sync.deleteMoment("m");SubscriptionSyncCoordinator(db,cloud).run()
        assertNull(db.library().distillation("m"));assertTrue(cloud.rows["moment_m"]!!.deleted)
    }
    @Test fun `private media locator never uploads even on approved feed`()=fixture {db,sync,_ ->
        db.library().putEpisode(db.library().episode("e")!!.copy(audioUrl="https://example.test/audio?token=secret"))
        sync.checkpoint("e",1000,100000,false,1,"sessionA",1000)
        sync.putMoment(DistillationEntity("m","e",0,10000))
        assertEquals(1000L,db.library().episode("e")!!.positionMs);assertEquals(0,db.sync().count())
    }
    @Test fun `explicit unplayed is not overwritten by stale automatic completion`()=fixture {db,sync,cloud ->
        sync.checkpoint("e",1000,100000,false,1,"sessionA",1000)
        SubscriptionSyncCoordinator(db,cloud).run()
        sync.markCompleted("e",false);SubscriptionSyncCoordinator(db,cloud).run()
        sync.checkpoint("e",100000,100000,true,2,"sessionA",2000,true)
        assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
        assertFalse(db.library().episode("e")!!.completed)
        assertEquals("false",cloud.rows["completion_e"]!!.payload["completed"])
    }
    @Test fun `checkpoint never replaces an operation awaiting server acknowledgement`()=fixture {db,sync,cloud ->
        sync.checkpoint("e",1000,100000,false,1,"sessionA",1000)
        cloud.afterCommit={sync.checkpoint("e",2000,100000,false,2,"sessionA",2000,true);cloud.afterCommit=null}
        assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
        assertEquals(2,cloud.log.size);assertEquals("2000",cloud.rows["progress_e"]!!.payload["positionMs"])
    }
    @Test fun `failed outer transaction rolls back moment and its pending intent`()=fixture {db,sync,_ ->
        try {db.withTransaction {sync.putMoment(DistillationEntity("m","e",0,1000));error("Interrupted before commit")}}catch(_:IllegalStateException){}
        assertNull(db.library().distillation("m"));assertEquals(0,db.sync().count())
    }
    @Test fun `paused handoff accepts a newer cloud position without manufacturing a conflict`()=fixture {db,sync,cloud ->
        sync.checkpoint("e",70000,100000,false,1,"sessionA",1000,true,active=false)
        assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
        val remote=cloud.rows["progress_e"]!!
        cloud.commit(SyncOutboxEntity("other-session","alice","g1","progress_e",1,org.json.JSONObject(remote.payload+mapOf("positionMs" to "90000","sessionId" to "sessionB")).toString(),false))
        assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
        assertEquals(90000L,db.library().episode("e")!!.positionMs)
        assertEquals(0,db.sync().count())
    }
    @Test fun `stopping playback flushes the last position and releases active handoff protection`()=fixture {db,sync,cloud ->
        sync.checkpoint("e",1000,100000,false,1,"sessionA",1000)
        SubscriptionSyncCoordinator(db,cloud).run()
        sync.checkpoint("e",2000,100000,false,2,"sessionA",2000)
        assertEquals(0,db.sync().count())
        sync.finishPlayback("e",2000,100000,2,"sessionA",2000)
        assertEquals("2000",SubscriptionSyncStore.decode(db.sync().operations("alice","g1").single().payload)["positionMs"])
        assertFalse(db.sync().view("progress_e")!!.active)
        assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
    }
    @Test fun `ordinary instrumentation changes remain local`()=fixture {db,_,_ ->
        SubscriptionCloudAccess.instrumentation=true
        try {
            PortableLibrarySync(db).checkpoint("e",2000,100000,false,1,"sessionA",1000)
            PortableLibrarySync(db).putMoment(DistillationEntity("m","e",0,1000))
            assertEquals(0,db.sync().count());assertNotNull(db.library().distillation("m"))
        } finally {SubscriptionCloudAccess.instrumentation=false}
    }
}
