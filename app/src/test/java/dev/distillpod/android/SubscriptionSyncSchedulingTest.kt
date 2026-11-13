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
    @Test fun `scheduling decision merges with pending work`() {
        assertEquals(SyncWorkAction.ENQUEUE,subscriptionWorkAction(emptyList(),false))
        assertEquals(SyncWorkAction.ENQUEUE,subscriptionWorkAction(listOf(SUCCEEDED,FAILED,CANCELLED),false))
        assertEquals(SyncWorkAction.KEEP,subscriptionWorkAction(listOf(ENQUEUED),false))
        assertEquals(SyncWorkAction.REPLACE,subscriptionWorkAction(listOf(ENQUEUED),true))
        assertEquals(SyncWorkAction.REPLACE,subscriptionWorkAction(listOf(ENQUEUED,BLOCKED),true))
        assertEquals(SyncWorkAction.KEEP,subscriptionWorkAction(listOf(ENQUEUED,BLOCKED),false))
        assertEquals(SyncWorkAction.FOLLOW_UP,subscriptionWorkAction(listOf(RUNNING),false))
        assertEquals(SyncWorkAction.FOLLOW_UP,subscriptionWorkAction(listOf(SUCCEEDED,RUNNING),true))
        // A running sync is never cancelled; its single follow-up already covers new requests.
        assertEquals(SyncWorkAction.KEEP,subscriptionWorkAction(listOf(RUNNING,BLOCKED),false))
        assertEquals(SyncWorkAction.KEEP,subscriptionWorkAction(listOf(RUNNING,BLOCKED),true))
    }

    @Test fun `bursts of kicks coalesce into one scheduling decision`()=runTest {
        val calls=mutableListOf<Boolean>()
        val scheduler=SubscriptionSyncScheduler(backgroundScope) { calls+=it }
        repeat(7) { scheduler.kick();advanceTimeBy(40) }
        advanceTimeBy(1_000);runCurrent()
        assertEquals(listOf(false),calls)
        scheduler.kick();advanceTimeBy(1_000);runCurrent()
        assertEquals(listOf(false,false),calls)
    }
    @Test fun `sync now skips the debounce`()=runTest {
        val calls=mutableListOf<Boolean>()
        val scheduler=SubscriptionSyncScheduler(backgroundScope) { calls+=it }
        scheduler.kick();scheduler.syncNow();runCurrent()
        assertEquals(listOf(true),calls)
        scheduler.kick();advanceTimeBy(100);scheduler.syncNow();advanceTimeBy(250);runCurrent()
        assertEquals(listOf(true,true),calls)
    }
    @Test fun `a failed scheduling attempt does not stop later kicks`()=runTest {
        var attempts=0
        val scheduler=SubscriptionSyncScheduler(backgroundScope) { attempts++;if(attempts==1)error("synthetic") }
        scheduler.kick();advanceTimeBy(1_000);runCurrent()
        scheduler.kick();advanceTimeBy(1_000);runCurrent()
        assertEquals(2,attempts)
    }
    @Test fun `concurrent kicks from many threads coalesce without losing the last one`()=runBlocking {
        val count=AtomicInteger();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val scheduler=SubscriptionSyncScheduler(scope,debounceMs=20) { count.incrementAndGet() }
            (1..8).map { thread { repeat(500) { scheduler.kick() } } }.forEach { it.join() }
            withTimeout(5_000) { while(true) { val seen=count.get();delay(200);if(seen>0 && count.get()==seen)break } }
            val settled=count.get()
            assertTrue("$settled scheduling decisions for 4000 kicks",settled in 1..20)
            scheduler.kick()
            withTimeout(5_000) { while(count.get()==settled)delay(10) }
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
    private suspend fun schedule(manager:WorkManager,user:Boolean=false)=scheduleSubscriptionWork(manager,user,::subscriptionSyncRequest)

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
    @Test fun `failed chain is replaced rather than appended to`()=work { manager ->
        HeldWorker.run={ ListenableWorker.Result.failure() }
        schedule(manager);val failed=open(manager).single().id
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(failed)
        assertTrue(open(manager).isEmpty())
        assertEquals(SyncWorkAction.ENQUEUE,schedule(manager))
        assertEquals(ENQUEUED,open(manager).single().state)
    }
}
