package dev.distillpod.android

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import dev.distillpod.android.sync.*
import dev.distillpod.core.sync.*
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Protocol-4 history compaction (#176): recovery of installations below the watermark and bounded compaction. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SyncHistoryCompactionTest {
    /** One account's cloud with deletable history, holding the same invariants as firestore.rules. */
    class History:SubscriptionCloud,SyncHistoryCloud {
        override var uid:String?="alice"
        val owner=SyncOwner("alice","g1")
        val rows=linkedMapOf<String,CloudSubscription>()
        val log=sortedMapOf<Long,CloudSubscription>()
        val receipts=linkedMapOf<String,CloudSubscription>()
        var sequence=0L;var protocol=3;var compactedThrough=0L
        var loseAck=false;var heads=0;var entityReads=0
        var beforeChanges:(suspend ()->Unit)?=null
        var headGate:kotlinx.coroutines.CompletableDeferred<Unit>?=null
        val calls=mutableListOf<String>()
        override suspend fun head():CloudHead { heads++;headGate?.await();return CloudHead(owner,sequence,protocol,compactedThrough) }
        override suspend fun entities(after:String):List<CloudSubscription> { entityReads++;return rows.values.filter { it.key>after }.sortedBy { it.key }.take(100) }
        override suspend fun changes(after:Long,through:Long):List<CloudSubscription> {
            beforeChanges?.invoke()
            return log.values.filter { it.sequence>after && it.sequence<=through }.take(100)
        }
        override suspend fun commit(operation:SyncOutboxEntity):CloudSubscription {
            receipts[operation.operationId]?.let { return it }
            val old=rows[operation.entityKey]
            if((old?.version?:0)!=operation.baseVersion) {
                if(receiptlessRetry(operation,compactedThrough,old))return old!!
                throw SubscriptionConflict(old)
            }
            val r=CloudSubscription(operation.entityKey,operation.baseVersion+1,if(operation.deleted)emptyMap() else SubscriptionSyncStore.decode(operation.payload),operation.deleted,++sequence)
            rows[r.key]=r;log[r.sequence]=r;receipts[operation.operationId]=r
            if(loseAck){loseAck=false;throw java.io.IOException("Interrupted after commit")}
            return r
        }
        /** What another, compacting installation leaves behind. */
        fun compact(through:Long) {
            protocol=4;compactedThrough=through
            log.headMap(through+1).clear();receipts.values.removeIf { it.sequence<=through }
        }
        override suspend fun upgradeForCompaction() { calls+="upgrade";check(protocol in 3..4);protocol=4 }
        override suspend fun advanceCompaction(through:Long) {
            calls+="advance:$through"
            check(protocol>=4 && through>compactedThrough && through<=sequence-SyncCompaction.RETAINED_SEQUENCES) { "Rules refuse this watermark" }
            compactedThrough=through
        }
        override suspend fun compactChanges(through:Long,limit:Int):Int {
            check(protocol>=4 && through<=compactedThrough) { "Rules refuse these deletes" }
            val doomed=log.keys.filter { it<=through }.take(limit);doomed.forEach { log.remove(it) }
            calls+="changes:${doomed.size}";return doomed.size
        }
        override suspend fun compactReceipts(through:Long,limit:Int):Int {
            check(protocol>=4 && through<=compactedThrough) { "Rules refuse these deletes" }
            val doomed=receipts.entries.filter { it.value.sequence<=through }.sortedBy { it.value.sequence }.take(limit).map { it.key }
            doomed.forEach { receipts.remove(it) }
            calls+="receipts:${doomed.size}";return doomed.size
        }
    }
    private val first=PodcastEntity("p1","https://example.test/first","First",subscribed=true)
    private val second=PodcastEntity("p2","https://example.test/second","Second",subscribed=true)
    private val third=PodcastEntity("p3","https://example.test/third","Third",subscribed=true)
    private fun key(p:PodcastEntity)=SubscriptionSyncStore.key(p)
    private suspend fun database(block:suspend (AppDatabase,SubscriptionSyncStore)->Unit) {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val name="compaction-${UUID.randomUUID()}.db";val db=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
        val store=SubscriptionSyncStore(db)
        try { store.bind(SyncOwner("alice","g1"));block(db,store) } finally { db.close();context.deleteDatabase(name) }
    }
    private fun one(block:suspend (AppDatabase,SubscriptionSyncStore,History)->Unit)=runBlocking { val cloud=History();database { db,store -> block(db,store,cloud) } }
    private fun two(block:suspend (AppDatabase,SubscriptionSyncStore,AppDatabase,SubscriptionSyncStore,History)->Unit)=runBlocking {
        val cloud=History();database { a,storeA -> database { b,storeB -> block(a,storeA,b,storeB,cloud) } }
    }
    private suspend fun run(db:AppDatabase,cloud:History)=SubscriptionSyncCoordinator(db,cloud).run()

    @Test fun `cursor below the watermark rebuilds from entities and keeps pending local edits`()=two { a,storeA,b,storeB,cloud ->
        storeA.follow(first,true);assertTrue(run(a,cloud));assertTrue(run(b,cloud))
        // While B is away, A unfollows one feed and follows another; history is compacted past B's cursor.
        storeA.follow(first.copy(subscribed=false),true);storeA.follow(second,true);assertTrue(run(a,cloud))
        storeB.follow(third,true)
        cloud.compact(cloud.sequence)
        val reads=cloud.entityReads
        assertTrue(run(b,cloud))
        assertTrue("B rebuilt from entities",cloud.entityReads>reads)
        assertFalse(b.library().podcast("p1")!!.subscribed);assertTrue(b.library().podcast("p2")!!.subscribed)
        assertTrue(b.library().podcast("p3")!!.subscribed);assertEquals(0,storeB.pendingCount())
        assertEquals(1L,cloud.rows[key(third)]!!.version)
        val binding=b.sync().binding()!!
        assertTrue(binding.bootstrapped);assertEquals("rebuilt at the head it read",cloud.compactedThrough,binding.cursor);assertEquals("synced",binding.status)
        // A, never below the watermark, receives B's edit by ordinary replay.
        assertTrue(run(a,cloud));assertTrue(a.library().podcast("p3")!!.subscribed)
    }
    @Test fun `pending edit of a record changed meanwhile surfaces a conflict after rebuilding`()=two { a,storeA,b,storeB,cloud ->
        storeA.follow(first,true);assertTrue(run(a,cloud));assertTrue(run(b,cloud))
        storeA.follow(first.copy(title="Renamed on A"),true);assertTrue(run(a,cloud))
        storeB.follow(first.copy(title="Renamed on B"),true)
        cloud.compact(cloud.sequence)
        assertFalse(run(b,cloud))
        assertEquals("conflict",b.sync().binding()!!.status)
        assertEquals(listOf("conflict"),b.sync().forKey("alice","g1",key(first)).map { it.state })
        assertEquals("Renamed on B",b.library().podcast("p1")!!.title)
        assertEquals(2L,b.sync().remote("alice","g1",key(first))!!.version)
        storeB.resolve(key(first),false)
        assertEquals("Renamed on A",b.library().podcast("p1")!!.title)
    }
    @Test fun `installation at or above the watermark replays without rebuilding`()=two { a,storeA,b,_,cloud ->
        storeA.follow(first,true);assertTrue(run(a,cloud));assertTrue(run(b,cloud))
        storeA.follow(second,true);assertTrue(run(a,cloud))
        cloud.compact(b.sync().binding()!!.cursor)
        val reads=cloud.entityReads
        assertTrue(run(b,cloud))
        assertEquals(reads,cloud.entityReads);assertTrue(b.library().podcast("p2")!!.subscribed)
    }
    @Test fun `compaction racing with replay rebuilds instead of reporting a history gap`()=two { a,storeA,b,_,cloud ->
        storeA.follow(first,true);assertTrue(run(a,cloud));assertTrue(run(b,cloud))
        storeA.follow(second,true);assertTrue(run(a,cloud))
        cloud.beforeChanges={ cloud.beforeChanges=null;cloud.compact(cloud.sequence) }
        assertFalse(run(b,cloud))
        assertEquals("pending",b.sync().binding()!!.status);assertFalse(b.sync().binding()!!.bootstrapped)
        assertTrue(run(b,cloud));assertTrue(b.library().podcast("p2")!!.subscribed)
    }
    @Test fun `missing history without a watermark still stops with a gap`()=two { a,storeA,b,_,cloud ->
        storeA.follow(first,true);assertTrue(run(a,cloud));assertTrue(run(b,cloud))
        storeA.follow(second,true);assertTrue(run(a,cloud))
        cloud.log.clear()
        try { run(b,cloud);fail("Gap accepted") } catch(gap:IllegalStateException) { assertEquals("Cloud history gap; recovery is required.",gap.message) }
        assertTrue(b.sync().binding()!!.bootstrapped)
    }
    @Test fun `lost acknowledgement whose receipt was compacted completes when the cloud holds the same state`()=one { db,store,cloud ->
        store.follow(first,true);cloud.loseAck=true
        try { run(db,cloud);fail() } catch(_:java.io.IOException) {}
        cloud.compact(cloud.sequence)
        assertTrue(run(db,cloud))
        assertEquals(1L,cloud.sequence);assertEquals(0,store.pendingCount())
        assertEquals("synced",db.sync().binding()!!.status);assertEquals(1L,db.sync().remote("alice","g1",key(first))!!.version)
    }
    @Test fun `lost acknowledgement after compaction keeps a conflict when the cloud moved on`()=one { db,store,cloud ->
        store.follow(first,true);cloud.loseAck=true
        try { run(db,cloud);fail() } catch(_:java.io.IOException) {}
        cloud.commit(SyncOutboxEntity("other","alice","g1",key(first),1,JSONObject(SubscriptionSyncStore.payload(first.copy(title="Elsewhere")).toSortedMap()).toString(),false))
        cloud.compact(cloud.sequence)
        assertFalse(run(db,cloud))
        assertEquals("conflict",db.sync().binding()!!.status)
    }
    @Test fun `only a retried operation on a compacted account is acknowledged by equal state`() {
        val payload=JSONObject(SubscriptionSyncStore.payload(first).toSortedMap()).toString()
        val retried=SyncOutboxEntity("op","alice","g1",key(first),0,payload,false,state="uploading")
        val same=CloudSubscription(key(first),1,SubscriptionSyncStore.decode(payload),false,5)
        assertTrue(receiptlessRetry(retried,5,same))
        assertFalse("receipts are complete before compaction",receiptlessRetry(retried,0,same))
        assertFalse("a first attempt cannot have committed",receiptlessRetry(retried.copy(state="queued"),5,same))
        assertFalse(receiptlessRetry(retried,5,same.copy(payload=same.payload+("title" to "Other"))))
        assertFalse(receiptlessRetry(retried,5,same.copy(deleted=true,payload=emptyMap())))
        assertFalse(receiptlessRetry(retried,5,null))
        val deletion=retried.copy(payload="{}",deleted=true,baseVersion=1)
        assertTrue(receiptlessRetry(deletion,5,CloudSubscription(key(first),2,emptyMap(),true,5)))
    }

    // Compaction by this installation.
    private var clock=1_000_000L
    private val day=24L*60*60*1000
    private suspend fun caughtUp(db:AppDatabase,cursor:Long) { db.sync().putBinding(db.sync().binding()!!.copy(bootstrapped=true,bootstrapAfter="",cursor=cursor)) }
    private fun compactor(db:AppDatabase,cloud:History,enabled:Boolean=true)=SyncHistoryCompactor(db,cloud,SyncCompactionPolicy(enabled)) { clock }
    private fun History.history(count:Int) {
        for(s in 1..count) {
            val r=CloudSubscription("subscription_k$s",1,mapOf("subscribed" to "true"),false,s.toLong())
            log[r.sequence]=r;receipts["op$s"]=r
        }
        sequence=count.toLong()
    }
    @Test fun `ordinary builds ship with compaction disabled`() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        assertFalse(context.resources.getBoolean(R.bool.sync_compaction_enabled))
    }
    @Test fun `accounts newer than this client still stop at the update gate`()=one { db,_,cloud ->
        cloud.protocol=SyncCompaction.PROTOCOL+1
        try { run(db,cloud);fail("Unknown protocol accepted") } catch(gate:IllegalStateException) { assertEquals("Update the app to read this sync account.",gate.message) }
    }
    @Test fun `disabled compaction never contacts the cloud`()=one { db,_,cloud ->
        cloud.history(10);cloud.sequence=50_000;caughtUp(db,50_000)
        val compactor=compactor(db,cloud,enabled=false)
        assertEquals(0,compactor.run());clock+=60*day;assertEquals(0,compactor.run())
        assertEquals(0,cloud.heads);assertEquals(3,cloud.protocol);assertTrue(cloud.calls.isEmpty())
    }
    @Test fun `history older than both windows is compacted in bounded resumable batches`()=one { db,_,cloud ->
        cloud.history(250);cloud.sequence=20_000;caughtUp(db,20_000)
        val compactor=compactor(db,cloud)
        assertEquals("the first run only starts the retention clock",0,compactor.run())
        assertEquals(3,cloud.protocol);assertTrue(cloud.calls.isEmpty())
        clock+=30*day;cloud.sequence=40_000;caughtUp(db,40_000)
        // A recent change that must survive.
        cloud.log[39_990]=CloudSubscription("subscription_recent",1,mapOf("subscribed" to "true"),false,39_990)
        assertEquals(200,compactor.run())
        assertEquals(listOf("upgrade","advance:20000","changes:200"),cloud.calls)
        assertEquals(4,cloud.protocol);assertEquals(20_000L,cloud.compactedThrough)
        assertEquals("throttled between runs",0,compactor.run());assertEquals(3,cloud.calls.size)
        clock+=SyncCompaction.INTERVAL_MS
        assertEquals(200,compactor.run());assertEquals(listOf("changes:50","receipts:150"),cloud.calls.drop(3))
        clock+=SyncCompaction.INTERVAL_MS
        assertEquals(100,compactor.run());clock+=SyncCompaction.INTERVAL_MS;assertEquals(0,compactor.run())
        assertEquals(listOf(39_990L),cloud.log.keys.toList());assertTrue(cloud.receipts.isEmpty())
        assertEquals(1,cloud.calls.count { it=="upgrade" })
    }
    @Test fun `watermark trails the retained sequences and this installation's cursor`()=one { db,_,cloud ->
        cloud.sequence=40_000;caughtUp(db,40_000)
        val compactor=compactor(db,cloud)
        compactor.run();clock+=31*day;cloud.sequence=45_000;caughtUp(db,30_000)
        compactor.run()
        assertEquals("never past what this installation replayed",30_000L,cloud.compactedThrough)
        clock+=31*day;cloud.sequence=48_000;caughtUp(db,48_000)
        compactor.run()
        assertEquals(38_000L,cloud.compactedThrough)
    }
    @Test fun `short histories and accounts before contextual moments are never upgraded`()=one { db,_,cloud ->
        cloud.sequence=9_000;caughtUp(db,9_000)
        val compactor=compactor(db,cloud)
        compactor.run();clock+=31*day;compactor.run()
        assertEquals(3,cloud.protocol);assertTrue(cloud.calls.isEmpty())
        for(protocol in 1..2) {
            cloud.protocol=protocol;cloud.sequence=50_000;caughtUp(db,50_000)
            clock+=31*day;compactor.run();clock+=31*day;compactor.run()
            assertEquals(protocol,cloud.protocol);assertTrue(cloud.calls.isEmpty())
        }
    }
    @Test fun `overlapping compaction runs do not both contact the cloud`()=one { db,_,cloud ->
        cloud.sequence=50_000;caughtUp(db,50_000)
        val compactor=SyncHistoryCompactor(db,cloud,SyncCompactionPolicy(true,intervalMs=0)) { clock }
        cloud.headGate=kotlinx.coroutines.CompletableDeferred()
        kotlinx.coroutines.coroutineScope {
            val first=async { compactor.run() }
            while(cloud.heads==0)kotlinx.coroutines.yield()
            assertEquals(0,compactor.run());assertEquals(1,cloud.heads)
            cloud.headGate!!.complete(Unit);first.await()
        }
    }
    @Test fun `paused other-account or unbootstrapped installations do not compact`()=one { db,store,cloud ->
        cloud.sequence=50_000
        val compactor=compactor(db,cloud)
        compactor.run();assertEquals("not yet bootstrapped",0,cloud.heads)
        caughtUp(db,50_000);cloud.uid="bob";compactor.run();assertEquals(0,cloud.heads)
        cloud.uid="alice";store.pause();compactor.run();assertEquals(0,cloud.heads)
    }
}
