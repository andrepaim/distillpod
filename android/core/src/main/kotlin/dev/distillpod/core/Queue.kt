package dev.distillpod.core
import java.security.MessageDigest

fun episodeId(feed: String, guid: String): String = MessageDigest.getInstance("SHA-256")
    .digest("${feed.length}:$feed$guid".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
class EpisodeQueue(initial: List<String> = emptyList()) {
    private val entries = initial.distinct().toMutableList()
    val items: List<String> get() = entries.toList()
    fun addNext(id: String) { entries.remove(id); entries.add(0, id) }
    fun addLast(id: String) { entries.remove(id); entries.add(id) }
    fun remove(id: String) { entries.remove(id) }
    fun peek(): String? = entries.firstOrNull()
    fun acknowledgePlayed(id: String) { entries.remove(id) }
    fun move(from: Int, to: Int) {
        require(from in entries.indices && to in entries.indices)
        entries.add(to, entries.removeAt(from))
    }
}
data class ListeningProgress(val positionMs: Long, val durationMs: Long, val completed: Boolean = false) {
    init { require(positionMs >= 0 && durationMs >= 0 && (durationMs == 0L || positionMs <= durationMs)) }
}
