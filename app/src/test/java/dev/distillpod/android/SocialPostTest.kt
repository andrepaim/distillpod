package dev.distillpod.android

import android.content.Context
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The source snapshot every post is made from, and the export directory it is shared through. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SocialPostTest {
    @After fun resetProviderCache() {
        // Robolectric gives each test a new data directory, while FileProvider caches roots statically.
        (FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as MutableMap<*, *>).clear()
    }
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    private suspend fun seed(db: AppDatabase) {
        db.library().putPodcast(PodcastEntity("p", "https://host.test/feed", "A show", websiteUrl = "https://host.test/show"))
        db.library().putEpisode(EpisodeEntity("e", "p", "g", "Episode", "https://host.test/audio", webpageUrl = "https://host.test/episode"))
        db.library().putDistillation(DistillationEntity("m", "e", 1000, 60000, "Small daily routines build lasting skills.", "Small daily routines", "Consistent practice helps us learn.", state = "succeeded"))
    }

    @Test fun `source snapshot survives moment deletion backup and restore unchanged`() = runBlocking {
        val db = database(); val target = database()
        try {
            seed(db)
            val draft = PostRepository(db).create("m")
            val content = PostContent.parse(draft.content)
            assertEquals("A show", content.show); assertEquals("Episode", content.episode)
            assertEquals("Small daily routines build lasting skills.", content.excerpt)
            assertEquals(1000L, content.startMs); assertEquals(60000L, content.endMs)
            assertEquals("Small daily routines", content.quote); assertEquals("Consistent practice helps us learn.", content.takeaway)
            assertEquals("", content.sourceUrl) // Unknown feeds are not automatically public.
            assertEquals("m", draft.sourceMomentId)
            db.library().deleteDistillation("m")
            assertNull(db.library().post(draft.id)!!.sourceMomentId)
            assertEquals(draft.content, db.library().post(draft.id)!!.content)
            val backup = LibraryBackup(target)
            backup.merge(backup.preview(LibraryBackup(db).export().inputStream()))
            val restored = target.library().post(draft.id)!!
            assertEquals(draft.content, restored.content)
            assertNull(restored.sourceMomentId)
            PostRepository(target).delete(draft.id)
            assertNull(target.library().post(draft.id))
        } finally { db.close(); target.close() }
    }

    @Test fun `snapshot quotes must come from the excerpt`() {
        try { PostContent(excerpt = "Original", quote = "Invented").validated(); fail() } catch (_: IllegalArgumentException) {}
        try { PostContent(sourceUrl = "https://user:pass@host.test/ep").validated(); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun `provider exposes only exports`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File(context.cacheDir, "post-exports/test.png").apply { parentFile!!.mkdirs(); writeText("fixture") }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.post-files", file)
        assertEquals("content", uri.scheme)
        for (private in listOf(java.io.File(context.filesDir, "private.db"), java.io.File(context.filesDir, "post-images/a.png"), java.io.File(context.cacheDir, "other.png"))) {
            try { FileProvider.getUriForFile(context, "${context.packageName}.post-files", private); fail("exposed $private") } catch (_: IllegalArgumentException) {}
        }
    }
}
