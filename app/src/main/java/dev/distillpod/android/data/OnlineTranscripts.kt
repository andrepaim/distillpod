package dev.distillpod.android.data

import androidx.room.*
import dev.distillpod.core.episodeId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong

private const val RESPONSE_LIMIT = 2_000_000
private const val DAY_MS = 86_400_000L
const val ONLINE_TRANSCRIPT_PROVIDER = "PodcastTranscript.ai"

@Entity(tableName = "online_transcripts", foreignKeys = [ForeignKey(entity = EpisodeEntity::class,
    parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.CASCADE)])
data class OnlineTranscriptEntity(@PrimaryKey val episodeId: String, val revision: String, val audioUrl: String,
    val providerId: String, val retrievedAt: Long, val createdAt: String, val durationMs: Long,
    val language: String, val segmentsJson: String, val contentHash: String, val selected: Boolean = true) {
    fun matches(ep: EpisodeEntity) = selected && sameAudio(audioUrl, ep.audioUrl)
    fun transcript(ep: EpisodeEntity) = TranscriptEntity(ep.id, revision, ep.mediaFingerprint,
        ONLINE_TRANSCRIPT_PROVIDER, language, source = "online", state = "ready", durationMs = durationMs, processedMs = durationMs)
    fun segments() = decodeOnlineSegments(episodeId, revision, segmentsJson, durationMs)
}

@Entity(tableName = "transcript_lookup", foreignKeys = [ForeignKey(entity = EpisodeEntity::class,
    parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.CASCADE)])
data class TranscriptLookupEntity(@PrimaryKey val episodeId: String, val identity: String, val retryAt: Long)

data class ExistingTranscript(val providerId: String, val audioUrl: String, val durationMs: Long,
    val language: String, val createdAt: String, val segmentsJson: String)

fun interface ExistingTranscriptProvider { suspend fun find(ep: EpisodeEntity): ExistingTranscript? }

private fun sameAudio(left: String, right: String): Boolean = runCatching {
    publicHttpUrl(left) == publicHttpUrl(right)
}.getOrDefault(false)

internal fun decodeOnlineSegments(id: String, revision: String, json: String, durationMs: Long): List<SegmentEntity> {
    require(json.length <= 1_000_000 && durationMs in 1..86_400_000)
    val values = JSONArray(json)
    require(values.length() in 1..30000)
    var previousStart = -1L
    var textSize = 0
    val result = (0 until values.length()).map { i ->
        val s = values.getJSONObject(i)
        fun millis(key: String): Long {
            val seconds = s.getDouble(key)
            require(seconds.isFinite() && seconds >= 0 && seconds <= 86401)
            return (seconds * 1000).roundToLong()
        }
        val start = millis("start")
        val end = millis("end")
        val text = s.getString("text").trim()
        textSize += text.length
        require(start >= previousStart && end > start && start < durationMs && end <= durationMs + 1000)
        require(text.isNotEmpty() && text.length <= 10000 && textSize <= 1_000_000)
        previousStart = start
        SegmentEntity(id, revision, start, end.coerceAtMost(durationMs), text, i)
    }
    // A truncated preview must not unlock full-episode analysis.
    require(result.maxOf { it.endMs } >= durationMs - minOf(10000L, durationMs / 10))
    return result
}

/** Only public GET endpoints. Never submit audio or create a transcription job. */
class PodcastTranscriptClient(http: OkHttpClient,
    private val baseUrl: HttpUrl = "https://backend.podcasttranscript.ai/api/v1/".toHttpUrl()) : ExistingTranscriptProvider {
    private val client = http.newBuilder().callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    override suspend fun find(ep: EpisodeEntity): ExistingTranscript? = try {
        withTimeout(15000) {
            val search = baseUrl.newBuilder().addPathSegment("transcriptions")
                .addQueryParameter("q", ep.title.take(200)).addQueryParameter("limit", "20").build()
            val candidates = get(search).getJSONArray("data")
            require(candidates.length() <= 20)
            val matches = (0 until candidates.length()).map { candidates.getJSONObject(it) }.filter { matches(it, ep) }.take(3)
            for (candidate in matches) {
                val id = candidate.getString("id")
                require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
                val detail = get(baseUrl.newBuilder().addPathSegment("transcriptions").addPathSegment(id)
                    .addQueryParameter("include", "segments").build()).getJSONObject("data").getJSONObject("transcription")
                if (!matches(detail, ep) || detail.getString("id") != id) continue
                val seconds = detail.getDouble("duration")
                if (!seconds.isFinite() || seconds <= 0 || seconds > 86400) continue
                val duration = (seconds * 1000).roundToLong()
                if (ep.durationMs > 0 && abs(duration - ep.durationMs) > 2000) continue
                val segments = detail.getJSONArray("segments").toString()
                if (runCatching { decodeOnlineSegments(ep.id, "validation", segments, duration) }.isFailure) continue
                return@withTimeout ExistingTranscript(id, ep.audioUrl, duration,
                    detail.optString("language").take(100), detail.optString("created_at").take(100), segments)
            }
            null
        }
    } catch (timeout: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw IOException("Transcript lookup timed out", timeout)
    }
    private fun matches(value: JSONObject, ep: EpisodeEntity) = listOf("audio_url", "original_url")
        .any { sameAudio(value.optString(it), ep.audioUrl) }

    private suspend fun get(url: HttpUrl): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWith(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use {
                    if (it.code == 429) throw TranscriptRateLimit(it.header("Retry-After")?.toLongOrNull())
                    check(it.isSuccessful) { "Transcript lookup is unavailable" }
                    JSONObject(it.body!!.boundedText(RESPONSE_LIMIT)).also { body -> check(body.getBoolean("ok")) }
                } }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }
}

