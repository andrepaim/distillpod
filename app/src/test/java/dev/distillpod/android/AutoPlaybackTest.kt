package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.auto.AutoPlayback
import dev.distillpod.android.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class AutoPlaybackTest {
    private fun fixture(block:suspend (AppDatabase,AutoPlayback)->Unit)=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java).build()
        try {
            db.library().putPodcast(PodcastEntity("pod","https://example.com/feed","Café Ciência"))
            db.library().putEpisodes(listOf(
                EpisodeEntity("a","pod","a","Older science","https://example.com/a",publishedAt=1,positionMs=15000,lastPlayedAt=5),
                EpisodeEntity("b","pod","b","Latest discovery","https://example.com/b",publishedAt=2,positionMs=0),
                EpisodeEntity("c","pod","c","Finished science","https://example.com/c",publishedAt=3,completed=true,positionMs=60000,durationMs=60000)))
            db.library().insertQueue(listOf(QueueEntryEntity("a",0),QueueEntryEntity("b",1)))
            block(db,AutoPlayback(db))
        } finally {db.close()}
    }
    @Test fun `selecting queued episode preserves order and resumes`()=fixture { _,p ->
        val result=p.select("a",listOf("b","a"))
        assertEquals(listOf("b","a"),result.items.map {it.mediaId});assertEquals(1,result.index);assertEquals(15000L,result.position)
    }
    @Test fun `new selection prepends and removes stale duplicate queue entries`()=fixture { _,p ->
        val result=p.select("c",listOf("a","missing","a"))
        assertEquals(listOf("c","a"),result.items.map {it.mediaId});assertEquals(0L,result.position)
    }
    @Test fun `search folds accents and show request chooses unfinished episode`()=fixture { _,p ->
        assertEquals("a",p.voice("CAFE CIENCIA"))
        assertEquals("b",p.voice("Latest discovery"))
        assertEquals(3,p.search("cafe ciencia",0,100).size)
    }
    @Test fun `no match preserves playback and empty query restores session then queue`()=fixture { db,p ->
        assertNull(p.voice("unrelated nonsense"));assertEquals("a",p.voice(""))
        db.library().putPlayerSession(PlayerSessionEntity(episodeId="b"))
        assertEquals("b",p.voice(""))
    }
    @Test fun `generic request reaches unplayed episodes beyond the first hundred`()=fixture {db,p ->
        db.library().clearQueue()
        db.library().putEpisodes((1..110).map {EpisodeEntity("done$it","pod","done$it","Completed","https://example.com/$it",publishedAt=it.toLong()+10,completed=true)})
        assertEquals("b",p.voice(""))
    }
    @Test fun `search pagination is bounded and stale selection fails`()=fixture { _,p ->
        assertEquals(1,p.search("science",0,1).size)
        assertEquals(1,p.search("science",1,1).size)
        assertTrue(p.search("science",-1,1).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {runBlocking {p.select("missing",emptyList())}}
    }
}
