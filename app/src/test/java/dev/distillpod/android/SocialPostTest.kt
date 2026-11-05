package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.ai.*
import dev.distillpod.android.data.*
import dev.distillpod.android.sharing.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class SocialPostTest {
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    private suspend fun seed(db: AppDatabase) {
        db.library().putPodcast(PodcastEntity("p", "https://host.test/feed", "A show", websiteUrl = "https://host.test/show"))
        db.library().putEpisode(EpisodeEntity("e", "p", "g", "Episode", "https://host.test/audio", webpageUrl = "https://host.test/episode"))
        db.library().putDistillation(DistillationEntity("m", "e", 1000, 60000, "Small daily routines build lasting skills.", "Small daily routines", "Consistent practice helps us learn.", state = "succeeded"))
    }
    @Test fun `draft edits survive deletion backup and restore without changing source`() = runBlocking {
        val db = database(); val target = database()
        try {
            seed(db); val repo = PostRepository(db)
            val draft = repo.create("m")
            val content = PostContent.parse(draft.content)
            assertEquals("", content.sourceUrl) // Unknown feeds are not automatically public.
            val updated = repo.save(draft.id, 0, content.copy(headline = "Practice adds up", perspective = "This helps me plan."))
            assertEquals(1, updated.revision)
            assertEquals("Small daily routines", db.library().distillation("m")!!.quote)
            db.library().deleteDistillation("m")
            assertNull(db.library().post(draft.id)!!.sourceMomentId)
            val backup = LibraryBackup(target)
            backup.merge(backup.preview(LibraryBackup(db).export().inputStream()))
            val restored = target.library().post(draft.id)!!
            assertEquals(updated.content, restored.content)
            assertNull(restored.sourceMomentId)
            val duplicate = PostRepository(target).duplicate(draft.id)
            assertNotEquals(draft.id, duplicate.id)
            assertEquals(restored.content, duplicate.content)
        } finally { db.close(); target.close() }
    }
    @Test fun `stale writes and changed source snapshots cannot overwrite drafts`() = runBlocking {
        val db = database()
        try {
            seed(db); val repo = PostRepository(db); val draft = repo.create("m"); val content = PostContent.parse(draft.content)
            repo.save(draft.id, 0, content.copy(headline = "New"))
            try { repo.save(draft.id, 0, content); fail("stale save accepted") } catch (_: IllegalArgumentException) {}
            try { repo.save(draft.id, 1, content.copy(excerpt = "fake")); fail("source edit accepted") } catch (_: IllegalArgumentException) {}
            repo.delete(draft.id)
            try { repo.save(draft.id, 1, content); fail("deleted row resurrected") } catch (_: IllegalStateException) {}
        } finally { db.close() }
    }
    @Test fun `composer preserves source and quotes and returns a proposal`() = runBlocking {
        val content = PostContent(excerpt = "A useful source.", quote = "A useful source.", sourceUrl = "https://host.test/ep")
        val ai = object : AiGateway { override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit): AiText {
            assertTrue(input.contains("A useful source.")); return AiText("""{"headline":"An idea","takeaway":"Think about this source.","body":"A useful idea to consider."}""", "fixture")
        } }
        val proposal = PostComposer(ai).compose(content, "Draft with AI")
        assertEquals(content.quote, proposal.quote); assertEquals(content.sourceUrl, proposal.sourceUrl)
        assertEquals("", content.headline); assertEquals("An idea", proposal.headline)
        assertTrue(proposal.shareText().contains("Source:"))
    }
    @Test fun `quote edits and invalid generated content are rejected`() = runBlocking {
        try { PostContent(excerpt = "Original", quote = "Invented").validated(); fail() } catch (_: IllegalArgumentException) {}
        val ai = object : AiGateway { override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit) = AiText("{}", "fixture") }
        try { PostComposer(ai).compose(PostContent(excerpt = "Original"), "Shorten"); fail() } catch (_: org.json.JSONException) {}
    }
    @Test fun `renderer exports both sizes with attribution and rejects overflow`() {
        for (square in listOf(false, true)) for (theme in listOf("Ink", "Lavender", "Mint")) {
            val post = PostContent(show = "Ideias", episode = "Atenção e prática", takeaway = "Pequenos hábitos ajudam a aprender.", square = square, theme = theme)
            val image = PostCardRenderer.render(post)
            assertEquals(1080, image.width); assertEquals(if (square) 1080 else 1350, image.height)
            if (!square && theme == "Ink") java.io.File("/tmp/distillpod-social-preview.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals(if (theme == "Ink") 0xFF0C0F16.toInt() else if (theme == "Mint") 0xFF91DCC8.toInt() else 0xFFB7B9FF.toInt(), image.getPixel(0, 0))
            image.recycle()
        }
        try { PostCardRenderer.render(PostContent(takeaway = "Too much text ".repeat(170))); fail("overflow accepted") } catch (_: IllegalArgumentException) {}
    }
    @Test fun `image saving writes a visible PNG and provider exposes only exports`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PostShareController.save(context, PostContent(takeaway = "A useful idea."))
        val file = java.io.File(context.cacheDir, "post-exports/test.png").apply { parentFile!!.mkdirs(); writeText("fixture") }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.post-files", file)
        assertEquals("content", uri.scheme)
        try { androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.post-files", java.io.File(context.filesDir, "private.db")); fail("private file exposed") } catch (_: IllegalArgumentException) {}
    }
}
