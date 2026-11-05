package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.auto.AutoCatalog
import dev.distillpod.android.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoCatalogTest {
    private fun fixture(block: suspend (AppDatabase, AutoCatalog) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.library().putPodcast(PodcastEntity("show", "https://example.com/feed", "Café Science"))
            db.library().putPodcast(PodcastEntity("hidden", "https://example.com/hidden", "Hidden", subscribed=false))
            db.library().putEpisode(EpisodeEntity("old", "show", "old", "First", "https://example.com/old", publishedAt=1, positionMs=2000, lastPlayedAt=20))
            db.library().putEpisode(EpisodeEntity("new", "show", "new", "Latest", "https://example.com/new", publishedAt=2, positionMs=3000, lastPlayedAt=10))
            db.library().putEpisode(EpisodeEntity("done", "show", "done", "Finished", "https://example.com/done", publishedAt=3, completed=true))
            block(db, AutoCatalog(db))
        } finally { db.close() }
    }
    @Test fun `root contains four browsable sections`() = fixture { _, c ->
        val rows=c.children(AutoCatalog.ROOT,0,20)
        assertEquals(listOf("Continue listening","Queue","Downloads","Podcasts"),rows.map { it.mediaMetadata.title.toString() })
        assertTrue(rows.all { it.mediaMetadata.isBrowsable==true && it.mediaMetadata.isPlayable==false })
    }
    @Test fun `continue excludes completed and prioritizes current episode`() = fixture { db,c ->
        db.library().putPlayerSession(PlayerSessionEntity(episodeId="new"))
        assertEquals(listOf("new","old"),c.children(AutoCatalog.CONTINUE,0,20).map { it.mediaId })
    }
    @Test fun `queue keeps user order and metadata is playable`() = fixture { db,c ->
        db.library().insertQueue(listOf(QueueEntryEntity("old",1),QueueEntryEntity("new",0)))
        val rows=c.children(AutoCatalog.QUEUE,0,20)
        assertEquals(listOf("new","old"),rows.map { it.mediaId })
        assertTrue(rows.all { it.mediaMetadata.isPlayable==true && it.mediaMetadata.isBrowsable==false })
        assertEquals("Café Science",rows.first().mediaMetadata.artist.toString())
    }
    @Test fun `only subscribed shows appear and show episodes paginate without overlap`() = fixture { _,c ->
        val shows=c.children(AutoCatalog.PODCASTS,0,20)
        assertEquals(1,shows.size)
        val id=shows.single().mediaId
        assertEquals(listOf("done","new"),c.children(id,0,2).map { it.mediaId })
        assertEquals(listOf("old"),c.children(id,1,2).map { it.mediaId })
    }
    @Test fun `downloads require an existing usable file`() = fixture { db,c ->
        val f=File.createTempFile("auto-audio",".mp3");f.writeBytes(byteArrayOf(1))
        try {
            db.library().putEpisode(db.library().episode("new")!!.copy(localFile=f.path,downloadState="downloaded"))
            db.library().putEpisode(db.library().episode("old")!!.copy(localFile=f.path+"missing",downloadState="downloaded"))
            assertEquals(listOf("new"),c.children(AutoCatalog.DOWNLOADS,0,20).map { it.mediaId })
        } finally { f.delete() }
    }
    @Test fun `legacy unbounded request still receives a bounded root`() = fixture { _,c ->
        assertEquals(4,c.children(AutoCatalog.ROOT,0,Int.MAX_VALUE).size)
    }
    @Test fun `legacy browse reaches every episode through bounded more folders`() = fixture { db,c ->
        db.library().putEpisodes((1..205).map { EpisodeEntity("bulk$it","show","bulk$it","Episode $it","https://example.com/$it",publishedAt=it.toLong()+10) })
        val ids=mutableListOf<String>();var folder=AutoCatalog.SHOW+"show"
        var pages=0
        while(true) {
            assertTrue(++pages<=4)
            val rows=c.legacyChildren(folder)
            assertTrue(rows.size<=100)
            ids+=rows.filter { it.mediaMetadata.isPlayable==true }.map { it.mediaId }
            val more=rows.firstOrNull { it.mediaMetadata.isBrowsable==true } ?: break
            folder=more.mediaId
        }
        assertEquals(208,ids.size)
        assertEquals(208,ids.distinct().size)
    }
    @Test fun `continuation folders also work when browser switches to normal paging`() = fixture { db,c ->
        db.library().putEpisodes((1..105).map { EpisodeEntity("batch$it","show","batch$it","Episode $it","https://example.com/$it",publishedAt=it.toLong()+10) })
        val more=c.legacyChildren(AutoCatalog.SHOW+"show").last()
        assertTrue(more.mediaMetadata.isBrowsable==true)
        assertTrue(c.children(more.mediaId,0,100).isNotEmpty())
    }
    @Test fun `invalid folders and pages return empty without overflow`() = fixture { _,c ->
        assertTrue(c.children("bad",0,20).isEmpty())
        assertTrue(c.children(AutoCatalog.PODCASTS,-1,20).isEmpty())
        assertTrue(c.children(AutoCatalog.PODCASTS,Int.MAX_VALUE,Int.MAX_VALUE).isEmpty())
        assertNull(c.item("missing"))
    }
}
