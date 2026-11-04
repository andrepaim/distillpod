package dev.distillpod.core.sync

/** Wire-level identity; distinct from a provider login and from a device clock. */
data class SyncOwner(val uid: String, val generation: String)
data class SyncMutation(val id: String, val owner: SyncOwner, val key: String, val baseVersion: Long,
    val payload: Map<String, String>, val deleted: Boolean = false)
data class RemoteRecord(val version: Long, val payload: Map<String, String>, val deleted: Boolean = false)
sealed interface MergeDecision {
    data class Apply(val record: RemoteRecord) : MergeDecision
    data class Conflict(val current: RemoteRecord?) : MergeDecision
}
object SyncProtocol {
    private val safeId = Regex("[A-Za-z0-9_-]{1,128}")
    private val allowed = setOf("subscribed", "feedUrl", "originalFeedUrl", "title", "author", "positionMs", "completed",
        "episodeId", "podcastId", "guid", "audioUrl", "sessionId", "deviceId", "deviceSequence",
        "startMs", "endMs", "excerpt", "quote", "insight", "revision", "fingerprint", "createdAt")
    fun validate(mutation: SyncMutation) {
        require(listOf(mutation.id, mutation.owner.uid, mutation.owner.generation, mutation.key).all { safeId.matches(it) })
        require(mutation.baseVersion in 0 until Long.MAX_VALUE)
        require(mutation.payload.keys.all { it in allowed })
        require(mutation.payload.entries.sumOf { it.key.toByteArray(Charsets.UTF_8).size.toLong() + it.value.toByteArray(Charsets.UTF_8).size } <= 128 * 1024)
        for (field in listOf("feedUrl", "audioUrl", "originalFeedUrl")) mutation.payload[field]?.let {
            require(it.toByteArray(Charsets.UTF_8).size <= 2048)
            val uri = try { java.net.URI(it) } catch (_: Exception) { throw IllegalArgumentException("Invalid public locator") }
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        }
    }
    fun merge(mutation: SyncMutation, current: RemoteRecord?): MergeDecision {
        validate(mutation)
        val version = current?.version ?: 0L
        if (mutation.baseVersion != version) return MergeDecision.Conflict(current)
        return MergeDecision.Apply(RemoteRecord(version + 1,
            if (mutation.deleted) emptyMap() else mutation.payload.toMap(), mutation.deleted))
    }
    fun retryDelayMs(attempt: Int, jitter: Double): Long {
        require(attempt >= 0 && jitter.isFinite() && jitter in 0.0..1.0)
        return (15_000L * (1L shl attempt.coerceAtMost(8)) * (1.0 + jitter)).toLong().coerceAtMost(3_600_000L)
    }
}
