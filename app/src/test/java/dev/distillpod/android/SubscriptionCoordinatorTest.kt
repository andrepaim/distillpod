package dev.distillpod.android

import androidx.room.Room
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SubscriptionCoordinatorTest {
    class Cloud:SubscriptionCloud {
        override var uid:String?="alice"
        val owner=SyncOwner("alice","g1")
        val rows=linkedMapOf<String,CloudSubscription>();val log=mutableListOf<CloudSubscription>();val receipts=mutableMapOf<String,CloudSubscription>()
        var cancelOnCommit=false;var loseAck=false;var afterCommit:(suspend ()->Unit)?=null;var commits=0
        override suspend fun head()=CloudHead(owner,log.size.toLong())
        override suspend fun entities(after:String)=rows.values.filter { it.key>after }.sortedBy { it.key }.take(100)
        override suspend fun changes(after:Long,through:Long)=log.filter { it.sequence>after&&it.sequence<=through }.take(100)
        override suspend fun commit(operation:SyncOutboxEntity):CloudSubscription {
            commits++
            receipts[operation.operationId]?.let { return it }
            val old=rows[operation.entityKey]
            if((old?.version?:0)!=operation.baseVersion)throw SubscriptionConflict(old)
            val r=CloudSubscription(operation.entityKey,operation.baseVersion+1,if(operation.deleted)emptyMap() else SubscriptionSyncStore.decode(operation.payload),operation.deleted,log.size+1L)
            rows[r.key]=r;log.add(r);receipts[operation.operationId]=r;afterCommit?.invoke()
            if(cancelOnCommit){cancelOnCommit=false;throw kotlinx.coroutines.CancellationException("Work interrupted")}
            if(loseAck){loseAck=false;throw java.io.IOException("Interrupted after commit")}
            return r
        }
    }
    private fun fixture(block:suspend(AppDatabase,SubscriptionSyncStore,Cloud)->Unit)=runBlocking {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val name="sync-${UUID.randomUUID()}.db";val db=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
        val store=SubscriptionSyncStore(db)
        try{store.bind(SyncOwner("alice","g1"));block(db,store,Cloud())}finally{db.close();context.deleteDatabase(name)}
    }
    private fun podcast()=PodcastEntity("p1","https://example.test/feed","Show")
    @Test fun `follow reaches independent second local database`()=fixture { a,store,cloud ->
        store.follow(podcast(),true);assertTrue(SubscriptionSyncCoordinator(a,cloud).run())
        fixture { b,_,_ ->assertTrue(SubscriptionSyncCoordinator(b,cloud).run());assertEquals("Show",b.library().podcast("p1")!!.title) }
        assertEquals(0,store.pendingCount())
    }
    @Test fun `lost server acknowledgement retries same operation once logically`()=fixture { db,store,cloud ->
        store.follow(podcast(),true);cloud.loseAck=true
        try{SubscriptionSyncCoordinator(db,cloud).run();fail()}catch(_:java.io.IOException){}
        assertEquals(1,store.pendingCount());assertTrue(SubscriptionSyncCoordinator(db,cloud).run());assertEquals(1,cloud.log.size);assertEquals(0,store.pendingCount())
    }
    @Test fun `interruption resets visible status and preserves unacknowledged intent`()=fixture { db,store,cloud ->
        store.follow(podcast(),true);cloud.cancelOnCommit=true
        try{SubscriptionSyncCoordinator(db,cloud).run();fail()}catch(_:kotlinx.coroutines.CancellationException){}
        assertEquals("pending",db.sync().binding()!!.status);assertEquals(1,store.pendingCount())
        assertTrue(SubscriptionSyncCoordinator(db,cloud).run());assertEquals(1,cloud.log.size)
    }
    @Test fun `old receipt cannot roll back a newer remote edit already pulled`()=fixture { db,store,cloud ->
        store.follow(podcast(),true);cloud.loseAck=true
        try{SubscriptionSyncCoordinator(db,cloud).run();fail()}catch(_:java.io.IOException){}
        val key=SubscriptionSyncStore.key(podcast())
        val latest=SubscriptionSyncStore.payload(podcast().copy(title="Changed on other phone"))
        cloud.commit(SyncOutboxEntity("other-edit","alice","g1",key,1,org.json.JSONObject(latest).toString(),false))
        assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
        assertEquals("Changed on other phone",db.library().podcast("p1")!!.title)
        assertEquals(2L,db.sync().remote("alice","g1",key)!!.version)
    }
    @Test fun `different authenticated account causes no cloud writes`()=fixture { db,store,cloud ->store.follow(podcast(),true);cloud.uid="bob";assertFalse(SubscriptionSyncCoordinator(db,cloud).run());assertEquals(0,cloud.commits);assertEquals(1,store.pendingCount()) }
    @Test fun `sign out during request cannot acknowledge retained work`()=fixture { db,store,cloud ->store.follow(podcast(),true);cloud.afterCommit={store.pause()};assertFalse(SubscriptionSyncCoordinator(db,cloud).run());assertEquals(1,store.pendingCount());assertNull(store.activeOwner()) }
    @Test fun `cloud deletion conflicts with stale local edit and can be accepted`()=fixture { db,store,cloud ->
        store.follow(podcast(),true);SubscriptionSyncCoordinator(db,cloud).run()
        val key=SubscriptionSyncStore.key(podcast())
        cloud.commit(SyncOutboxEntity("external","alice","g1",key,1,"{}",true))
        store.follow(podcast().copy(title="Offline edit"),true)
        assertFalse(SubscriptionSyncCoordinator(db,cloud).run())
        assertTrue(db.library().podcast("p1")!!.subscribed)
        store.resolve(key,false)
        assertFalse(db.library().podcast("p1")!!.subscribed);assertEquals(0,store.pendingCount())
    }
    @Test fun `explicit local conflict choice references current tombstone`()=fixture { db,store,cloud ->
        store.follow(podcast(),true);SubscriptionSyncCoordinator(db,cloud).run()
        val key=SubscriptionSyncStore.key(podcast())
        cloud.commit(SyncOutboxEntity("external","alice","g1",key,1,"{}",true))
        store.follow(podcast().copy(title="Offline edit"),true);SubscriptionSyncCoordinator(db,cloud).run()
        store.resolve(key,true);assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
        assertFalse(cloud.rows[key]!!.deleted);assertEquals(3L,cloud.rows[key]!!.version)
    }
    @Test fun `delete reaches another phone without deleting local episode`()=fixture { db,store,cloud ->
        store.follow(podcast(),true);SubscriptionSyncCoordinator(db,cloud).run()
        db.library().putEpisode(EpisodeEntity("e","p1","guid","Episode","https://example.test/audio"))
        store.follow(podcast().copy(subscribed=false),true);SubscriptionSyncCoordinator(db,cloud).run()
        assertNotNull(db.library().episode("e"));assertTrue(cloud.rows.values.single().deleted)
    }
}
