package dev.distillpod.android.data

import dev.distillpod.core.YouTubeUrl
import java.util.Locale

enum class ShowFormat(val label: String) { ALL("All"), AUDIO("Audio"), VIDEO("Video") }
enum class ShowSort(val label: String) { TITLE("A–Z"), RECENTLY_PLAYED("Recently played"), LATEST_EPISODE("Latest episode") }

data class ShowPreferences(
    val format: ShowFormat = ShowFormat.ALL,
    val sort: ShowSort = ShowSort.TITLE,
    val pinnedFeeds: Set<String> = emptySet(),
)

data class ShowSummary(
    val show: PodcastEntity,
    val hasAudio: Boolean = false,
    val hasVideo: Boolean = false,
    val episodeCount: Int = 0,
    val unfinishedCount: Int = 0,
    val latestPublishedAt: Long = 0,
    val lastPlayedAt: Long = 0,
) {
    val pinKey: String get() = show.originalFeedUrl.ifBlank { show.feedUrl }
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
        val videoCount = items.count { it.mimeType?.startsWith("video/", ignoreCase = true) == true || YouTubeUrl.videoId(it.audioUrl) != null }
        ShowSummary(show, hasAudio = videoCount < items.size,
            hasVideo = videoCount > 0 || YouTubeUrl.source(show.feedUrl) != null,
            episodeCount = items.size, unfinishedCount = items.count { !it.completed },
            latestPublishedAt = items.maxOfOrNull { it.publishedAt.coerceAtLeast(0) } ?: 0,
            lastPlayedAt = items.maxOfOrNull { it.lastPlayedAt.coerceAtLeast(0) } ?: 0)
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
