package dev.distillpod.android

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import dev.distillpod.android.sync.*
import dev.distillpod.core.sync.ContentArtifacts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinishedContentTest {
    private fun fixture(block:suspend(AppDatabase,FinishedContent)->Unit)=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(),AppDatabase::class.java).build()
        try {
            db.library().putPodcast(PodcastEntity("p","https://example.test/feed","Show"));db.sync().approve(SyncPublicFeedEntity("p"))
            db.library().putEpisode(EpisodeEntity("e","p","guid","Episode","https://example.test/audio",mediaFingerprint="sha"))
            db.library().putTranscript(TranscriptEntity("e","r1","sha","tiny","en",state="ready",durationMs=10000,processedMs=10000))
            db.library().putSegments(listOf(SegmentEntity("e","r1",0,10000,"Completed transcript")));db.library().putCoverage(CoverageEntity("e","r1",0,10000))
            block(db,FinishedContent(db))
        } finally {db.close()}
    }
    @Test fun `completed transcript produces portable artifact with timings and provenance`()=fixture {_,content ->
        val artifact=content.snapshot("e");assertNotNull(artifact)
        val value=JSONObject(ContentArtifacts.decode(artifact!!).toString(Charsets.UTF_8))
        assertEquals("r1",value.getJSONObject("transcript").getString("revision"))
        assertEquals("sha",value.getJSONObject("transcript").getString("fingerprint"))
        assertEquals("Completed transcript",value.getJSONArray("segments").getJSONObject(0).getString("text"))
        assertFalse(value.toString().contains("localCreatedAt"))
        assertFalse(value.toString().contains("localDownloadedAt"))
    }
    @Test fun `fresh library restores finished content without computation or credentials`()=fixture {_,content ->
        val artifact=content.snapshot("e")!!
        val restored=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(),AppDatabase::class.java).build()
        try {
            val beforeImport = System.currentTimeMillis()
            assertTrue(FinishedContent(restored).restore(artifact))
            assertTrue(restored.library().transcript("e")!!.localCreatedAt in beforeImport..System.currentTimeMillis())
            assertEquals(0L, restored.library().episode("e")!!.localDownloadedAt)
            assertEquals("ready",restored.library().transcript("e")!!.state)
            assertEquals("Completed transcript",restored.library().segments("e","r1").single().text)
            assertNull(restored.library().episode("e")!!.localFile)
            assertTrue(restored.library().queue().isEmpty())
        } finally {restored.close()}
    }
    @Test fun `different local audio fingerprint never receives old transcript timings`()=fixture {db,content ->
        val artifact=content.snapshot("e")!!
        db.library().putEpisode(db.library().episode("e")!!.copy(mediaFingerprint="changed"))
        assertFalse(content.restore(artifact))
        assertEquals("sha",db.library().transcript("e")!!.fingerprint)
    }
    @Test fun `unfinished transcript checkpoints never become finished content`()=fixture {db,content ->
        db.library().putTranscript(db.library().transcript("e")!!.copy(state="processing"))
        assertNull(content.snapshot("e"))
    }
    @Test fun `finished analysis and conversation branches omit job states errors and attempts`()=fixture {db,content ->
        db.library().putAnalysis(AnalysisEntity("e","r1",state="succeeded",summary="Saved summary",attempt="private-attempt",error="private-error"))
        db.library().putMessage(MessageEntity("m1","e","user","Question",revision="r1",fingerprint="sha"))
        db.library().putMessage(MessageEntity("m2","e","assistant","Saved answer",revision="r1",fingerprint="sha",replyTo="m1"))
        db.library().putMessage(MessageEntity("m3","e","assistant","Partial secret answer",state="running",revision="r1",fingerprint="sha",replyTo="m1"))
        val value=ContentArtifacts.decode(content.snapshot("e")!!).toString(Charsets.UTF_8)
        assertTrue(value.contains("Saved summary"));assertTrue(value.contains("Saved answer"))
        assertFalse(value.contains("private-attempt"));assertFalse(value.contains("private-error"));assertFalse(value.contains("Partial secret answer"))
    }
    @Test fun `private media and mismatched audio content remain local`()=fixture {db,content ->
        db.library().putEpisode(db.library().episode("e")!!.copy(audioUrl="https://example.test/audio?secret=token"))
        assertNull(content.snapshot("e"))
    }
}
