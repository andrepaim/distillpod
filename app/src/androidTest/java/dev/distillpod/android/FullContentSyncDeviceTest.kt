package dev.distillpod.android

import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.storage.FirebaseStorage
import dev.distillpod.android.data.*
import dev.distillpod.android.sync.*
import dev.distillpod.core.sync.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FullContentSyncDeviceTest {
    @Test fun queuePreferencesTranscriptAnalysisConversationAndResearchRestoreWithoutJobs()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("syncEmulators")=="true")
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val tag=UUID.randomUUID().toString();val bucket="demo-distillpod-sync.appspot.com"
        val options=FirebaseOptions.Builder().setProjectId("demo-distillpod-sync").setApplicationId("1:123456789:android:1234567890abcdef")
            .setApiKey("fake-api-key").setStorageBucket(bucket).build()
        val appA=FirebaseApp.initializeApp(context,options,"content-a-$tag");val appB=FirebaseApp.initializeApp(context,options,"content-b-$tag")
        fun auth(app:FirebaseApp)=FirebaseAuth.getInstance(app).apply {useEmulator("10.0.2.2",9099)}
        fun firestore(app:FirebaseApp)=FirebaseFirestore.getInstance(app).apply {
            firestoreSettings=FirebaseFirestoreSettings.Builder().setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build();useEmulator("10.0.2.2",8085)
        }
        val authA=auth(appA);val authB=auth(appB);val fsA=firestore(appA);val fsB=firestore(appB)
        val storageA=FirebaseStorage.getInstance(appA).apply {useEmulator("10.0.2.2",9199)}
        val storageB=FirebaseStorage.getInstance(appB).apply {useEmulator("10.0.2.2",9199)}
        val nameA="content-a-$tag.db";val nameB="content-b-$tag.db"
        val a=Room.databaseBuilder(context,AppDatabase::class.java,nameA).build();val b=Room.databaseBuilder(context,AppDatabase::class.java,nameB).build()
        val dirA=File(context.cacheDir,"content-a-$tag");val dirB=File(context.cacheDir,"content-b-$tag")
        try {withTimeout(90000) {
            val email="content-$tag@example.test";val password="synthetic-password-123"
            authA.createUserWithEmailAndPassword(email,password).await();authB.signInWithEmailAndPassword(email,password).await()
            val filesA=ContentFiles(context,dirA);val filesB=ContentFiles(context,dirB)
            val artifactsA=ArtifactSync(a,filesA,FirebaseContentObjects(authA,storageA,filesA,UploadSessions(context)),isolatedDemo=true)
            val artifactsB=ArtifactSync(b,filesB,FirebaseContentObjects(authB,storageB,filesB,UploadSessions(context)),isolatedDemo=true)
            val cloudA=FirebaseSubscriptionCloud(authA,fsA,artifactsA);val cloudB=FirebaseSubscriptionCloud(authB,fsB,artifactsB)
            val owner=cloudA.head().owner;artifactsA.probe(owner);cloudA.enableFullScope()
            assertEquals(2,cloudB.head().protocol)
            SubscriptionSyncStore(a,isolatedDemo=true).bind(owner);SubscriptionSyncStore(b,isolatedDemo=true).bind(owner)
            a.sync().putView(SyncViewEntity("__extended_scope",version=1));b.sync().putView(SyncViewEntity("__extended_scope",version=1))
            val podcast=PodcastEntity("p","https://example.test/content-$tag","Content fixture")
            SubscriptionSyncStore(a,isolatedDemo=true).follow(podcast,true)
            a.library().putEpisode(EpisodeEntity("e","p","guid","Content episode","https://example.test/audio",durationMs=10000,mediaFingerprint="sha",localFile="/private/audio",downloadState="downloaded"))
            a.library().putTranscript(TranscriptEntity("e","r1","sha","tiny","en",state="ready",durationMs=10000,processedMs=10000))
            a.library().putSegments(listOf(SegmentEntity("e","r1",0,10000,"A finished synthetic transcript.")));a.library().putCoverage(CoverageEntity("e","r1",0,10000))
            a.library().putAnalysis(AnalysisEntity("e","r1",state="succeeded",summary="A completed summary",chaptersJson="[]",adsJson="[]",model="test-model"))
            a.library().putMessage(MessageEntity("m1","e","user","A synthetic question",revision="r1",fingerprint="sha"))
            a.library().putMessage(MessageEntity("m2","e","assistant","A completed answer",replyTo="m1",revision="r1",fingerprint="sha"))
            a.library().putMessage(MessageEntity("m3","e","assistant","Incomplete answer",state="running",replyTo="m1",revision="r1",fingerprint="sha"))
            PortableLibrarySync(a,isolatedDemo=true).putMoment(DistillationEntity("moment","e",0,10000,"A finished synthetic transcript.",state="saved",revision="r1",fingerprint="sha"))
            a.library().putResearch(ResearchEntity("research","moment",state="succeeded",report="A finished explanation"))
            CollectionSync(a,isolatedDemo=true).queue(listOf("e"));CollectionSync(a,isolatedDemo=true).preference("language","pt")
            artifactsA.capture()
            assertTrue(SubscriptionSyncCoordinator(a,cloudA).run())
            assertTrue(SubscriptionSyncCoordinator(b,cloudB).run())
            assertEquals(listOf("e"),b.library().queue().map {it.episodeId})
            assertEquals("pt",b.sync().preferences().single().value)
            assertEquals("ready",b.library().transcript("e")!!.state)
            assertEquals("A finished synthetic transcript.",b.library().segments("e","r1").single().text)
            assertEquals("A completed summary",b.library().analysis("e")!!.summary)
            assertEquals(setOf("m1","m2"),b.library().messages().map {it.id}.toSet())
            assertEquals("A finished explanation",b.library().research("research")!!.report)
            assertNull(b.library().episode("e")!!.localFile);assertEquals("none",b.library().episode("e")!!.downloadState)
            assertNull(b.library().playerSession());assertEquals(0,b.sync().count())
            assertEquals(1,a.sync().artifacts(owner.uid,owner.generation).size)
        }} finally {
            a.close();b.close();context.deleteDatabase(nameA);context.deleteDatabase(nameB);dirA.deleteRecursively();dirB.deleteRecursively()
            fsA.terminate().await();fsB.terminate().await();authA.signOut();authB.signOut();appA.delete();appB.delete()
        }
    }
}
