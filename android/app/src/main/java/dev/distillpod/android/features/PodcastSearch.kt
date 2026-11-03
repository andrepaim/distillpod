package dev.distillpod.android.features

import dev.distillpod.android.data.PodcastCandidate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SearchState(val results: List<PodcastCandidate> = emptyList(), val loading: Boolean = false,
    val error: String? = null, val submittedQuery: String? = null)

internal fun isFeedAddress(value: String) = value.trim().let { it.startsWith("https://", true) || it.startsWith("http://", true) }

/** An obsolete blocking directory request may finish after cancellation; generations isolate it. */
internal class PodcastSearch(private val scope: CoroutineScope, private val find: suspend (String) -> List<PodcastCandidate>,
    private val remember: suspend (String) -> Unit = {}) {
    private val mutable = MutableStateFlow(SearchState())
    val state = mutable.asStateFlow()
    private var request = 0L
    private var job: Job? = null
    fun clear() { request++; job?.cancel(); mutable.value = SearchState() }
    fun submit(query: String) {
        val term = query.trim()
        if (term.length !in 2..200) return
        job?.cancel()
        val current = ++request
        mutable.value = SearchState(loading = true, submittedQuery = term)
        job = scope.launch {
            try {
                val results = find(term)
                if (current != request) return@launch
                mutable.value = SearchState(results = results, submittedQuery = term)
                try { remember(term) } catch (error: Exception) { if (error is CancellationException) throw error }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current == request) mutable.value = SearchState(error = "Search could not finish. Check your connection and try again.", submittedQuery = term) }
        }
    }
}
