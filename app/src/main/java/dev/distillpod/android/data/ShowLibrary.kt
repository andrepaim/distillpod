package dev.distillpod.android.data

import dev.distillpod.core.HomeShowOrder
import dev.distillpod.core.HomeShowRank
import dev.distillpod.core.YouTubeUrl
import java.util.Locale

enum class ShowFormat(val label: String) { ALL("All"), AUDIO("Audio"), VIDEO("Video") }
enum class ShowSort(val label: String) { TITLE("A–Z"), RECENTLY_PLAYED("Recently played"), LATEST_EPISODE("Latest episode") }

data class ShowPreferences(
    val format: ShowFormat = ShowFormat.ALL,
    val sort: ShowSort = ShowSort.TITLE,
    val pinnedFeeds: Set<String> = emptySet(),
)

/** The feed a pin is stored under: the original feed, so pins survive a show's local id or feed change. */
internal val PodcastEntity.pinKey: String get() = originalFeedUrl.ifBlank { feedUrl }

/** A video MIME type or a YouTube video link. */
private fun EpisodeEntity.isVideo() = mimeType?.startsWith("video/", ignoreCase = true) == true || YouTubeUrl.videoId(audioUrl) != null

/** The Shows screen's Audio filter: at least one audio episode, so mixed audio/video shows match too. */
internal fun showHasAudio(episodes: List<EpisodeEntity>): Boolean = episodes.any { !it.isVideo() }

/** Publication of the latest known episode, or 0 when no episode has a date. */
internal fun latestPublication(episodes: List<EpisodeEntity>): Long = episodes.maxOfOrNull { it.publishedAt.coerceAtLeast(0) } ?: 0

data class ShowSummary(
    val show: PodcastEntity,
    val hasAudio: Boolean = false,
    val hasVideo: Boolean = false,
    val episodeCount: Int = 0,
    val unfinishedCount: Int = 0,
    val latestPublishedAt: Long = 0,
    val lastPlayedAt: Long = 0,
) {
    val pinKey: String get() = show.pinKey
    val formatLabel: String get() = when {
        hasAudio && hasVideo -> "Audio & video"
        hasVideo -> "Video"
        hasAudio -> "Audio"
        else -> "Format unknown"
    }
}

data class ShowLibraryState(val shows: List<ShowSummary>, val preferences: ShowPreferences)

internal fun summarizeShows(shows: List<PodcastEntity>, episodes: List<EpisodeEntity>): List<ShowSummary> {
    val byShow = episodes.groupBy { it.podcastId }
    return shows.filter { it.subscribed }.map { show ->
        val items = byShow[show.id].orEmpty()
        ShowSummary(show, hasAudio = showHasAudio(items),
            hasVideo = items.any { it.isVideo() } || YouTubeUrl.source(show.feedUrl) != null,
            episodeCount = items.size, unfinishedCount = items.count { !it.completed },
            latestPublishedAt = latestPublication(items),
            lastPlayedAt = items.maxOfOrNull { it.lastPlayedAt.coerceAtLeast(0) } ?: 0)
    }
}

/**
 * Home's "Your shows" row (#276): [HomeShowOrder] over the Shows screen's pins and Audio filter, ranked by each
 * show's latest known episode. A feed refresh moves a show only when it stores a newer publication.
 */
internal fun homeShows(subscribed: List<PodcastEntity>, episodes: List<EpisodeEntity>, pinnedFeeds: Set<String>): List<PodcastEntity> {
    if (subscribed.size < 2) return subscribed
    val ids = subscribed.mapTo(HashSet()) { it.id }
    val byShow = episodes.filter { it.podcastId in ids }.groupBy { it.podcastId }
    return HomeShowOrder.sort(subscribed) { show ->
        val items = byShow[show.id].orEmpty()
        HomeShowRank(show.id, show.title, show.pinKey in pinnedFeeds, showHasAudio(items), latestPublication(items))
    }
}

internal fun selectShows(shows: List<ShowSummary>, query: String, preferences: ShowPreferences): List<ShowSummary> {
    val titleOrder = compareBy<ShowSummary> { it.show.title.lowercase(Locale.ROOT) }.thenBy { it.show.id }
    val order = when (preferences.sort) {
        ShowSort.TITLE -> titleOrder
        ShowSort.RECENTLY_PLAYED -> compareByDescending<ShowSummary> { it.lastPlayedAt }.then(titleOrder)
        ShowSort.LATEST_EPISODE -> compareByDescending<ShowSummary> { it.latestPublishedAt }.then(titleOrder)
    }
    val term = query.trim()
    return shows.filter { row ->
        (term.isEmpty() || (row.show.title + " " + row.show.author).contains(term, ignoreCase = true)) &&
            when (preferences.format) {
                ShowFormat.ALL -> true
                ShowFormat.AUDIO -> row.hasAudio
                ShowFormat.VIDEO -> row.hasVideo
            }
    }.sortedWith(compareByDescending<ShowSummary> { it.pinKey in preferences.pinnedFeeds }.then(order))
}
