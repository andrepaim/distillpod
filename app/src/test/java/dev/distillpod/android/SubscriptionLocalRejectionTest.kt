package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.firestore.FirebaseFirestoreException
import dev.distillpod.android.data.*
import dev.distillpod.android.sync.*
import dev.distillpod.core.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/** Deterministic local failures of one operation are set aside as rejected instead of blocking the outbox. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SubscriptionLocalRejectionTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val later=PodcastEntity("p2","https://example.test/later","Later",subscribed=true)
    private fun operation(key:String,payload:Map<String,String>)=SyncOutboxEntity(UUID.randomUUID().toString(),"alice","g1",key,0,
        org.json.JSONObject(payload.toSortedMap()).toString(),false)

    @Test fun `an operation failing protocol validation is rejected before any cloud write`() {
        val valid=SubscriptionSyncStore.payload(later)
        assertEquals(valid,commitPayload(operation(SubscriptionSyncStore.key(later),valid)))
        for(invalid in listOf(operation(SubscriptionSyncStore.key(later),valid+("unexpected" to "field")),operation("not a key",valid))) {
            try {commitPayload(invalid);fail()} catch(rejected:SyncOperationRejected) {assertTrue(rejected.cause is IllegalArgumentException)}
        }
    }
    @Test fun `only an invalid argument refusal of staged writes rejects a Firestore commit`() {
        for(code in FirebaseFirestoreException.Code.values().filter {it!=FirebaseFirestoreException.Code.OK}) {
            val error=FirebaseFirestoreException("synthetic",code)
            assertEquals(code.name,code==FirebaseFirestoreException.Code.INVALID_ARGUMENT,firestoreWriteRejected(error,staged=true))
            assertFalse(code.name,firestoreWriteRejected(error,staged=false))
        }
        assertFalse(firestoreWriteRejected(java.io.IOException("synthetic"),staged=true))
    }

    private class Fixture(val db:AppDatabase,val artifacts:ArtifactSync,val objects:ArtifactSyncTest.Objects,val cloud:SubscriptionCoordinatorTest.Cloud) {
        /** Mirrors FirebaseSubscriptionCloud.commit: validate, upload content, then publish the record. */
        val transport=object:SubscriptionCloud {
            override val uid get()=cloud.uid
            override suspend fun head()=cloud.head()
            override suspend fun entities(after:String)=cloud.entities(after)
            override suspend fun changes(after:Long,through:Long)=cloud.changes(after,through)
            override suspend fun prepareContent(owner:SyncOwner,record:CloudSubscription)=artifacts.fetch(owner,record)
            override suspend fun commit(operation:SyncOutboxEntity):CloudSubscription {
                commitPayload(operation)
                if(operation.entityKey.startsWith("artifact_"))artifacts.upload(operation)
                return cloud.commit(operation)
            }
        }
        suspend fun run()=SubscriptionSyncCoordinator(db,transport).run()
        suspend fun states()=db.sync().operations("alice","g1").map {it.entityKey to it.state}
    }
    private fun fixture(block:suspend Fixture.()->Unit)=runBlocking {
        val dir=File(context.cacheDir,"local-rejection-${UUID.randomUUID()}")
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            val store=SubscriptionSyncStore(db)
            store.bind(SyncOwner("alice","g1"));db.sync().putView(SyncViewEntity("__extended_scope",version=1))
            db.library().putPodcast(PodcastEntity("p","https://example.test/feed","Show"));db.sync().approve(SyncPublicFeedEntity("p"))
            db.library().putEpisode(EpisodeEntity("e","p","guid","Episode","https://example.test/audio",mediaFingerprint="sha"))
            db.library().putTranscript(TranscriptEntity("e","r1","sha","tiny","en",state="ready",durationMs=10000))
            db.library().putSegments(listOf(SegmentEntity("e","r1",0,10000,"Finished content")))
            val objects=ArtifactSyncTest.Objects()
            Fixture(db,ArtifactSync(db,ContentFiles(context,dir),objects),objects,SubscriptionCoordinatorTest.Cloud()).block()
        } finally {db.close();dir.deleteRecursively()}
    }
    private suspend fun Fixture.removeLocalTranscript() {
        db.openHelper.writableDatabase.execSQL("DELETE FROM transcripts WHERE episodeId='e'")
        db.library().deleteSegments("e")
    }

    @Test fun `content whose staged file is gone and cannot be rebuilt is rejected and later work continues`()=fixture {
        artifacts.capture()
        val staged=db.sync().artifacts("alice","g1").single()
        removeLocalTranscript();File(staged.localFile).delete()
        artifacts.capture()
        SubscriptionSyncStore(db).follow(later,true)
        assertFalse(run())
        assertEquals(listOf("artifact_e" to "rejected"),states())
        assertNotNull(cloud.rows[SubscriptionSyncStore.key(later)]);assertTrue(objects.rows.isEmpty())
        assertEquals("rejected",db.sync().binding()!!.status)
        // Discarding the refused content leaves the library synced.
        SubscriptionSyncStore(db).discardRejected();assertTrue(run())
    }
    @Test fun `a staged artifact whose identity differs from its operation is rejected`()=fixture {
        artifacts.capture()
        val queued=db.sync().operations("alice","g1").single()
        val changed=SubscriptionSyncStore.decode(queued.payload)+("title" to "Different episode")
        db.sync().acknowledge("alice","g1",queued.operationId)
        db.sync().enqueue(queued.copy(payload=org.json.JSONObject(changed.toSortedMap()).toString()))
        SubscriptionSyncStore(db).follow(later,true)
        assertFalse(run())
        assertEquals(listOf("artifact_e" to "rejected"),states())
        assertNotNull(cloud.rows[SubscriptionSyncStore.key(later)]);assertTrue(objects.rows.isEmpty())
    }
    @Test fun `an interrupted upload of replaced content gives way to the newer content`()=fixture {
        artifacts.capture()
        val old=db.sync().operations("alice","g1").single()
        db.sync().state(old.operationId,"uploading")
        File(db.sync().artifacts("alice","g1").single().localFile).delete()
        db.library().putTranscript(TranscriptEntity("e","r2","sha","tiny","en",state="ready",durationMs=10000))
        db.library().putSegments(listOf(SegmentEntity("e","r2",0,10000,"Newer content")))
        artifacts.capture()
        assertEquals(2,db.sync().forKey("alice","g1","artifact_e").size)
        assertTrue(run())
        assertTrue(db.sync().operations("alice","g1").isEmpty())
        val published=cloud.rows["artifact_e"]!!
        assertEquals(db.sync().view("artifact_e")!!.sessionId,published.payload["hash"])
        assertEquals(1,objects.rows.size)
    }
    @Test fun `staging that local content can rebuild is repaired rather than rejected`()=fixture {
        artifacts.capture()
        File(db.sync().artifacts("alice","g1").single().localFile).delete()
        artifacts.capture()
        assertTrue(run());assertEquals(1,objects.rows.size)
    }
    @Test fun `staging lost after capture is rebuilt by the upload itself`()=fixture {
        artifacts.capture()
        File(db.sync().artifacts("alice","g1").single().localFile).delete()
        assertTrue(run());assertEquals(1,objects.rows.size)
        assertTrue(File(db.sync().artifacts("alice","g1").single().localFile).isFile)
    }
}
