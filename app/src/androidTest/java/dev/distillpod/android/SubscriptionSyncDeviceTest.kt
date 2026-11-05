package dev.distillpod.android

import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import dev.distillpod.android.data.*
import dev.distillpod.android.sync.*
import dev.distillpod.core.sync.SyncOwner
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SubscriptionSyncDeviceTest {
    @Test fun offlineFollowRestartsRestoresUnfollowsAndRejectsOtherAccount() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Explicit isolated cloud emulator argument required",args.getString("syncEmulators")=="true")
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator") || Build.HARDWARE.contains("ranchu")) { "Synthetic cloud tests require an Android emulator" }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val host=args.getString("syncEmulatorHost")?:"10.0.2.2"
        require(host in listOf("10.0.2.2","127.0.0.1"))
        for(port in listOf(8085,9099))Socket().use { it.connect(InetSocketAddress(host,port),3000) }
        val tag=UUID.randomUUID().toString()
        val options=FirebaseOptions.Builder().setProjectId("demo-distillpod-sync").setApplicationId("1:123456789:android:1234567890abcdef")
            .setApiKey("fake-api-key").build()
        val a=FirebaseApp.initializeApp(context,options,"a-$tag")
        val b=FirebaseApp.initializeApp(context,options,"b-$tag")
        val authA=FirebaseAuth.getInstance(a).apply { useEmulator(host,9099) }
        val authB=FirebaseAuth.getInstance(b).apply { useEmulator(host,9099) }
        fun firestore(app:FirebaseApp)=FirebaseFirestore.getInstance(app).apply {
            firestoreSettings=FirebaseFirestoreSettings.Builder().setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
            useEmulator(host,8085)
        }
        val fsA=firestore(a);val fsB=firestore(b)
        val nameA="sync-a-$tag.db";val nameB="sync-b-$tag.db"
        var dbA=Room.databaseBuilder(context,AppDatabase::class.java,nameA).build()
        val dbB=Room.databaseBuilder(context,AppDatabase::class.java,nameB).build()
        try {
            withTimeout(60000) {
                val email="alice-$tag@example.test";val password="fixture-password-123"
                val user=authA.createUserWithEmailAndPassword(email,password).await().user!!
                authB.signInWithEmailAndPassword(email,password).await()
                val cloudA=FirebaseSubscriptionCloud(authA,fsA);val cloudB=FirebaseSubscriptionCloud(authB,fsB)
                val head=cloudA.head()
                SubscriptionSyncStore(dbA,isolatedDemo=true).bind(head.owner);SubscriptionSyncStore(dbB,isolatedDemo=true).bind(cloudB.head().owner)
                fsA.disableNetwork().await()
                val podcast=PodcastEntity("podcast1","https://example.test/public-feed","Synthetic subscription",originalFeedUrl="https://example.test/public-feed")
                SubscriptionSyncStore(dbA,isolatedDemo=true).follow(podcast,true)
                assertEquals(1,SubscriptionSyncStore(dbA,isolatedDemo=true).pendingCount())
                dbA.close();dbA=Room.databaseBuilder(context,AppDatabase::class.java,nameA).build()
                fsA.enableNetwork().await()
                assertTrue(SubscriptionSyncCoordinator(dbA,cloudA).run())
                assertTrue(SubscriptionSyncCoordinator(dbB,cloudB).run())
                assertEquals(podcast.title,dbB.library().podcast("podcast1")!!.title)
                SubscriptionSyncStore(dbA,isolatedDemo=true).follow(podcast.copy(id="fromA",feedUrl="https://example.test/a",originalFeedUrl="https://example.test/a"),true)
                SubscriptionSyncStore(dbB,isolatedDemo=true).follow(podcast.copy(id="fromB",feedUrl="https://example.test/b",originalFeedUrl="https://example.test/b"),true)
                coroutineScope {
                    val left=async { SubscriptionSyncCoordinator(dbA,cloudA).run() }
                    val right=async { SubscriptionSyncCoordinator(dbB,cloudB).run() }
                    assertTrue(left.await());assertTrue(right.await())
                }
                SubscriptionSyncCoordinator(dbA,cloudA).run();SubscriptionSyncCoordinator(dbB,cloudB).run()
                assertEquals(3,dbA.library().podcasts().count { it.subscribed })
                assertEquals(3,dbB.library().podcasts().count { it.subscribed })
                // Real SDK transport: restore portable records, retain divergent sessions, delete a moment.
                val portableA=PortableLibrarySync(dbA,isolatedDemo=true)
                val portableB=PortableLibrarySync(dbB,isolatedDemo=true)
                dbA.library().putEpisode(EpisodeEntity("episode1","podcast1","guid1","Synthetic episode","https://example.test/audio",durationMs=100000,localFile="/device/private/audio",downloadState="downloaded",mediaFingerprint="sha"))
                portableA.checkpoint("episode1",70000,100000,false,1,"sessionA",1000)
                portableA.putMoment(DistillationEntity("moment1","episode1",1000,11000,"Synthetic source words",quote="source words",insight="A completed synthetic insight",revision="r1",fingerprint="sha",state="succeeded",model="test-model"))
                portableA.markCompleted("episode1",true)
                assertTrue(SubscriptionSyncCoordinator(dbA,cloudA).run())
                assertTrue(SubscriptionSyncCoordinator(dbB,cloudB).run())
                val restored=dbB.library().episode("episode1")!!
                assertEquals(70000L,restored.positionMs);assertTrue(restored.completed)
                assertNull(restored.localFile);assertEquals("none",restored.downloadState)
                assertEquals("A completed synthetic insight",dbB.library().distillation("moment1")!!.insight)
                assertTrue(dbB.library().transcripts().isEmpty())
                val selected=org.json.JSONObject().put("version",2).put("generationId","synthetic-attempt").put("originalExcerpt","Synthetic source words")
                    .put("selectedStartMs",2000).put("selectedEndMs",9000).put("passageStart",0).put("passageEnd",0)
                    .put("reviewModel","test-review").put("generationMs",1).put("reviewMs",1).put("quoteOmitted",true).toString()
                portableA.putMoment(dbA.library().distillation("moment1")!!.copy(quote=null,sourceJson=selected))
                assertFalse(SubscriptionSyncCoordinator(dbA,cloudA).run())
                assertEquals("upgrade_required",dbA.sync().binding()!!.status)
                cloudA.enableContextualMoments()
                assertEquals(3,cloudB.head().protocol)
                assertTrue(SubscriptionSyncCoordinator(dbA,cloudA).run())
                assertTrue(SubscriptionSyncCoordinator(dbB,cloudB).run())
                val contextual=dbB.library().distillation("moment1")!!
                assertEquals("succeeded",contextual.state);assertNull(contextual.quote)
                assertEquals(2000L,contextual.sourceStartMs);assertEquals(1000L,contextual.startMs)
                assertEquals(selected,contextual.sourceJson)
                fsA.disableNetwork().await()
                portableA.checkpoint("episode1",10000,100000,false,2,"sessionA",2000,true)
                portableB.checkpoint("episode1",90000,100000,false,3,"sessionB",1000,true)
                assertTrue(SubscriptionSyncCoordinator(dbB,cloudB).run())
                fsA.enableNetwork().await()
                assertFalse(SubscriptionSyncCoordinator(dbA,cloudA).run())
                assertEquals(10000L,dbA.library().episode("episode1")!!.positionMs)
                assertTrue(dbA.sync().forKey(user.uid,head.owner.generation,"progress_episode1").any {it.state=="conflict"})
                portableA.resolve("progress_episode1",true)
                assertTrue(SubscriptionSyncCoordinator(dbA,cloudA).run())
                portableB.releaseSessions()
                assertTrue(SubscriptionSyncCoordinator(dbB,cloudB).run())
                assertEquals(10000L,dbB.library().episode("episode1")!!.positionMs)
                portableA.deleteMoment("moment1")
                assertTrue(SubscriptionSyncCoordinator(dbA,cloudA).run())
                assertTrue(SubscriptionSyncCoordinator(dbB,cloudB).run())
                assertNull(dbB.library().distillation("moment1"))
                assertEquals("/device/private/audio",dbA.library().episode("episode1")!!.localFile)
                SubscriptionSyncStore(dbA,isolatedDemo=true).follow(podcast.copy(subscribed=false),true)
                authA.signOut()
                assertFalse(SubscriptionSyncCoordinator(dbA,cloudA).run())
                val other=authA.createUserWithEmailAndPassword("bob-$tag@example.test",password).await().user!!
                assertFalse(SubscriptionSyncCoordinator(dbA,cloudA).run())
                assertEquals(1,SubscriptionSyncStore(dbA,isolatedDemo=true).pendingCount())
                try { SubscriptionSyncStore(dbA,isolatedDemo=true).bind(SyncOwner(other.uid,head.owner.generation));fail("Other account adopted library") } catch(_:IllegalStateException) { }
                try {fsA.collection("users").document(user.uid).get(Source.SERVER).await();fail("Cross-account read allowed")}
                catch(error:FirebaseFirestoreException){assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED,error.code)}
                authA.signInWithEmailAndPassword(email,password).await()
                assertTrue(SubscriptionSyncCoordinator(dbA,cloudA).run())
                assertTrue(SubscriptionSyncCoordinator(dbB,cloudB).run())
                assertFalse(dbB.library().podcast("podcast1")!!.subscribed)
                assertEquals(0,SubscriptionSyncStore(dbA,isolatedDemo=true).pendingCount())
            }
        } finally {
            dbA.close();dbB.close();context.deleteDatabase(nameA);context.deleteDatabase(nameB)
            fsA.terminate().await();fsB.terminate().await();a.delete();b.delete()
        }
    }
}
