package dev.distillpod.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.WorkInfo.State.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.distillpod.android.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** #142: sync requests merge with pending work instead of chaining a run per trigger. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SubscriptionSyncSchedulingTest {
    private val now=1_000_000L
    private fun w(state:WorkInfo.State,attempts:Int=0,next:Long=now)=SyncWorkSnapshot(state,attempts,next)
    private val backedOff=w(ENQUEUED,attempts=1,next=now+30_000)
    @Test fun `scheduling decision merges with pending work`() {
        fun action(vararg work:SyncWorkSnapshot,user:Boolean=false)=subscriptionWorkAction(work.toList(),user,now)
        assertEquals(SyncWorkAction.ENQUEUE,action())
        assertEquals(SyncWorkAction.ENQUEUE,action(w(SUCCEEDED),w(FAILED),w(CANCELLED)))
        assertEquals(SyncWorkAction.KEEP,action(w(ENQUEUED)))
        assertEquals(SyncWorkAction.KEEP,action(backedOff))
        assertEquals(SyncWorkAction.KEEP,action(backedOff,w(BLOCKED)))
        assertEquals(SyncWorkAction.REPLACE,action(backedOff,user=true))
        assertEquals(SyncWorkAction.REPLACE,action(backedOff,w(BLOCKED),user=true))
        // A request about to run (fresh, or backoff ending soon) is kept: replacing it could cancel a starting sync.
        assertEquals(SyncWorkAction.KEEP,action(w(ENQUEUED),user=true))
        assertEquals(SyncWorkAction.KEEP,action(w(ENQUEUED,attempts=2,next=now+2_000),user=true))
        assertEquals(SyncWorkAction.FOLLOW_UP,action(w(RUNNING)))
        assertEquals(SyncWorkAction.FOLLOW_UP,action(w(SUCCEEDED),w(RUNNING),user=true))
        // A running sync is never cancelled; its single follow-up already covers new requests.
        assertEquals(SyncWorkAction.KEEP,action(w(RUNNING),w(BLOCKED)))
        assertEquals(SyncWorkAction.KEEP,action(w(RUNNING),w(BLOCKED),user=true))
    }

    private fun TestScope.scheduler(failed:(Exception)->Unit={},schedule:suspend (Boolean)->Unit)=
        SubscriptionSyncScheduler(backgroundScope,clock={ testScheduler.currentTime },failed=failed,schedule=schedule)
    @Test fun `bursts of kicks coalesce into one scheduling decision after they settle`()=runTest {
        val calls=mutableListOf<Boolean>();val scheduler=scheduler { calls+=it }
        repeat(5) { scheduler.kick();advanceTimeBy(200) }
        // Last kick at 800 ms: the decision waits for 300 ms of quiet.
        advanceTimeBy(50);runCurrent();assertEquals(emptyList<Boolean>(),calls)
        advanceTimeBy(100);runCurrent();assertEquals(listOf(false),calls)
        scheduler.kick();advanceTimeBy(1_000);runCurrent()
        assertEquals(listOf(false,false),calls)
    }
    @Test fun `a continuous stream of kicks still schedules within the maximum wait`()=runTest {
        val calls=mutableListOf<Boolean>();val scheduler=scheduler { calls+=it }
        repeat(25) { scheduler.kick();advanceTimeBy(200) }
        runCurrent();assertEquals(2,calls.size)
    }
    @Test fun `sync now skips the debounce even inside a kick window`()=runTest {
        val calls=mutableListOf<Boolean>();val scheduler=scheduler { calls+=it }
        scheduler.kick();scheduler.syncNow();runCurrent()
        assertEquals(listOf(true),calls)
        scheduler.kick();advanceTimeBy(100);scheduler.syncNow();runCurrent()
        assertEquals(listOf(true,true),calls)
    }
    @Test fun `a kick during an in-flight decision schedules once more`()=runTest {
        val calls=mutableListOf<Boolean>();val gate=CompletableDeferred<Unit>()
        val scheduler=scheduler { calls+=it;if(calls.size==1)gate.await() }
        scheduler.kick();advanceTimeBy(1_000);runCurrent();assertEquals(1,calls.size)
        scheduler.kick();gate.complete(Unit);advanceTimeBy(1_000);runCurrent()
        assertEquals(2,calls.size)
    }
    @Test fun `failed scheduling is reported and does not stop or downgrade later requests`()=runTest {
        val calls=mutableListOf<Boolean>();val failures=mutableListOf<Exception>()
        val scheduler=scheduler({ failures+=it }) { calls+=it;if(calls.size<=2)throw if(calls.size==1)IllegalStateException("synthetic") else java.util.concurrent.CancellationException("future") }
        scheduler.syncNow();runCurrent()
        scheduler.kick();advanceTimeBy(1_000);runCurrent()
        scheduler.kick();advanceTimeBy(1_000);runCurrent()
        // The user request survives both failures, including a cancelled WorkManager future.
        assertEquals(listOf(true,true,true),calls);assertEquals(2,failures.size)
    }
    @Test fun `concurrent kicks from many threads coalesce without losing the last one`()=runBlocking {
        val count=AtomicInteger();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val scheduler=SubscriptionSyncScheduler(scope,debounceMs=20) { count.incrementAndGet() }
            (1..8).map { thread { repeat(500) { scheduler.kick() } } }.forEach { it.join() }
            withTimeout(10_000) { while(true) { val seen=count.get();delay(300);if(seen>0 && count.get()==seen)break } }
            val settled=count.get()
            assertTrue("$settled scheduling decisions for 4000 kicks",settled in 1..200)
            scheduler.kick()
            withTimeout(10_000) { while(count.get()==settled)delay(10) }
        } finally { scope.cancel() }
    }

    // WorkManager behavior with the production request and unique name.
    class HeldWorker(context:Context,params:WorkerParameters):Worker(context,params) {
        companion object { @Volatile var run:()->ListenableWorker.Result={ ListenableWorker.Result.success() } }
        override fun doWork():ListenableWorker.Result=run()
    }
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    /** [running] keeps real executors so a worker can stay RUNNING while the test schedules more work. */
    private fun work(running:Boolean=false,block:suspend (WorkManager)->Unit)=runBlocking {
        val factory=object:WorkerFactory() {
            override fun createWorker(context:Context,name:String,params:WorkerParameters):ListenableWorker?=HeldWorker(context,params)
        }
        if(running) WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder()
            .setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task,"held-sync-worker").apply { isDaemon=true } })
            .setWorkerFactory(factory).build(),WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS)
        else WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder().setExecutor(SynchronousExecutor())
            .setWorkerFactory(factory).build())
        val manager=WorkManager.getInstance(context)
        try { block(manager) } finally {
            HeldWorker.run={ ListenableWorker.Result.success() }
            manager.cancelAllWork().result.get(5,TimeUnit.SECONDS);WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }
    private fun open(manager:WorkManager)=manager.getWorkInfosForUniqueWork(SUBSCRIPTION_SYNC_WORK).get(5,TimeUnit.SECONDS).filter { !it.state.isFinished }
    // The test thread is Robolectric's main thread; pump it while real executors make progress.
    private suspend fun until(condition:()->Boolean)=withTimeout(10_000) {
        while(!condition()) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();delay(20) }
    }
    private suspend fun awaitState(manager:WorkManager,id:UUID,state:WorkInfo.State)=until { manager.getWorkInfoById(id).get(5,TimeUnit.SECONDS)?.state==state }
    private suspend fun schedule(manager:WorkManager,user:Boolean=false,active:Boolean=true)=scheduleSubscriptionWork(manager,user,active,::subscriptionSyncRequest)

    @Test fun `kicks while enqueued keep the single pending run`()=work { manager ->
        assertEquals(SyncWorkAction.ENQUEUE,schedule(manager))
        val pending=open(manager).single().id
        repeat(3) { assertEquals(SyncWorkAction.KEEP,schedule(manager)) }
        assertEquals(listOf(pending),open(manager).map { it.id })
    }
    @Test fun `sync now replaces work waiting in backoff while kicks keep it`()=work { manager ->
        HeldWorker.run={ ListenableWorker.Result.retry() }
        schedule(manager);val backedOff=open(manager).single().id
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(backedOff)
        val waiting=open(manager).single();assertEquals(backedOff,waiting.id);assertEquals(1,waiting.runAttemptCount)
        assertEquals(SyncWorkAction.KEEP,schedule(manager));assertEquals(backedOff,open(manager).single().id)
        assertEquals(SyncWorkAction.REPLACE,schedule(manager,user=true))
        val fresh=open(manager).single()
        assertNotEquals(backedOff,fresh.id);assertEquals(0,fresh.runAttemptCount)
        // REPLACE cancels and prunes the backed-off request.
        assertTrue(manager.getWorkInfoById(backedOff).get(5,TimeUnit.SECONDS)?.state.let { it==null || it==CANCELLED })
    }
    @Test fun `running work gets exactly one follow-up and is never cancelled`()=work(running=true) { manager ->
        val started=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        HeldWorker.run={ started.countDown();release.await(10,TimeUnit.SECONDS);ListenableWorker.Result.success() }
        try {
            schedule(manager);val first=open(manager).single().id
            WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(first)
            until { started.count==0L };awaitState(manager,first,RUNNING)
            assertEquals(SyncWorkAction.FOLLOW_UP,schedule(manager))
            assertEquals(SyncWorkAction.KEEP,schedule(manager))
            assertEquals(SyncWorkAction.KEEP,schedule(manager,user=true))
            assertEquals(listOf(RUNNING,BLOCKED),open(manager).map { it.state }.sorted())
            release.countDown();awaitState(manager,first,SUCCEEDED)
            val followUp=open(manager).single();assertNotEquals(first,followUp.id);assertEquals(ENQUEUED,followUp.state)
        } finally { release.countDown() }
    }
    @Test fun `finished failed chain starts a fresh run`()=work { manager ->
        HeldWorker.run={ ListenableWorker.Result.failure() }
        schedule(manager);val failed=open(manager).single().id
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(failed)
        assertTrue(open(manager).isEmpty())
        assertEquals(SyncWorkAction.ENQUEUE,schedule(manager))
        assertEquals(ENQUEUED,open(manager).single().state)
    }
    @Test fun `follow-up racing a finished head is neither lost nor blocked`()=work { manager ->
        for(result in listOf(ListenableWorker.Result.failure(),ListenableWorker.Result.success())) {
            HeldWorker.run={ result }
            schedule(manager);val head=open(manager).single().id
            WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(head)
            // The decision saw RUNNING; the head finished before the follow-up was enqueued.
            enqueueSubscriptionWork(manager,SyncWorkAction.FOLLOW_UP,::subscriptionSyncRequest)
            assertEquals(listOf(ENQUEUED),open(manager).map { it.state })
            manager.cancelUniqueWork(SUBSCRIPTION_SYNC_WORK).result.get(5,TimeUnit.SECONDS)
        }
    }
    @Test fun `paused sync schedules nothing`()=work { manager ->
        assertEquals(SyncWorkAction.SKIP,schedule(manager,active=false))
        assertTrue(open(manager).isEmpty())
    }
    @Test fun `retried run with a follow-up is kept by kicks and replaced by sync now`()=work(running=true) { manager ->
        val started=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        HeldWorker.run={ started.countDown();release.await(10,TimeUnit.SECONDS);ListenableWorker.Result.retry() }
        try {
            schedule(manager);val first=open(manager).single().id
            WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(first)
            until { started.count==0L };awaitState(manager,first,RUNNING)
            assertEquals(SyncWorkAction.FOLLOW_UP,schedule(manager))
            release.countDown();awaitState(manager,first,ENQUEUED)
            assertEquals(listOf(ENQUEUED,BLOCKED),open(manager).map { it.state }.sorted())
            assertEquals(SyncWorkAction.KEEP,schedule(manager))
            assertEquals(SyncWorkAction.REPLACE,schedule(manager,user=true))
            val fresh=open(manager).single();assertNotEquals(first,fresh.id);assertEquals(0,fresh.runAttemptCount)
        } finally { release.countDown() }
    }
}
