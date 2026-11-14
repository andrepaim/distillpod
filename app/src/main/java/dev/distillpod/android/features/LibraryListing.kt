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
    val episodeCount: Int, val visible: List<EpisodeEntity>, val resume: EpisodeEntity?, val feedProblems: List<PodcastEntity> = emptyList(),
    val continueListening: List<EpisodeEntity> = listOfNotNull(resume))

/**
 * The Library tab lists followed shows plus episodes kept offline or already played; a show page
 * lists that show. The episode loaded in the player resumes first, otherwise the most recently played
 * unfinished one. [LibraryListing.continueListening] follows it with the other unfinished episodes, most
 * recently played first, up to [CONTINUE_LISTENING_LIMIT]. [LibraryListing.feedProblems] are followed shows
 * (or the open show) whose refresh failed.
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
    // Continue listening always matches the mini-player while an episode is loaded, even a finished one (#146).
    // Only the few unfinished episodes are sorted; the stable sort keeps maxBy's first-of-equals choice.
    val unfinished = library.filter { it.positionMs > 0 && !it.completed }.sortedByDescending { it.lastPlayedAt }
    val resume = currentEpisodeId?.let { id -> episodes.firstOrNull { it.id == id } } ?: unfinished.firstOrNull()
    val continueListening = (listOfNotNull(resume) + unfinished.filter { it.id != resume?.id }).take(CONTINUE_LISTENING_LIMIT)
    // Unfollowed shows are never refreshed, so an old error of theirs would never clear (#146).
    val problems = podcasts.filter { it.error != null && if (podcastId != null) it.id == podcastId else it.subscribed }
    return LibraryListing(podcastId, filter, subscribed, selected, library.size, visible, resume, problems, continueListening)
}

/** The Library's continue-listening carousel holds at most this many episodes (proposal 5.4). */
internal const val CONTINUE_LISTENING_LIMIT = 5

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
