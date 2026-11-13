package dev.distillpod.android

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.firestore.FirebaseFirestoreException
import dev.distillpod.android.data.*
import dev.distillpod.android.sync.*
import dev.distillpod.core.sync.SyncOwner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SubscriptionRejectedOperationTest {
    /** Fake transport whose cloud refuses selected records, as rules drift would. */
    class Rejecting(val inner:SubscriptionCoordinatorTest.Cloud):SubscriptionCloud {
        val rejectedKeys=mutableSetOf<String>();val attempts=mutableListOf<String>()
        var failure:Exception?=null;var beforeRejection:(suspend ()->Unit)?=null
        override val uid get()=inner.uid
        override suspend fun head()=inner.head()
        override suspend fun entities(after:String)=inner.entities(after)
        override suspend fun changes(after:Long,through:Long)=inner.changes(after,through)
        override suspend fun commit(operation:SyncOutboxEntity):CloudSubscription {
            attempts+=operation.entityKey
            failure?.let { throw it }
            if(operation.entityKey in rejectedKeys) {
                beforeRejection?.invoke()
                throw SyncOperationRejected(FirebaseFirestoreException("synthetic",FirebaseFirestoreException.Code.PERMISSION_DENIED))
            }
            return inner.commit(operation)
        }
    }
    private val first=PodcastEntity("p1","https://example.test/first","First",subscribed=true)
    private val second=PodcastEntity("p2","https://example.test/second","Second",subscribed=true)
    private val firstKey=SubscriptionSyncStore.key(first)
    private fun fixture(block:suspend(AppDatabase,SubscriptionSyncStore,Rejecting)->Unit)=runBlocking {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val name="rejected-${UUID.randomUUID()}.db";val db=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
        val store=SubscriptionSyncStore(db)
        try{store.bind(SyncOwner("alice","g1"));block(db,store,Rejecting(SubscriptionCoordinatorTest.Cloud()))}finally{db.close();context.deleteDatabase(name)}
    }
    private suspend fun rejectFirst(db:AppDatabase,store:SubscriptionSyncStore,cloud:Rejecting) {
        store.follow(first,true);store.follow(second,true);cloud.rejectedKeys+=firstKey
        assertFalse(SubscriptionSyncCoordinator(db,cloud).run())
    }
    private suspend fun states(db:AppDatabase)=db.sync().operations("alice","g1").map { it.entityKey to it.state }

    @Test fun `rejected operation is retained visibly while later outbox work continues`()=fixture { db,store,cloud ->
        rejectFirst(db,store,cloud)
        assertEquals(listOf(firstKey to "rejected"),states(db))
        assertNotNull(cloud.inner.rows[SubscriptionSyncStore.key(second)]);assertNull(cloud.inner.rows[firstKey])
        assertEquals("rejected",db.sync().binding()!!.status)
        assertTrue(db.library().podcast("p1")!!.subscribed)
        assertNull(db.sync().nextOperation("alice","g1"))
        // A later run neither resends the refused change nor reports a sync failure.
        val attempts=cloud.attempts.size
        store.follow(PodcastEntity("p3","https://example.test/third","Third",subscribed=true),true)
        assertFalse(SubscriptionSyncCoordinator(db,cloud).run())
        assertEquals(attempts+1,cloud.attempts.size);assertEquals(1,cloud.attempts.count { it==firstKey })
        assertEquals(2,cloud.inner.rows.size);assertEquals("rejected",db.sync().binding()!!.status)
        assertEquals(listOf(firstKey to "rejected"),states(db))
    }
    @Test fun `retry requeues rejected changes for another attempt`()=fixture { db,store,cloud ->
        rejectFirst(db,store,cloud)
        cloud.rejectedKeys.clear();store.retryRejected()
        assertEquals(listOf(firstKey to "queued"),states(db));assertEquals("pending",db.sync().binding()!!.status)
        assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
        assertNotNull(cloud.inner.rows[firstKey]);assertEquals(0,store.pendingCount());assertEquals("synced",db.sync().binding()!!.status)
    }
    @Test fun `repeated rejection after retry returns to the visible rejected state`()=fixture { db,store,cloud ->
        rejectFirst(db,store,cloud);store.retryRejected()
        assertFalse(SubscriptionSyncCoordinator(db,cloud).run())
        assertEquals(listOf(firstKey to "rejected"),states(db));assertEquals("rejected",db.sync().binding()!!.status)
    }
    @Test fun `discard keeps the local library and clears the rejected status`()=fixture { db,store,cloud ->
        rejectFirst(db,store,cloud)
        store.discardRejected()
        assertEquals(0,store.pendingCount());assertTrue(db.library().podcast("p1")!!.subscribed)
        assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
        assertNull(cloud.inner.rows[firstKey]);assertEquals("synced",db.sync().binding()!!.status)
    }
    @Test fun `new local intent supersedes a rejected subscription change`()=fixture { db,store,cloud ->
        rejectFirst(db,store,cloud)
        store.follow(first.copy(title="Renamed"),true)
        assertEquals(listOf(firstKey to "queued"),states(db))
        cloud.rejectedKeys.clear();assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
        assertEquals("Renamed",cloud.inner.rows[firstKey]!!.payload["title"])
    }
    @Test fun `new portable intent supersedes a rejected portable change`()=fixture { db,store,cloud ->
        store.follow(first,true);assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
        db.library().putEpisode(EpisodeEntity("e","p1","guid","Episode","https://example.test/audio",durationMs=1000,mediaFingerprint="sha"))
        val portable=PortableLibrarySync(db)
        portable.markCompleted("e",true);cloud.rejectedKeys+="completion_e"
        assertFalse(SubscriptionSyncCoordinator(db,cloud).run())
        assertEquals(listOf("completion_e" to "rejected"),states(db))
        portable.markCompleted("e",false)
        assertEquals(listOf("completion_e" to "queued"),states(db))
    }
    @Test fun `account change while a rejection arrives leaves the operation unmarked`()=fixture { db,store,cloud ->
        store.follow(first,true);cloud.rejectedKeys+=firstKey;cloud.beforeRejection={store.pause()}
        assertFalse(SubscriptionSyncCoordinator(db,cloud).run())
        assertEquals(listOf(firstKey to "uploading"),states(db));assertEquals("paused",db.sync().binding()!!.status)
    }
    @Test fun `unclassified permission denial still propagates and keeps the operation`()=fixture { db,store,cloud ->
        store.follow(first,true)
        val denied=FirebaseFirestoreException("synthetic",FirebaseFirestoreException.Code.PERMISSION_DENIED);cloud.failure=denied
        try{SubscriptionSyncCoordinator(db,cloud).run();fail("Failure must propagate")}catch(actual:Exception){assertSame(denied,actual)}
        assertEquals("error",db.sync().binding()!!.status);assertEquals(listOf(firstKey to "uploading"),states(db))
    }
    @Test fun `transient failure is not a rejection`()=fixture { db,store,cloud ->
        store.follow(first,true)
        cloud.failure=FirebaseFirestoreException("synthetic",FirebaseFirestoreException.Code.UNAVAILABLE)
        try{SubscriptionSyncCoordinator(db,cloud).run();fail("Failure must propagate")}catch(_:FirebaseFirestoreException){}
        assertEquals("offline",db.sync().binding()!!.status);assertEquals(listOf(firstKey to "uploading"),states(db))
        cloud.failure=null;assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
    }
    @Test fun `retry and discard leave other accounts and states untouched`()=fixture { db,store,cloud ->
        rejectFirst(db,store,cloud)
        db.sync().enqueue(SyncOutboxEntity("other","bob","g9","subscription_other",0,"{}",true,state="rejected"))
        db.sync().enqueue(SyncOutboxEntity("conflicted","alice","g1","subscription_conflicted",0,"{}",true,state="conflict"))
        store.retryRejected()
        assertEquals("rejected",db.sync().operations("bob","g9").single().state)
        assertEquals(listOf(firstKey to "queued","subscription_conflicted" to "conflict"),states(db))
        cloud.rejectedKeys+=firstKey;SubscriptionSyncCoordinator(db,cloud).run();store.discardRejected()
        assertEquals(1,db.sync().operations("bob","g9").size)
        assertEquals(listOf("subscription_conflicted" to "conflict"),states(db))
    }
}
