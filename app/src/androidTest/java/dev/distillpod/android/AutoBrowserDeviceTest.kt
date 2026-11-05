package dev.distillpod.android

import android.content.ComponentName
import android.media.browse.MediaBrowser as PlatformBrowser
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.auto.AutoCatalog
import dev.distillpod.android.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synthetic data only; run on the dedicated disposable Auto emulator. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AutoBrowserDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val component=ComponentName(context,PlaybackService::class.java)
    private fun seed()=runBlocking {
        context.appGraph.ready.await()
        val file=java.io.File(context.filesDir,"auto-test.wav")
        val bytes=ByteBuffer.allocate(44+16000*2*60).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(bytes.capacity()-8).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes.capacity()-44)
        file.writeBytes(bytes.array())
        val dao=context.appGraph.db.library()
        dao.putPodcast(PodcastEntity("auto-show","https://example.invalid/feed","Café Auto"))
        dao.putEpisode(EpisodeEntity("auto-ep","auto-show","auto-guid","Auto episode","https://example.invalid/audio",durationMs=60000,
            positionMs=5000,localFile=file.path,downloadState="downloaded",downloadedBytes=file.length(),mediaFingerprint="auto-fixture"))
        dao.putTranscript(TranscriptEntity("auto-ep","auto-rev","auto-fixture","publisher","en",source="publisher",state="ready",durationMs=60000,processedMs=60000))
        dao.putCoverage(CoverageEntity("auto-ep","auto-rev",0,60000))
        dao.putSegments(listOf(SegmentEntity("auto-ep","auto-rev",0,60000,"A synthetic moment for Android Auto tests.")))
    }
    private fun browser(listener:MediaBrowser.Listener?=null):MediaBrowser {
        val result=CompletableFuture<MediaBrowser>()
        instrumentation.runOnMainSync {
            val builder=MediaBrowser.Builder(context,SessionToken(context,component))
            if(listener!=null)builder.setListener(listener)
            val future=builder.buildAsync()
            future.addListener({try{result.complete(future.get())}catch(e:Exception){result.completeExceptionally(e)}},context.mainExecutor)
        }
        return result.get(15,TimeUnit.SECONDS)
    }
    private fun <T> main(block:()->T):T {
        val future=CompletableFuture<T>()
        instrumentation.runOnMainSync {try{future.complete(block())}catch(e:Throwable){future.completeExceptionally(e)}}
        return future.get(15,TimeUnit.SECONDS)
    }
    private fun await(predicate:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(System.nanoTime()<end) {if(main(predicate))return;Thread.sleep(100)}
        fail("Player did not reach expected state")
    }
    @Test fun modernBrowserBrowsesSelectsResumesAndSavesMoment() {
        seed();val browser=browser()
        try {
            val root=main {browser.getLibraryRoot(null)}.get(10,TimeUnit.SECONDS)
            assertEquals(AutoCatalog.ROOT,root.value!!.mediaId)
            val children=main {browser.getChildren(AutoCatalog.ROOT,0,100,null)}.get(10,TimeUnit.SECONDS)
            assertEquals(4,children.value!!.size)
            main {browser.setMediaItem(MediaItem.Builder().setMediaId("auto-ep").build());browser.prepare()}
            await {browser.currentMediaItem?.mediaId=="auto-ep" && browser.playbackState==androidx.media3.common.Player.STATE_READY}
            assertTrue(main {browser.currentPosition in 4900..5100})
            assertEquals(10000L,main {browser.seekBackIncrement});assertEquals(30000L,main {browser.seekForwardIncrement})
            main {browser.seekTo(20000)}
            await {browser.currentPosition in 19900..20100}
            assertEquals(SessionResult.RESULT_SUCCESS,main {browser.sendCustomCommand(dev.distillpod.android.playback.PlaybackControls.back,Bundle.EMPTY)}.get(10,TimeUnit.SECONDS).resultCode)
            await {browser.currentPosition in 9900..10100}
            assertEquals(SessionResult.RESULT_SUCCESS,main {browser.sendCustomCommand(dev.distillpod.android.playback.PlaybackControls.forward,Bundle.EMPTY)}.get(10,TimeUnit.SECONDS).resultCode)
            await {browser.currentPosition in 39900..40100}
            main {browser.seekTo(5000)}
            await {browser.currentPosition in 4900..5100}
            val result=main {browser.sendCustomCommand(SessionCommand("dev.distillpod.android.DISTILL_MOMENT",Bundle.EMPTY),Bundle.EMPTY)}.get(10,TimeUnit.SECONDS)
            assertEquals(SessionResult.RESULT_SUCCESS,result.resultCode)
            runBlocking {assertTrue(context.appGraph.db.library().distillations().any {it.episodeId=="auto-ep" && it.endMs in 4900..5100})}
            assertEquals("auto-ep",main {browser.currentMediaItem?.mediaId})
        } finally {main {browser.stop();browser.release()}}
    }
    @Test fun modernSubscriptionsReceiveLibraryUpdates() {
        seed();val changed=CountDownLatch(1);var expected=Int.MAX_VALUE
        val browser=browser(object:MediaBrowser.Listener {
            override fun onChildrenChanged(browser:MediaBrowser,parentId:String,itemCount:Int,params:MediaLibraryService.LibraryParams?) {
                if(parentId==AutoCatalog.PODCASTS && itemCount>=expected)changed.countDown()
            }
        })
        try {
            val initial=main {browser.getChildren(AutoCatalog.PODCASTS,0,100,null)}.get(10,TimeUnit.SECONDS).value!!.size
            expected=initial+1
            assertEquals(LibraryResult.RESULT_SUCCESS,main {browser.subscribe(AutoCatalog.PODCASTS,null)}.get(10,TimeUnit.SECONDS).resultCode)
            runBlocking {context.appGraph.db.library().putPodcast(PodcastEntity("new-${System.nanoTime()}","https://example.invalid/${System.nanoTime()}","New show"))}
            assertTrue("Subscribed folder did not refresh",changed.await(15,TimeUnit.SECONDS))
        } finally {main {browser.release()}}
    }
    fun prepareRestartCheckpoint() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("autoRestartPhase")=="prepare")
        seed();val browser=browser()
        try {
            main {browser.setMediaItem(MediaItem.Builder().setMediaId("auto-ep").build());browser.prepare()}
            await {browser.currentMediaItem?.mediaId=="auto-ep" && browser.playbackState==androidx.media3.common.Player.STATE_READY}
            main {browser.seekTo(17000);browser.pause()}
            await {browser.currentPosition in 16900..17100}
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(System.nanoTime()<deadline) {
                if(runBlocking {context.appGraph.db.library().episode("auto-ep")!!.positionMs in 16900..17100})return
                Thread.sleep(100)
            }
            fail("Playback checkpoint was not persisted")
        } finally {main {browser.release()}}
    }
    fun verifyRestartCheckpoint() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("autoRestartPhase")=="verify")
        runBlocking {context.appGraph.ready.await();assertTrue(context.appGraph.db.library().episode("auto-ep")!!.positionMs in 16900..17100)}
        val browser=browser()
        try {
            await {browser.currentMediaItem?.mediaId=="auto-ep" && browser.playbackState==androidx.media3.common.Player.STATE_READY}
            assertTrue(main {browser.currentPosition in 16900..17100})
            assertFalse(main {browser.isPlaying})
        } finally {main {browser.stop();browser.release()}}
    }
    @Test fun platformLegacyBrowserConnectsAndSubscribesWithoutActivity() {
        seed();val ready=CountDownLatch(1);val loaded=CountDownLatch(1)
        val rows=java.util.concurrent.atomic.AtomicReference<List<PlatformBrowser.MediaItem>>()
        lateinit var browser:PlatformBrowser
        main {
            browser=PlatformBrowser(context,component,object:PlatformBrowser.ConnectionCallback(){
                override fun onConnected(){ready.countDown()}
            },null);browser.connect()
        }
        try {
            assertTrue("Legacy browser failed to connect",ready.await(15,TimeUnit.SECONDS))
            main {browser.subscribe(AutoCatalog.ROOT,object:PlatformBrowser.SubscriptionCallback(){
                override fun onChildrenLoaded(parentId:String,children:MutableList<PlatformBrowser.MediaItem>){rows.set(children);loaded.countDown()}
                override fun onError(parentId:String){loaded.countDown()}
            })}
            assertTrue(loaded.await(10,TimeUnit.SECONDS));assertEquals(4,rows.get()?.size)
        } finally {main {browser.disconnect()}}
    }
}
