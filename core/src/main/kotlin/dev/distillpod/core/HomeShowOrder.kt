package dev.distillpod.core

import java.util.Locale

/**
 * What places one followed show in Home's "Your shows" row (#276). [audio] is true when the show matches the
 * Shows screen's Audio filter, which includes mixed audio/video shows. [latestPublishedAt] is the publication
 * time of the show's latest known episode in epoch milliseconds; null or a non-positive value means unknown.
 */
data class HomeShowRank(val id: String, val title: String, val pinned: Boolean, val audio: Boolean, val latestPublishedAt: Long?)

/**
 * Order of Home's "Your shows" row (#276): pinned before unpinned shows; within each, Audio-filter shows before
 * video-only ones; then the newest latest episode first, with unknown dates last; ties by case-insensitive title,
 * then id. Only an actual new publication moves a show, so refreshing a feed with nothing new keeps its place.
 */
object HomeShowOrder {
    private fun HomeShowRank.knownDate(): Long? = latestPublishedAt?.takeIf { it > 0 }

    val comparator: Comparator<HomeShowRank> = compareByDescending<HomeShowRank> { it.pinned }
        .thenByDescending { it.audio }
        .thenBy { it.knownDate() == null }
        .thenByDescending { it.knownDate() ?: 0 }
        .thenBy { it.title.lowercase(Locale.ROOT) }
        .thenBy { it.id }

    /** [shows] in Home order, ranking each show once. */
    fun <T> sort(shows: List<T>, rank: (T) -> HomeShowRank): List<T> =
        shows.map { it to rank(it) }.sortedWith(compareBy(comparator) { it.second }).map { it.first }
}
