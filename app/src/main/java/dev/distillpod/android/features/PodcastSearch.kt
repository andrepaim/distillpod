package dev.distillpod.android.features

import dev.distillpod.android.data.PodcastCandidate
import dev.distillpod.android.data.ProviderSearchResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SearchState(val results: List<PodcastCandidate> = emptyList(), val loading: Boolean = false,
    val error: String? = null, val submittedQuery: String? = null,
    val loadingProviders: Set<String> = emptySet(), val failedProviders: Set<String> = emptySet())

internal fun isFeedAddress(value: String) = value.trim().let { it.startsWith("https://", true) || it.startsWith("http://", true) }

/** An obsolete blocking directory request may finish after cancellation; generations isolate it. */
internal class PodcastSearch(private val scope: CoroutineScope, private val find: suspend (String) -> List<PodcastCandidate>,
    private val remember: suspend (String) -> Unit = {},
    private val providers: Set<String> = emptySet(),
    private val findProviders: (suspend (String, String?, (ProviderSearchResult) -> Unit) -> Unit)? = null) {
    private val mutable = MutableStateFlow(SearchState())
    val state = mutable.asStateFlow()
    @Volatile private var request = 0L
    private var job: Job? = null
    private val providerRows = mutableMapOf<String, List<PodcastCandidate>>()
    fun clear() = synchronized(providerRows) { request++; job?.cancel(); providerRows.clear(); mutable.value = SearchState() }
    fun retry(provider: String) {
        val state = mutable.value
        if (!state.loading && provider in state.failedProviders) state.submittedQuery?.let { submit(it, provider) }
    }
    fun submit(query: String, only: String? = null) {
        val term = query.trim()
        if (term.length !in 2..200) return
        job?.cancel()
        val current = ++request
        if (only == null) synchronized(providerRows) { providerRows.clear() }
        mutable.value = if (only == null) SearchState(loading = true, submittedQuery = term, loadingProviders = providers)
            else mutable.value.copy(loading = true, error = null, loadingProviders = setOf(only), failedProviders = mutable.value.failedProviders - only)
        job = scope.launch {
            try {
                if (findProviders == null) {
                    val results = find(term)
                    if (current != request) return@launch
                    mutable.value = SearchState(results = results, submittedQuery = term)
                } else findProviders.invoke(term, only) { result ->
                    synchronized(providerRows) {
                        if (current == request) {
                            providerRows[result.provider] = result.results
                            val rows = providers.flatMap { providerRows[it].orEmpty() }.distinctBy { it.feedUrl }
                                .sortedByDescending { it.title.equals(term, true) }
                            mutable.update { state ->
                                val pending = state.loadingProviders - result.provider
                                val failed = if (result.failed) state.failedProviders + result.provider else state.failedProviders - result.provider
                                state.copy(results = rows, loading = pending.isNotEmpty(), loadingProviders = pending, failedProviders = failed,
                                    error = if (pending.isEmpty() && rows.isEmpty() && failed.isNotEmpty()) "Some sources could not be searched. Please retry." else null)
                            }
                        }
                    }
                }
                if (current != request) return@launch
                try { remember(term) } catch (error: Exception) { if (error is CancellationException) throw error }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current == request) mutable.value = SearchState(error = "Search could not finish. Check your connection and try again.", submittedQuery = term) }
        }
    }
}
