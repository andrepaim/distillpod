package dev.distillpod.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import dev.distillpod.android.data.*
import dev.distillpod.android.features.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Disposable-emulator acceptance; no social publication or AI requests. */
class SocialPostDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun createEditPreviewAndRecoverDetachedDraftWithLargeText() = runBlocking<Unit> {
        val graph = compose.activity.applicationContext.appGraph
        graph.ready.await()
        val dao = graph.db.library()
        val id = "social-device-${java.util.UUID.randomUUID()}"
        var postId: String? = null
        try {
            dao.putPodcast(PodcastEntity(id, "https://example.test/$id", "Ideias do podcast"))
            dao.putEpisode(EpisodeEntity(id, id, id, "Prática e atenção", "https://example.test/audio.mp3"))
            dao.putDistillation(DistillationEntity(id, id, 1000, 60000, "Pequenos hábitos ajudam a aprender.", "Pequenos hábitos", "A prática consistente ajuda a aprender.", state = "succeeded"))
            withContext(Dispatchers.Main) {
                val vm = ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java]
                vm.root("saved"); vm.open("capture:$id")
                compose.activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(graph.context.resources.displayMetrics.density, 1.6f)) { ProductApp(vm) }
                }
            }
            compose.onNodeWithText("Create post").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Headline").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Headline").performScrollTo().performTextInput("Uma ideia para hoje")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithText("Design & preview").performScrollTo().performClick()
            compose.onNodeWithText("Mint").performScrollTo().performClick()
            compose.onNodeWithText("Square").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Uma ideia para hoje. “Pequenos hábitos”. Ideias do podcast · Prática e atenção · 0:01–1:00").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Uma ideia para hoje. “Pequenos hábitos”. Ideias do podcast · Prática e atenção · 0:01–1:00").performScrollTo()
            androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()).takeScreenshot(java.io.File(graph.context.getExternalFilesDir(null), "social-card-preview.png"))
            compose.onNodeWithText("Copy image description").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Saved on this device").fetchSemanticsNodes().isNotEmpty() }
            val rows = dao.observePosts().first()
            val draft = rows.single { it.sourceMomentId == id }; postId = draft.id
            val saved = PostContent.parse(draft.content)
            assertEquals("Uma ideia para hoje", saved.headline); assertEquals("Mint", saved.theme); assertTrue(saved.square)
            dao.deleteDistillation(id)
            assertNull(dao.post(draft.id)!!.sourceMomentId)
            withContext(Dispatchers.Main) {
                val vm = ViewModelProvider(compose.activity, AppViewModel.factory(graph))[AppViewModel::class.java]
                vm.root("saved")
            }
            compose.onNodeWithText("Post drafts").performClick()
            compose.onNodeWithText("Uma ideia para hoje").assertExists()
        } finally {
            dao.observePosts().first().filter { it.sourceMomentId == id || it.id == postId }.forEach { dao.deletePost(it.id) }
            dao.deletePodcast(id)
        }
    }
}
