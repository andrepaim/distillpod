package dev.distillpod.android

import android.content.Context
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
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ArtifactSyncTest {
    class Objects:ContentObjects {
        val rows=mutableMapOf<String,ContentArtifact>();var failAfterUpload=false;var reject=false
        override suspend fun put(owner:SyncOwner,artifact:ContentArtifact) {
            if(reject)throw SyncOperationRejected(com.google.firebase.storage.StorageException.fromExceptionAndHttpCode(null,403)!!)
            rows["${owner.uid}/${owner.generation}/${artifact.hash}"]=artifact
            if(failAfterUpload){failAfterUpload=false;throw java.io.IOException("Connection interrupted after object upload")}
        }
        override suspend fun get(owner:SyncOwner,hash:String,expandedBytes:Long)=rows["${owner.uid}/${owner.generation}/$hash"]?:throw java.io.IOException("Missing object")
    }
    private fun fixture(block:suspend(AppDatabase,ArtifactSync,Objects,SubscriptionCoordinatorTest.Cloud)->Unit)=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val dir=File(context.cacheDir,"artifact-${UUID.randomUUID()}")
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build();val objects=Objects()
        try {
            SubscriptionSyncStore(db).bind(SyncOwner("alice","g1"));db.sync().putView(SyncViewEntity("__extended_scope",version=1))
            db.library().putPodcast(PodcastEntity("p","https://example.test/feed","Show"));db.sync().approve(SyncPublicFeedEntity("p"))
            db.library().putEpisode(EpisodeEntity("e","p","guid","Episode","https://example.test/audio",mediaFingerprint="sha"))
            db.library().putTranscript(TranscriptEntity("e","r1","sha","tiny","en",state="ready",durationMs=10000))
            db.library().putSegments(listOf(SegmentEntity("e","r1",0,10000,"Finished content")))
            block(db,ArtifactSync(db,ContentFiles(context,dir),objects),objects,SubscriptionCoordinatorTest.Cloud())
        } finally {db.close();dir.deleteRecursively()}
    }
    private fun transport(cloud:SubscriptionCoordinatorTest.Cloud,artifacts:ArtifactSync)=object:SubscriptionCloud {
        override val uid get()=cloud.uid
        override suspend fun head()=cloud.head()
        override suspend fun entities(after:String)=cloud.entities(after)
        override suspend fun changes(after:Long,through:Long)=cloud.changes(after,through)
        override suspend fun prepareContent(owner:SyncOwner,record:CloudSubscription)=artifacts.fetch(owner,record)
        override suspend fun commit(operation:SyncOutboxEntity):CloudSubscription {artifacts.upload(operation);return cloud.commit(operation)}
    }
    @Test fun `upload is verified before publication and retry does not duplicate content`()=fixture {db,artifacts,objects,cloud ->
        artifacts.capture();objects.failAfterUpload=true
        try {SubscriptionSyncCoordinator(db,transport(cloud,artifacts)).run();fail()}catch(_:java.io.IOException){}
        assertTrue(cloud.rows.isEmpty());assertEquals(1,objects.rows.size)
        assertTrue(SubscriptionSyncCoordinator(db,transport(cloud,artifacts)).run())
        assertEquals(1,objects.rows.size);assertEquals(1,cloud.rows.size);assertEquals("applied",db.sync().artifacts("alice","g1").single().state)
        artifacts.capture();assertEquals(0,db.sync().count())
    }
    @Test fun `corrupt upload staging rebuilds from local content without changing pending identity`()=fixture {db,artifacts,objects,cloud ->
        artifacts.capture()
        val operation=db.sync().operations("alice","g1").single().operationId
        File(db.sync().artifacts("alice","g1").single().localFile).writeText("corrupted staging")
        artifacts.capture()
        assertEquals(operation,db.sync().operations("alice","g1").single().operationId)
        assertTrue(SubscriptionSyncCoordinator(db,transport(cloud,artifacts)).run())
        assertEquals(1,objects.rows.size)
    }
    @Test fun `new local database restores finished content without recomputation`()=fixture {db,artifacts,objects,cloud ->
        artifacts.capture();assertTrue(SubscriptionSyncCoordinator(db,transport(cloud,artifacts)).run())
        val context=ApplicationProvider.getApplicationContext<Context>();val dir=File(context.cacheDir,"restore-${UUID.randomUUID()}")
        val restored=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            SubscriptionSyncStore(restored).bind(SyncOwner("alice","g1"))
            val remoteArtifacts=ArtifactSync(restored,ContentFiles(context,dir),objects)
            assertTrue(SubscriptionSyncCoordinator(restored,transport(cloud,remoteArtifacts)).run())
            assertEquals("ready",restored.library().transcript("e")!!.state)
            assertEquals("Finished content",restored.library().segments("e","r1").single().text)
            assertNull(restored.library().episode("e")!!.localFile)
        } finally {restored.close();dir.deleteRecursively()}
    }
    @Test fun `rejected content upload is retained without leaving sync pending`()=fixture {db,artifacts,objects,cloud ->
        artifacts.capture();objects.reject=true
        assertFalse(SubscriptionSyncCoordinator(db,transport(cloud,artifacts)).run())
        assertEquals("rejected",db.sync().operations("alice","g1").single().state)
        assertEquals("rejected",db.sync().binding()!!.status)
        assertEquals("prepared",db.sync().artifacts("alice","g1").single().state)
        assertTrue(objects.rows.isEmpty());assertTrue(cloud.rows.isEmpty())
        artifacts.capture();assertEquals(1,db.sync().count())
        objects.reject=false;SubscriptionSyncStore(db).retryRejected()
        assertTrue(SubscriptionSyncCoordinator(db,transport(cloud,artifacts)).run())
        assertEquals(1,cloud.rows.size);assertEquals(1,objects.rows.size)
    }
}
