package dev.distillpod.core

/** Explicit seeking into an ad means replay it until that range is left. */
class ReviewedAdSkipping {
    private var episode: String? = null
    private var replay: TimeRange? = null
    private fun select(id: String) { if (episode != id) { episode = id; replay = null } }
    fun seek(id: String, position: Long, ranges: List<TimeRange>) {
        select(id)
        replay = ranges.firstOrNull { position >= it.startMs && position < it.endMs }
    }
    fun target(id: String, position: Long, ranges: List<TimeRange>, enabled: Boolean): Long? {
        select(id)
        replay?.let { if (position < it.startMs || position >= it.endMs) replay = null }
        if (!enabled) return null
        return ranges.firstOrNull { position >= it.startMs && position < it.endMs && it != replay }?.endMs
    }
}