private class TranscriptRateLimit(val seconds: Long?) : IOException()

/** Cached online text stays available while an explicit local replacement is prepared. */
class EpisodeTextResolver(private val db: AppDatabase) {
    suspend fun online(id: String): OnlineTranscriptEntity? {
        val ep = db.library().episode(id) ?: return null
        return db.library().onlineTranscript(id)?.takeIf { it.matches(ep) }
    }
    suspend fun complete(id: String): TranscriptEntity? {
        val ep = db.library().episode(id) ?: return null
        val local = db.library().transcript(id)?.takeIf { it.state == "ready" && it.fingerprint == ep.mediaFingerprint }
        return if (local != null && local.source != "online") local else online(id)?.transcript(ep) ?: local
    }
    suspend fun segments(id: String, revision: String): List<SegmentEntity> {
        val online = online(id)?.takeIf { it.revision == revision }
        return online?.segments() ?: db.library().segments(id, revision)
    }
}

class OnlineTranscriptRepository(private val db: AppDatabase, private val settings: AppSettings,
    private val provider: ExistingTranscriptProvider, private val networkAllowed: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis) {
    constructor(graph: dev.distillpod.android.AppGraph) : this(graph.db, graph.settings, PodcastTranscriptClient(graph.http),
        { transcriptionAllowed(graph.context, graph.preferences.value, true) })

    suspend fun lookup(ep: EpisodeEntity, row: TranscriptEntity): Boolean {
        if (!settings.flow.first().onlineTranscripts || !row.lookupOnline || !networkAllowed()) return false
        // Public-source approval is explicit provenance, never a guess based on a URL's shape.
        if (db.sync().approved(ep.podcastId) == null) return false
        val identity = episodeId(ep.audioUrl, ep.title)
        val cached = db.library().transcriptLookup(ep.id)
        if (cached?.identity == identity && cached.retryAt > now()) return false
        var delay = DAY_MS
        val found = try {
            provider.find(ep)?.also {
                require(sameAudio(it.audioUrl, ep.audioUrl))
                require(ep.durationMs <= 0 || abs(it.durationMs - ep.durationMs) <= 2000)
                decodeOnlineSegments(ep.id, "validation", it.segmentsJson, it.durationMs)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            delay = if (error is TranscriptRateLimit) (error.seconds ?: 900).coerceIn(60, 86400) * 1000 else 900_000L
            null
        }
        currentCoroutineContext().ensureActive()
        return db.withTransaction {
            val dao = db.library()
            val current = dao.transcript(ep.id) ?: return@withTransaction false
            val latest = dao.episode(ep.id) ?: return@withTransaction false
            if (current.revision != row.revision || current.state != "processing" || !current.lookupOnline ||
                latest.audioUrl != ep.audioUrl || latest.mediaFingerprint != ep.mediaFingerprint ||
                !settings.flow.first().onlineTranscripts || db.sync().approved(ep.podcastId) == null) return@withTransaction false
            dao.putTranscriptLookup(TranscriptLookupEntity(ep.id, identity, now() + delay))
            if (found == null) return@withTransaction false
            require(sameAudio(found.audioUrl, ep.audioUrl))
            val hash = episodeId("online-transcript", found.segmentsJson)
            val online = OnlineTranscriptEntity(ep.id, "online:$hash", ep.audioUrl, found.providerId,
                now(), found.createdAt, found.durationMs, found.language, found.segmentsJson, hash)
            val segments = online.segments()
            dao.putOnlineTranscript(online)
            dao.deleteSegments(ep.id); dao.deleteCoverage(ep.id)
            dao.putSegments(segments)
            dao.putCoverage(CoverageEntity(ep.id, online.revision, 0, online.durationMs))
            dao.putTranscriptAt(online.transcript(ep), TranscriptWriteSite.ONLINE_ADOPTION)
            true
        }
    }
}
