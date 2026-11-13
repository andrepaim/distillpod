package dev.distillpod.android.features

import dev.distillpod.android.data.*
import dev.distillpod.core.YouTubeUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/** Typing pauses this long before the library is searched. */
internal const val LIBRARY_SEARCH_DEBOUNCE_MS = 250L
/** Rows of one section shown before "Show all", and at most after it. */
internal const val LIBRARY_SECTION_PREVIEW = 3
internal const val LIBRARY_SECTION_CAP = 50
private const val MAX_TERMS = 6

/**
 * Lower-case words of a library search, or null when there is nothing to search: fewer than two
 * characters, or a feed address that the Search tab offers to add instead.
 */
internal fun librarySearchTerms(query: String): List<String>? {
    val text = query.trim().take(200)
    if (text.length < 2 || isFeedAddress(text)) return null
    return text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct().take(MAX_TERMS)
}

internal data class ShowMatch(val id: String, val title: String, val author: String, val imageUrl: String?, val video: Boolean)
internal data class EpisodeMatch(val id: String, val title: String, val showTitle: String?, val imageUrl: String?, val publishedAt: Long, val video: Boolean)
internal data class PassageMatch(val episodeId: String, val episodeTitle: String, val showTitle: String?, val imageUrl: String?,
    val startMs: Long, val snippet: String, val matches: Int, val video: Boolean)
internal data class MomentMatch(val id: String, val text: String, val episodeTitle: String?, val showTitle: String?, val startMs: Long,
    val imageUrl: String?, val video: Boolean)

/** Every library match for [terms], ranked; sections are filtered by format and capped when shown. */
internal data class LibraryMatches(val terms: List<String>, val shows: List<ShowMatch> = emptyList(), val episodes: List<EpisodeMatch> = emptyList(),
    val passages: List<PassageMatch> = emptyList(), val moments: List<MomentMatch> = emptyList())

/** [terms] is what the field asks for; [matches] may still belong to the previous terms while [loading]. */
internal data class LibrarySearchState(val terms: List<String>? = null, val loading: Boolean = false,
    val matches: LibraryMatches? = null, val failed: Boolean = false)

private fun String.hasAll(terms: List<String>) = terms.all { contains(it, ignoreCase = true) }

/**
 * Ranks library rows for [terms]. Shows are followed shows; episodes are the Library tab's own
 * (followed shows, plus episodes kept offline or played). Every term must appear somewhere in a row.
 */
internal fun libraryMatches(terms: List<String>, podcasts: List<PodcastEntity>, episodes: List<EpisodeEntity>,
    captures: List<DistillationEntity>, hits: List<TranscriptHit>): LibraryMatches {
    val phrase = terms.joinToString(" ")
    val showById = podcasts.associateBy { it.id }
    fun PodcastEntity?.video() = this != null && YouTubeUrl.source(feedUrl) != null
    fun EpisodeEntity.art() = imageUrl ?: showById[podcastId]?.imageUrl

    val shows = podcasts.filter { it.subscribed && "${it.title}\n${it.author}".hasAll(terms) }
        .sortedWith(compareByDescending<PodcastEntity> { it.title.contains(phrase, true) }.thenBy { it.title.lowercase() })
        .map { ShowMatch(it.id, it.title, it.author, it.imageUrl, it.video()) }

    val followed = podcasts.filter { it.subscribed }.mapTo(HashSet()) { it.id }
    val episodeRows = episodes.asSequence()
        .filter { it.podcastId in followed || it.localFile != null || it.lastPlayedAt > 0 }
        .mapNotNull { ep ->
            val show = showById[ep.podcastId]?.title.orEmpty()
            val score = when {
                ep.title.contains(phrase, true) -> 3
                ep.title.hasAll(terms) -> 2
                "${ep.title}\n$show".hasAll(terms) -> 1
                "${ep.title}\n$show\n${ep.description}".hasAll(terms) -> 0
                else -> return@mapNotNull null
            }
            score to ep
        }
        .sortedWith(compareByDescending<Pair<Int, EpisodeEntity>> { it.first }.thenByDescending { it.second.publishedAt })
        .map { (_, ep) -> val show = showById[ep.podcastId]; EpisodeMatch(ep.id, ep.title, show?.title, ep.art(), ep.publishedAt, show.video()) }
        .toList()

    val episodeById = episodes.associateBy { it.id }
    val passages = hits.mapNotNull { hit -> episodeById[hit.episodeId]?.let { it to hit } }
        .sortedWith(compareByDescending<Pair<EpisodeEntity, TranscriptHit>> { it.second.matches }
            .thenByDescending { it.first.lastPlayedAt }.thenByDescending { it.first.publishedAt })
        .map { (ep, hit) -> val show = showById[ep.podcastId]
            PassageMatch(ep.id, ep.title, show?.title, ep.art(), hit.startMs, matchSnippet(hit.text, terms), hit.matches, show.video()) }

    val moments = savedMomentItems(captures, episodes, podcasts).zip(captures).mapNotNull { (item, saved) ->
        val own = listOfNotNull(item.insight, item.quote, item.excerpt).joinToString("\n")
        val score = when {
            own.hasAll(terms) -> 1
            "$own\n${item.episodeTitle.orEmpty()}\n${item.showTitle.orEmpty()}".hasAll(terms) -> 0
            else -> return@mapNotNull null
        }
        score to MomentMatch(item.id, item.insight ?: item.quote ?: item.excerpt.ifBlank { "Moment at ${timestamp(item.startMs)}" },
            item.episodeTitle, item.showTitle, item.startMs, item.artworkUrl, showById[episodeById[saved.episodeId]?.podcastId].video())
    }.sortedByDescending { it.first }.map { it.second }

    return LibraryMatches(terms, shows, episodeRows, passages, moments)
}

