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

/** Capture serializes a transcript snapshot only when its revision or portable rows changed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ArtifactCaptureCacheTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private class Fixture(val name:String,val dir:File,val objects:ArtifactSyncTest.Objects) {
        lateinit var db:AppDatabase
        fun open(context:Context){db=Room.databaseBuilder(context,AppDatabase::class.java,name).build()}
        fun artifacts(context:Context)=ArtifactSync(db,ContentFiles(context,dir),objects)
    }
    private fun fixture(block:suspend Fixture.()->Unit)=runBlocking {
        val f=Fixture("capture-${UUID.randomUUID()}.db",File(context.cacheDir,"capture-${UUID.randomUUID()}"),ArtifactSyncTest.Objects())
        f.open(context)
        try {
            SubscriptionSyncStore(f.db).bind(SyncOwner("alice","g1"));f.db.sync().putView(SyncViewEntity("__extended_scope",version=1))
            f.db.library().putPodcast(PodcastEntity("p","https://example.test/feed","Show"));f.db.sync().approve(SyncPublicFeedEntity("p"))
            transcript(f.db,"e","r1","Finished content")
            f.block()
        } finally {f.db.close();context.deleteDatabase(f.name);f.dir.deleteRecursively()}
    }
    private suspend fun transcript(db:AppDatabase,id:String,revision:String,vararg text:String) {
        if(db.library().episode(id)==null)db.library().putEpisode(EpisodeEntity(id,"p","guid-$id","Episode $id","https://example.test/$id",mediaFingerprint="sha-$id"))
        db.library().deleteSegments(id);db.library().deleteCoverage(id)
        db.library().putTranscript(TranscriptEntity(id,revision,"sha-$id","tiny","en",state="ready",durationMs=10000,processedMs=10000))
        db.library().putSegments(text.mapIndexed {i,t->SegmentEntity(id,revision,i*1000L,i*1000L+1000,t,i)})
        db.library().putCoverage(CoverageEntity(id,revision,0,10000))
    }
    private fun operations(db:AppDatabase,key:String)=runBlocking {db.sync().forKey("alice","g1",key)}
    /** The staged and enqueued artifact always equals what a full rebuild produces now. */
    private suspend fun assertCurrent(db:AppDatabase) {
        for(t in db.library().transcripts().filter {it.state=="ready"}) {
            val fresh=FinishedContent(db).snapshot(t.episodeId)!!
            assertEquals(fresh.hash,db.sync().view("artifact_${t.episodeId}")!!.sessionId)
            assertEquals(fresh.hash,SubscriptionSyncStore.decode(operations(db,"artifact_${t.episodeId}").last().payload)["hash"])
        }
    }

    @Test fun `unchanged transcripts are not serialized again on later runs`()=fixture {
        val artifacts=artifacts(context)
        assertEquals(ArtifactSync.Capture(rebuilt=1,unchanged=0),artifacts.capture())
        val operation=operations(db,"artifact_e").single().operationId
        repeat(3) {assertEquals(ArtifactSync.Capture(rebuilt=0,unchanged=1),artifacts.capture())}
        assertEquals(operation,operations(db,"artifact_e").single().operationId)
        assertCurrent(db)
    }
    @Test fun `the cache survives a process restart over the same database and staging files`()=fixture {
        artifacts(context).capture()
        val operation=operations(db,"artifact_e").single().operationId
        db.close();open(context)
        assertEquals(ArtifactSync.Capture(rebuilt=0,unchanged=1),artifacts(context).capture())
        assertEquals(operation,operations(db,"artifact_e").single().operationId)
        assertCurrent(db)
    }
    @Test fun `a changed transcript rebuilds only its own snapshot`()=fixture {
        val artifacts=artifacts(context)
        artifacts.capture()
        transcript(db,"e2","x1","Second episode")
        assertEquals(ArtifactSync.Capture(rebuilt=1,unchanged=1),artifacts.capture());assertCurrent(db)
        // A new revision of the first transcript.
        transcript(db,"e","r2","Replacement text")
        assertEquals(ArtifactSync.Capture(rebuilt=1,unchanged=1),artifacts.capture());assertCurrent(db)
        // Rows appended to an existing revision.
        db.library().putSegments(listOf(SegmentEntity("e","r2",5000,6000,"More",1)))
        assertEquals(ArtifactSync.Capture(rebuilt=1,unchanged=1),artifacts.capture());assertCurrent(db)
        // Portable outputs of the same revision.
        db.library().putAnalysis(AnalysisEntity("e2","x1",state="succeeded",summary="Summary"))
        assertEquals(ArtifactSync.Capture(rebuilt=1,unchanged=1),artifacts.capture());assertCurrent(db)
        db.library().putMessage(MessageEntity("m1","e","user","Question?",revision="r2",fingerprint="sha-e"))
        assertEquals(ArtifactSync.Capture(rebuilt=1,unchanged=1),artifacts.capture());assertCurrent(db)
        db.library().putDistillation(DistillationEntity("d1","e2",0,1000,revision="x1",fingerprint="sha-e2",state="succeeded"))
        db.library().putResearch(ResearchEntity("rs1","d1",state="succeeded",report="Report"))
        assertEquals(ArtifactSync.Capture(rebuilt=1,unchanged=1),artifacts.capture());assertCurrent(db)
        // Portable identity metadata.
        db.library().putEpisode(db.library().episode("e")!!.copy(title="Renamed"))
        assertEquals(ArtifactSync.Capture(rebuilt=1,unchanged=1),artifacts.capture());assertCurrent(db)
        assertEquals(ArtifactSync.Capture(rebuilt=0,unchanged=2),artifacts.capture())
    }
    @Test fun `a deleted transcript is not captured and an identical restore is not uploaded again`()=fixture {
        val artifacts=artifacts(context)
        transcript(db,"e2","x1","Second episode")
        artifacts.capture()
        val operation=operations(db,"artifact_e2").single().operationId
        // Local transcript removal deletes the transcript, segments and coverage.
        db.openHelper.writableDatabase.execSQL("DELETE FROM transcripts WHERE episodeId='e2'")
        db.library().deleteSegments("e2");db.library().deleteCoverage("e2")
        assertEquals(ArtifactSync.Capture(rebuilt=0,unchanged=1),artifacts.capture())
        assertEquals(operation,operations(db,"artifact_e2").single().operationId)
        transcript(db,"e2","x1","Second episode")
        assertEquals(ArtifactSync.Capture(rebuilt=0,unchanged=2),artifacts.capture())
        assertEquals(operation,operations(db,"artifact_e2").single().operationId)
        assertCurrent(db)
        // Restoring different content for the same episode is a change.
        transcript(db,"e2","x2","Different text")
        assertEquals(ArtifactSync.Capture(rebuilt=1,unchanged=1),artifacts.capture());assertCurrent(db)
    }
    @Test fun `a different artifact shown for the episode or lost staging forces a rebuild`()=fixture {
        val artifacts=artifacts(context)
        artifacts.capture()
        val staged=db.sync().view("artifact_e")!!
        db.sync().putView(staged.copy(sessionId="0".repeat(64)))
        assertEquals(ArtifactSync.Capture(rebuilt=1,unchanged=0),artifacts.capture());assertCurrent(db)
        File(db.sync().artifacts("alice","g1").single().localFile).delete()
        assertEquals(ArtifactSync.Capture(rebuilt=1,unchanged=0),artifacts.capture());assertCurrent(db)
        File(db.sync().artifacts("alice","g1").single().localFile).writeText("corrupted staging")
        assertEquals(ArtifactSync.Capture(rebuilt=1,unchanged=0),artifacts.capture());assertCurrent(db)
        assertEquals(ArtifactSync.Capture(rebuilt=0,unchanged=1),artifacts.capture())
    }
    @Test fun `snapshots that cannot be shared are neither built nor cached`()=fixture {
        val artifacts=artifacts(context)
        db.library().putEpisode(db.library().episode("e")!!.copy(mediaFingerprint="other"))
        assertEquals(ArtifactSync.Capture(),artifacts.capture())
        assertTrue(operations(db,"artifact_e").isEmpty())
        db.library().putEpisode(db.library().episode("e")!!.copy(mediaFingerprint="sha-e"))
        assertEquals(ArtifactSync.Capture(rebuilt=1,unchanged=0),artifacts.capture());assertCurrent(db)
    }
}
