package dev.distillpod.android

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import dev.distillpod.android.sync.*
import dev.distillpod.core.episodeId
import dev.distillpod.core.sync.SyncOwner
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #250: one installation only streamed an episode (provisional enclosure fingerprint, #245) and
 * another downloaded it (file hash). Synced work is accepted both ways; different files are not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SyncedSourceIdentityTest {
    private val audio="https://example.test/audio"
    private val provisional=episodeId("enclosure",audio)

    private fun database()=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(),AppDatabase::class.java).build()
    private fun phones(block:suspend(streamed:AppDatabase,downloaded:AppDatabase)->Unit)=runBlocking {
        val streamed=database();val downloaded=database()
        try {
            library(streamed,provisional);library(downloaded,"file-hash")
            block(streamed,downloaded)
        } finally {streamed.close();downloaded.close()}
    }
    private suspend fun library(db:AppDatabase,fingerprint:String,url:String=audio) {
        SubscriptionSyncStore(db).bind(SyncOwner("alice","g1"))
        db.library().putPodcast(PodcastEntity("p","https://example.test/feed","Show"));db.sync().approve(SyncPublicFeedEntity("p"))
        val downloaded=fingerprint!=episodeId("enclosure",url)
        db.library().putEpisode(EpisodeEntity("e","p","guid","Episode",url,durationMs=10000,mediaFingerprint=fingerprint,
            localFile=if(downloaded)"/local/audio" else null,downloadState=if(downloaded)"downloaded" else "none"))
    }
    private suspend fun finished(db:AppDatabase,fingerprint:String) {
        db.library().putTranscript(TranscriptEntity("e","r1",fingerprint,"tiny","en",state="ready",durationMs=10000,processedMs=10000))
        db.library().putSegments(listOf(SegmentEntity("e","r1",0,10000,"Completed transcript")));db.library().putCoverage(CoverageEntity("e","r1",0,10000))
        db.library().putMessage(MessageEntity("m1","e","user","Question",revision="r1",fingerprint=fingerprint))
    }
    private fun moment(id:String,fingerprint:String)=DistillationEntity(id,"e",1000,5000,"Source words",quote="Source",insight="Saved idea",
        revision="r1",fingerprint=fingerprint,state="succeeded",model="model")

    @Test fun `content made before the first download is accepted where the episode has its file hash`()=phones {streamed,downloaded ->
        finished(streamed,provisional)
        val artifact=FinishedContent(streamed).snapshot("e")!!
        assertTrue(FinishedContent(downloaded).restore(artifact))
        val dao=downloaded.library()
        assertEquals("file-hash",dao.episode("e")!!.mediaFingerprint)
        assertEquals("file-hash",dao.transcript("e")!!.fingerprint)
        assertEquals("ready",dao.transcript("e")!!.state)
        assertEquals("file-hash",dao.message("m1")!!.fingerprint)
        assertEquals("r1",EpisodeTextResolver(downloaded).complete("e")!!.revision)
    }
    @Test fun `a streaming-only library adopts the file hash of downloaded content for the same enclosure`()=phones {streamed,downloaded ->
        finished(downloaded,"file-hash")
        val artifact=FinishedContent(downloaded).snapshot("e")!!
        streamed.library().putDistillation(moment("own",provisional).copy(attempt="a"))
        streamed.library().putMomentAttempt(MomentAttemptEntity("a","own",JSONObject().put("revision","r1").put("fingerprint",provisional).toString()))
        streamed.library().putDistillation(moment("unverified","unverified:e"))
        assertTrue(FinishedContent(streamed).restore(artifact))
        val dao=streamed.library()
        assertEquals("file-hash",dao.episode("e")!!.mediaFingerprint)
        assertEquals("r1",EpisodeTextResolver(streamed).complete("e")!!.revision)
        assertEquals("file-hash",dao.distillation("own")!!.fingerprint)
        assertEquals("file-hash",JSONObject(dao.momentAttempt("a")!!.contextJson).getString("fingerprint"))
        assertEquals("unverified:e",dao.distillation("unverified")!!.fingerprint)
        // Both libraries now build the same artifact, so neither sends it back as a change.
        assertEquals(artifact.hash,FinishedContent(streamed).snapshot("e")!!.hash)
        assertEquals(0,streamed.sync().count())
    }
    @Test fun `content from a different file or enclosure is still rejected`()=phones {streamed,downloaded ->
        val other=database()
        try {
            library(other,"other-file-hash")
            finished(other,"other-file-hash")
            assertFalse(FinishedContent(downloaded).restore(FinishedContent(other).snapshot("e")!!))
            assertNull(downloaded.library().transcript("e"))
            val moved=database()
            try {
                library(moved,"new-file-hash","https://example.test/new-audio")
                finished(moved,"new-file-hash")
                assertFalse(FinishedContent(streamed).restore(FinishedContent(moved).snapshot("e")!!))
                assertEquals(provisional,streamed.library().episode("e")!!.mediaFingerprint)
                assertNull(streamed.library().transcript("e"))
            } finally {moved.close()}
        } finally {other.close()}
    }
    @Test fun `a moment saved while streaming is not sent to source review where the episode was downloaded`()=phones {streamed,downloaded ->
        val cloud=SubscriptionCoordinatorTest.Cloud()
        PortableLibrarySync(streamed).putMoment(moment("moment",provisional))
        assertTrue(SubscriptionSyncCoordinator(streamed,cloud).run())
        assertTrue(SubscriptionSyncCoordinator(downloaded,cloud).run())
        assertEquals("file-hash",downloaded.library().distillation("moment")!!.fingerprint)
        assertEquals("file-hash",downloaded.library().episode("e")!!.mediaFingerprint)
    }
    @Test fun `a moment saved from a downloaded file is not sent to source review where the episode was streamed`()=phones {streamed,downloaded ->
        val cloud=SubscriptionCoordinatorTest.Cloud()
        PortableLibrarySync(downloaded).putMoment(moment("moment","file-hash"))
        PortableLibrarySync(downloaded).putMoment(moment("unverified","unverified:e"))
        assertTrue(SubscriptionSyncCoordinator(downloaded,cloud).run())
        assertTrue(SubscriptionSyncCoordinator(streamed,cloud).run())
        assertEquals("file-hash",streamed.library().episode("e")!!.mediaFingerprint)
        assertEquals("file-hash",streamed.library().distillation("moment")!!.fingerprint)
        assertEquals("unverified:e",streamed.library().distillation("unverified")!!.fingerprint)
    }
    @Test fun `a moment edited on the downloaded library returns to the streamed one without a conflict`()=phones {streamed,downloaded ->
        val cloud=SubscriptionCoordinatorTest.Cloud()
        PortableLibrarySync(streamed).putMoment(moment("moment",provisional))
        PortableLibrarySync(streamed).putMoment(moment("other",provisional))
        assertTrue(SubscriptionSyncCoordinator(streamed,cloud).run())
        assertTrue(SubscriptionSyncCoordinator(downloaded,cloud).run())
        PortableLibrarySync(downloaded).putMoment(downloaded.library().distillation("moment")!!.copy(insight="Edited idea"))
        assertTrue(SubscriptionSyncCoordinator(downloaded,cloud).run())
        assertTrue(SubscriptionSyncCoordinator(streamed,cloud).run())
        val dao=streamed.library()
        assertEquals("file-hash",dao.episode("e")!!.mediaFingerprint)
        assertEquals("Edited idea",dao.distillation("moment")!!.insight)
        assertEquals("file-hash",dao.distillation("moment")!!.fingerprint)
        assertEquals("file-hash",dao.distillation("other")!!.fingerprint)
        // Adoption during a pull queues nothing that could conflict with the projected records.
        assertEquals(0,streamed.sync().count())
    }
    @Test fun `a moment from a different file stays in source review`()=phones {streamed,downloaded ->
        val cloud=SubscriptionCoordinatorTest.Cloud()
        val other=database()
        try {
            library(other,"other-file-hash")
            PortableLibrarySync(other).putMoment(moment("moment","other-file-hash"))
            assertTrue(SubscriptionSyncCoordinator(other,cloud).run())
            assertTrue(SubscriptionSyncCoordinator(downloaded,cloud).run())
            assertEquals("other-file-hash",downloaded.library().distillation("moment")!!.fingerprint)
            assertEquals("file-hash",downloaded.library().episode("e")!!.mediaFingerprint)
        } finally {other.close()}
    }
    @Test fun `an unverified moment alone never changes a streaming-only library's audio identity`()=phones {streamed,downloaded ->
        val cloud=SubscriptionCoordinatorTest.Cloud()
        PortableLibrarySync(downloaded).putMoment(moment("unverified","unverified:e"))
        assertTrue(SubscriptionSyncCoordinator(downloaded,cloud).run())
        assertTrue(SubscriptionSyncCoordinator(streamed,cloud).run())
        assertEquals(provisional,streamed.library().episode("e")!!.mediaFingerprint)
        assertEquals("unverified:e",streamed.library().distillation("unverified")!!.fingerprint)
    }
}
