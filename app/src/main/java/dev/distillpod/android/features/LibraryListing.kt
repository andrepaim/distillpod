package dev.distillpod.android.features

import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.playback.PlaybackState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Rows of the Library tab or one show page, for the filter they were selected with. */
internal data class LibraryListing(val podcastId: String?, val filter: String, val subscribed: List<PodcastEntity>, val selected: PodcastEntity?,
    val episodeCount: Int, val visible: List<EpisodeEntity>, val resume: EpisodeEntity?)

/**
 * The Library tab lists followed shows plus episodes kept offline or already played; a show page
 * lists that show. The current episode resumes first, otherwise the most recently played unfinished one.
 */
internal fun libraryListing(podcasts: List<PodcastEntity>, episodes: List<EpisodeEntity>, filter: String,
    podcastId: String?, currentEpisodeId: String?): LibraryListing {
    val subscribed = podcasts.filter { it.subscribed }
    val followed = subscribed.mapTo(HashSet()) { it.id }
    val selected = podcasts.firstOrNull { it.id == podcastId }
    val library = episodes.filter { ep ->
        if (podcastId != null) ep.podcastId == podcastId else ep.podcastId in followed || ep.localFile != null || ep.lastPlayedAt > 0
    }
    val filtered = library.filter { ep ->
        when (filter) { "downloads" -> ep.localFile != null; "progress" -> ep.positionMs > 0 && !ep.completed; else -> true }
    }
    val visible = if (selected?.feedUrl?.let { dev.distillpod.core.YouTubeUrl.source(it) } != null)
        filtered.sortedBy { it.sourceOrder ?: Int.MAX_VALUE } else filtered
    val resume = library.firstOrNull { it.id == currentEpisodeId && !it.completed }
        ?: library.filter { it.positionMs > 0 && !it.completed }.maxByOrNull { it.lastPlayedAt }
    return LibraryListing(podcastId, filter, subscribed, selected, library.size, visible, resume)
}

/** The Library tab (null show) or a show page when it is the top route; null for other screens. */
internal data class LibraryRoute(val podcastId: String?)
internal fun libraryRoute(route: String?): LibraryRoute? = when {
    route == "library" -> LibraryRoute(null)
    route?.startsWith("podcast:") == true -> LibraryRoute(route.substringAfter(':'))
    else -> null
}

/**
 * Rows of the library page on top of [route], recomputed for library, filter, route or
 * current-episode changes; live player position ticks do not recompute them (#141).
 */
internal fun libraryListings(podcasts: Flow<List<PodcastEntity>>, episodes: Flow<List<EpisodeEntity>>, filter: Flow<String>,
    player: Flow<PlaybackState>, route: Flow<String?>): Flow<LibraryListing?> =
    combine(podcasts, episodes, filter, player.map { it.episodeId }.distinctUntilChanged(),
        route.map(::libraryRoute).distinctUntilChanged()) { p, e, f, current, page ->
        page?.let { libraryListing(p, e, f, it.podcastId, current) }
    }.distinctUntilChanged()