/** A passage shortened around its first match, so long online segments still show why they matched. */
internal fun matchSnippet(text: String, terms: List<String>, radius: Int = 90): String {
    val clean = text.replace(Regex("\\s+"), " ").trim()
    if (clean.length <= radius * 2) return clean
    val at = terms.map { clean.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull() ?: 0
    var start = (at - radius).coerceAtLeast(0)
    var end = (at + radius).coerceAtMost(clean.length)
    if (start > 0) clean.indexOf(' ', start).takeIf { it in start until at }?.let { start = it + 1 }
    if (end < clean.length) clean.lastIndexOf(' ', end).takeIf { it > at }?.let { end = it }
    return (if (start > 0) "…" else "") + clean.substring(start, end).trim() + (if (end < clean.length) "…" else "")
}

internal fun <T> visibleLibraryRows(rows: List<T>, expanded: Boolean): List<T> =
    rows.take(if (expanded) LIBRARY_SECTION_CAP else LIBRARY_SECTION_PREVIEW)

/**
 * Library search states for the Search field. Each change waits [debounceMs]; a newer query cancels
 * the running one, so stale results are never published. Previous matches stay visible while loading.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun librarySearchStates(queries: Flow<String>, debounceMs: Long = LIBRARY_SEARCH_DEBOUNCE_MS,
    search: suspend (List<String>) -> LibraryMatches): Flow<LibrarySearchState> = flow {
    var shown: LibraryMatches? = null
    emitAll(queries.map(::librarySearchTerms).distinctUntilChanged().transformLatest { terms ->
        if (terms == null) { shown = null; emit(LibrarySearchState()); return@transformLatest }
        emit(LibrarySearchState(terms, loading = true, matches = shown))
        delay(debounceMs)
        val state = try { LibrarySearchState(terms, matches = search(terms)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { LibrarySearchState(terms, failed = true) }
        shown = state.matches
        emit(state)
    })
}

/** Searches the library rows already held in memory and the stored transcripts, off the main thread. */
internal class LibrarySearcher(private val transcripts: suspend (List<String>) -> List<TranscriptHit>,
    private val podcasts: () -> List<PodcastEntity>, private val episodes: () -> List<EpisodeEntity>,
    private val captures: () -> List<DistillationEntity>) {
    suspend fun search(terms: List<String>): LibraryMatches = coroutineScope {
        val hits = async { transcripts(terms) }
        val podcastRows = podcasts(); val episodeRows = episodes(); val captureRows = captures()
        withContext(Dispatchers.Default) { libraryMatches(terms, podcastRows, episodeRows, captureRows, hits.await()) }
    }
}
