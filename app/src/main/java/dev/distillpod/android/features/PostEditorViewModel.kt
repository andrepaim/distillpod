package dev.distillpod.android.features

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.distillpod.android.AppGraph
import dev.distillpod.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PostEditorState(val content: PostContent? = null, val saved: Boolean = true, val error: String? = null,
    val generating: Boolean = false, val proposal: PostContent? = null, val canUndo: Boolean = false)

class PostEditorViewModel(
    private val load: suspend () -> PostDraftEntity?,
    private val save: suspend (Int, PostContent) -> PostDraftEntity,
    private val compose: suspend (PostContent, String) -> PostContent,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val destinationChanged: (String) -> Unit = {},
) : ViewModel() {
    constructor(graph: AppGraph, id: String, savedState: SavedStateHandle) : this(
        { graph.db.library().post(id) }, { revision, content -> graph.posts.save(id, revision, content) },
        PostComposer(graph.ai)::compose, savedState,
        { destination -> graph.context.getSharedPreferences("post-editor", android.content.Context.MODE_PRIVATE).edit().putString("destination", destination).apply() },
    )
    val state = MutableStateFlow(PostEditorState())
    private var row: PostDraftEntity? = null
    private var sequence = 0
    private var undo: PostContent? = null
    private var generation: Job? = null
    private val mutex = Mutex()
    init { viewModelScope.launch {
        try { row = load() ?: error("Draft was deleted"); val disk = PostContent.parse(row!!.content)
            val recovered = savedState.get<String>("content")?.let { runCatching { PostContent.parse(it, validate = false) }.getOrNull() }
            state.value = PostEditorState(recovered ?: disk, saved = recovered == null || recovered == disk)
            if (!state.value.saved) flush() }
        catch (e: Exception) { state.value = state.value.copy(error = safeFailure(e)) }
    } }
    fun edit(change: (PostContent) -> PostContent) {
        val old = state.value.content ?: return
        sequence++
        generation?.cancel()
        state.value = state.value.copy(content = change(old), saved = false, proposal = null, generating = false, error = null)
        savedState["content"] = state.value.content!!.json()
        val scheduled = sequence
        viewModelScope.launch { delay(250); if (scheduled == sequence) flush() }
    }
    suspend fun flush(): PostContent? = mutex.withLock {
        val content = state.value.content ?: return@withLock null
        if (state.value.saved) return@withLock content
        val current = row ?: return@withLock null
        val version = sequence
        try {
            row = save(current.revision, content)
            destinationChanged(content.destination)
            if (version == sequence) { savedState.remove<String>("content"); state.value = state.value.copy(saved = true, error = null) }
            content
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            state.value = state.value.copy(error = safeFailure(e)); null
        }
    }
    fun generate(action: String) {
        if (state.value.generating) return
        generation = viewModelScope.launch {
            val content = flush() ?: return@launch
            val version = sequence
            state.value = state.value.copy(generating = true, error = null, proposal = null)
            try {
                val result = compose(content, action)
                if (sequence == version) state.value = state.value.copy(proposal = result, generating = false)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (sequence == version) state.value = state.value.copy(generating = false, error = safeFailure(e))
            }
        }
    }
    fun cancel() { sequence++; generation?.cancel(); state.value = state.value.copy(generating = false, proposal = null) }
    fun accept() { val proposal = state.value.proposal ?: return; undo = state.value.content; edit { proposal }; state.value = state.value.copy(canUndo = true) }
    fun reject() { state.value = state.value.copy(proposal = null) }
    fun undo() { val previous = undo ?: return; edit { previous }; undo = null; state.value = state.value.copy(canUndo = false) }
    fun report(error: String) { state.value = state.value.copy(error = error) }
}
