package dev.distillpod.android
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryStoreTest {
    @Test fun `library and pending distillations survive reopening`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("distillpod.db")
        LibraryStore(context).use { store ->
            store.saveEpisode(StoredEpisode("id", "feed", "Episode", "https://example.com/audio.mp3"))
            store.saveProgress("id", 12345)
            store.saveDistillation("gist", "id", 12345, "An excerpt", null)
        }
        LibraryStore(context).use { store ->
            assertEquals("Episode", store.episodes().single().title)
            assertEquals(12345L, store.progress("id"))
            assertEquals("An excerpt", store.distillations().single().excerpt)
            assertNull(store.distillations().single().insight)
        }
    }
    @Test fun `importing same episode preserves listening position`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("distillpod.db")
        LibraryStore(context).use { store ->
            val ep = StoredEpisode("id", "feed", "Episode", "https://example.com/a.mp3")
            store.saveEpisode(ep); store.saveProgress("id", 2000); store.saveEpisode(ep.copy(title = "Updated"))
            assertEquals(2000L, store.progress("id"))
            assertEquals(1, store.episodes().size)
        }
    }
}
