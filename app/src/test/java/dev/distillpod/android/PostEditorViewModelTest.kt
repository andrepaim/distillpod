package dev.distillpod.android

import androidx.lifecycle.SavedStateHandle
import dev.distillpod.android.data.*
import dev.distillpod.android.features.PostEditorViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PostEditorViewModelTest {
    private fun draft(content: PostContent) = PostDraftEntity("post", null, content.json(), 0, 0)
    @Test fun `late response cannot replace typing and request is not restarted`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val original = PostContent(excerpt = "Original source", takeaway = "Original")
            var disk = draft(original); val response = CompletableDeferred<PostContent>(); var calls = 0
            val editor = PostEditorViewModel({ disk }, { _, content -> disk.copy(content = content.json(), revision = disk.revision + 1).also { disk = it } },
                { _, _ -> calls++; withContext(NonCancellable) { response.await() } })
            runCurrent(); editor.generate("Draft with AI"); runCurrent()
            assertTrue(editor.state.value.generating)
            editor.edit { it.copy(headline = "My new thought") }
            response.complete(original.copy(headline = "Obsolete AI result"))
            advanceUntilIdle()
            assertEquals("My new thought", editor.state.value.content!!.headline)
            assertNull(editor.state.value.proposal)
            assertEquals(1, calls)
            assertEquals("My new thought", PostContent.parse(disk.content).headline)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `proposal acceptance and undo persist without overwriting original source`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val original = PostContent(excerpt = "Original source", takeaway = "Original")
            var disk = draft(original)
            val editor = PostEditorViewModel({ disk }, { _, content -> disk.copy(content = content.json(), revision = disk.revision + 1).also { disk = it } },
                { content, _ -> content.copy(headline = "Suggested") })
            runCurrent(); editor.generate("Draft with AI"); advanceUntilIdle()
            assertEquals("", PostContent.parse(disk.content).headline)
            assertEquals("Suggested", editor.state.value.proposal!!.headline)
            editor.accept(); advanceUntilIdle()
            assertEquals("Suggested", PostContent.parse(disk.content).headline)
            editor.undo(); advanceUntilIdle()
            assertEquals(original, PostContent.parse(disk.content))
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `process recreation recovers unsaved valid content without making AI request`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val original = PostContent(takeaway = "Original")
            val changed = original.copy(headline = "Recovered thought")
            var disk = draft(original)
            val editor = PostEditorViewModel({ disk }, { _, content -> disk.copy(content = content.json(), revision = 1).also { disk = it } },
                { _, _ -> error("AI must not run") }, SavedStateHandle(mapOf("content" to changed.json())))
            advanceUntilIdle()
            assertEquals(changed, editor.state.value.content)
            assertEquals(changed, PostContent.parse(disk.content))
            assertTrue(editor.state.value.saved)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `save failure keeps edits and prevents export snapshot until retry succeeds`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val original = PostContent(takeaway = "Original"); var fails = true
            val editor = PostEditorViewModel({ draft(original) }, { _, content -> if (fails) error("Disk full") else draft(content) }, { content, _ -> content })
            runCurrent(); editor.edit { it.copy(headline = "Keep this") }; advanceUntilIdle()
            assertFalse(editor.state.value.saved); assertNotNull(editor.state.value.error)
            assertEquals("Keep this", editor.state.value.content!!.headline)
            assertNull(editor.flush()); fails = false
            assertEquals("Keep this", editor.flush()!!.headline)
            assertTrue(editor.state.value.saved)
        } finally { Dispatchers.resetMain() }
    }
}
