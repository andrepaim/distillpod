package dev.distillpod.android.video

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dev.distillpod.android.data.PodcastCandidate
import dev.distillpod.core.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

fun interface YouTubeExtractor {
    fun extract(url: String, flat: Boolean, limit: Int): JSONObject
}

/** Reads YouTube's public, account-free Atom feed for a channel or playlist. */
fun interface YouTubeMetadataFetcher {
    fun fetch(url: String): ByteArray
}

/** One bounded, unauthenticated GET; the feed parser applies the same 2 MB limit while reading. */
class HttpYouTubeMetadataFetcher(http: OkHttpClient) : YouTubeMetadataFetcher {
    private val client = http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    override fun fetch(url: String): ByteArray = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) throw IOException("YouTube feed request failed")
        val body = response.body ?: throw IOException("YouTube feed is empty")
        if (body.contentLength() > LIMIT) throw IOException("YouTube feed exceeds the supported size")
        val source = body.source()
        if (source.request(LIMIT + 1)) throw IOException("YouTube feed exceeds the supported size")
        source.readByteArray()
    }
    private companion object { const val LIMIT = 2_000_000L }
}

/** No account, cookies, API key, remote service or runtime code update. */
class AndroidYouTubeExtractor(private val context: Context,
    private val signal: (String, String, Long, String) -> Unit = { _, _, _, _ -> }) : YouTubeExtractor {
    override fun extract(url: String, flat: Boolean, limit: Int): JSONObject {
        val started = System.nanoTime()
        val operation = java.util.UUID.randomUUID().toString()
        fun report(name: String, reason: String, elapsed: Long) { runCatching { signal(name, reason, elapsed, operation) } }
        report("youtube.extraction_started", "starting", 0)
        repeat(2) { attempt ->
            try {
                val data = ExtractionCall(execute = { id ->
                    initializeRuntime()
                    val request = YoutubeDLRequest(url).addOption("--ignore-config").addOption("--no-update")
                        .addOption("--skip-download").addOption("--dump-single-json")
                        .addOption("--socket-timeout", 12).addOption("--retries", 0).addOption("--extractor-retries", 0)
                    if (flat) request.addOption("--flat-playlist").addOption("--playlist-end", limit)
                    else request.addOption("--no-playlist")
                    val response = YoutubeDL.getInstance().execute(request, id, null)
                    require(response.out.length <= 10_000_000) { "YouTube response is too large" }
                    JSONObject(response.out)
                }, destroy = { YoutubeDL.getInstance().destroyProcessById(it) },
                    timeoutMs = (40_000 - (System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)).run()
                report("youtube.extraction_completed", "succeeded", (System.nanoTime() - started) / 1_000_000)
                return data
            } catch (e: InterruptedException) {
                report("youtube.extraction_failed", "cancelled", (System.nanoTime() - started) / 1_000_000)
                throw e
            } catch (e: YouTubeFailure) {
                if (attempt == 0 && e.retryable && (System.nanoTime() - started) / 1_000_000 < 39_000) {
                    report("youtube.extraction_retry", e.category, (System.nanoTime() - started) / 1_000_000)
                    Thread.sleep(400)
                } else {
                    report("youtube.extraction_failed", e.category, (System.nanoTime() - started) / 1_000_000)
                    throw e
                }
            }
        }
        error("Unreachable extraction state")
    }

    private fun initializeRuntime() = synchronized(runtimeLock) {
        if (runtimeReady) return@synchronized
        YoutubeDL.getInstance().init(context)
        // The wrapper preserves its extracted script across APK updates. Replace it atomically
        // with the build-pinned raw resource before allowing any extraction in this process.
        val file = java.io.File(context.noBackupFilesDir, "${YoutubeDL.baseName}/${YoutubeDL.ytdlpDirName}/${YoutubeDL.ytdlpBin}")
        val atomic = android.util.AtomicFile(file)
        val output = atomic.startWrite()
        try {
            context.resources.openRawResource(dev.distillpod.android.R.raw.ytdlp).use { it.copyTo(output) }
            atomic.finishWrite(output)
        } catch (e: Exception) { atomic.failWrite(output); throw e }
        runtimeReady = true
    }
    companion object {
        private val runtimeLock = Any()
        private var runtimeReady = false
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
/** A JSON null or absent value is missing text, never the string "null". */
private fun JSONObject.text(name: String): String = if (isNull(name)) "" else optString(name)
/**
 * Prefer the exact upload instant. A date-only value is stored at 12:00 UTC so its calendar day
 * stays the same when shown in time zones from UTC-11 to UTC+11.
 */
internal fun JSONObject.publishedAt(): String? = optLong("timestamp", 0).takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).toString() }
    ?: runCatching {
        LocalDate.parse(getString("upload_date"), DateTimeFormatter.BASIC_ISO_DATE).atTime(12, 0).toInstant(ZoneOffset.UTC).toString()
    }.getOrNull()
private fun JSONObject.picture(): String? = optJSONArray("thumbnails")?.objects()
    ?.lastOrNull()?.optString("url")?.takeIf { it.startsWith("https://") }

data class YouTubeStreams(val audio: String, val video: String?)

internal data class StreamClock(val elapsedNanos: () -> Long = System::nanoTime,
    val wallMillis: () -> Long = System::currentTimeMillis)

/** Network extraction is injectable; parsing and identity are shared by live and fixture tests. */
class YouTubeSource internal constructor(private val extractor: YouTubeExtractor, private val clock: StreamClock,
    private val metadata: YouTubeMetadataFetcher? = null) {
    constructor(extractor: YouTubeExtractor) : this(extractor, StreamClock())
    constructor(extractor: YouTubeExtractor, metadata: YouTubeMetadataFetcher) : this(extractor, StreamClock(), metadata)
    var onStreamRefresh: () -> Unit = {}
    internal fun refreshed() { runCatching { onStreamRefresh() } }
    suspend fun search(query: String): List<PodcastCandidate> = withTimeoutOrNull(45_000) {
        coroutineScope {
            val batches = listOf("EgIQAg==", "EgIQAw==").map { filter -> async {
                try {
                    runInterruptible(Dispatchers.IO) {
                        val url = "https://www.youtube.com/results".toHttpUrl().newBuilder()
                            .addQueryParameter("search_query", query).addQueryParameter("sp", filter).build()
                        extractor.extract(url.toString(), true, 12).optJSONArray("entries")?.objects().orEmpty().mapNotNull { item ->
                            val raw = item.optString("url")
                            val channel = YouTubeUrl.channel(raw)
                            val canonical = channel ?: YouTubeUrl.playlistId(raw)?.let(YouTubeUrl::list) ?: return@mapNotNull null
                            PodcastCandidate(item.optString("title"), item.optString("channel").ifBlank { item.optString("uploader") },
                                canonical, item.picture(), if (channel != null) "YouTube · channel" else "YouTube · playlist")
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            } }.map { it.await() }
            if (batches.all { it == null }) throw IOException("YouTube search is unavailable. Please retry.")
            batches.filterNotNull().flatten().distinctBy { it.feedUrl }
        }
    } ?: throw IOException("YouTube search timed out. Try again later.")

    suspend fun feed(id: String, raw: String): Feed = withTimeout(60_000) {
        runInterruptible(Dispatchers.IO) {
            val url = requireNotNull(YouTubeUrl.source(raw)) { "Use a public YouTube channel, playlist or video link" }
            val playlist = YouTubeUrl.playlistId(url) != null || YouTubeUrl.channel(url) != null
            val data = extractor.extract(url, playlist, 200)
            val entries = if (playlist) data.optJSONArray("entries")?.objects().orEmpty() else listOf(data)
            val episodes = entries.mapIndexedNotNull { index, item ->
                val videoId = YouTubeUrl.videoId(item.optString("url"))
                    ?: item.optString("id").takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) } ?: return@mapIndexedNotNull null
                if (item.optString("availability") in setOf("private", "premium_only", "subscriber_only", "needs_auth") ||
                    item.optString("live_status") in setOf("is_live", "is_upcoming")) return@mapIndexedNotNull null
                val watch = YouTubeUrl.watch(videoId)
                FeedEpisode(episodeId(id, videoId), videoId, item.optString("title"), watch,
                    item.optDouble("duration", 0.0).takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toLong() },
                    null, item.publishedAt(), item.text("description"), item.picture(), "video/youtube", webpageUrl = watch, sourceOrder = index)
            }.distinctBy { it.id }
            require(episodes.isNotEmpty()) { "This YouTube source has no available episodes" }
            val ordered = if (YouTubeUrl.channel(url) != null) episodes else episodes.sortedByDescending { it.publishedAt }
            // Flat extraction omits per-video dates and descriptions. Merge them after ordering so
            // partially available feed metadata never reorders a show.
            val known = if (playlist) sourceMetadata(url, data) else emptyMap()
            Feed(data.optString("title"), ordered.mapIndexed { index, episode ->
                val found = known[episode.guid]
                episode.copy(sourceOrder = index, publishedAt = found?.publishedAt ?: episode.publishedAt,
                    description = found?.description?.takeIf(String::isNotBlank) ?: episode.description)
            }, data.optString("channel").ifBlank { data.optString("uploader") },
                data.optString("description"), data.picture(), url)
        }
    }

    /** Best effort: an unavailable public feed leaves the refresh unchanged. */
    private fun sourceMetadata(url: String, data: JSONObject): Map<String, YouTubeVideoMetadata> {
        val fetcher = metadata ?: return emptyMap()
        val address = YouTubeUrl.playlistId(url)?.let(YouTubeMetadata::playlistFeed)
            ?: YouTubeMetadata.channelFeed(data.text("channel_id")) ?: return emptyMap()
        return try { YouTubeMetadata.parse(fetcher.fetch(address).inputStream()) }
        catch (e: InterruptedException) { throw e }
        catch (_: Exception) { emptyMap() }
    }

    /** Full single-video metadata for an episode outside the public feed window. */
    fun details(raw: String): YouTubeVideoMetadata {
        val id = requireNotNull(YouTubeUrl.videoId(raw)) { "Invalid YouTube episode" }
        val data = extractor.extract(YouTubeUrl.watch(id), false, 1)
        return YouTubeVideoMetadata(data.publishedAt(), data.text("description").trim())
    }

    // Memory-only cache, bounded by both signed URL expiry and a 30-minute cap; never sync CDN URLs.
    private val cache = linkedMapOf<String, Pair<Long, YouTubeStreams>>()
    private val inFlight = ConcurrentHashMap<String, FutureTask<YouTubeStreams>>()
    fun invalidate(raw: String) { YouTubeUrl.videoId(raw)?.let { synchronized(cache) { cache.remove(it) } } }
    fun streams(raw: String): YouTubeStreams {
        val id = requireNotNull(YouTubeUrl.videoId(raw)) { "Invalid YouTube episode" }
        val now = clock.elapsedNanos()
        synchronized(cache) { cache[id]?.takeIf { now < it.first }?.let { return it.second } }
        val created = FutureTask {
            val cached = synchronized(cache) { cache[id]?.takeIf { clock.elapsedNanos() < it.first }?.second }
            if (cached != null) return@FutureTask cached
            val parsed = selectStreams(extractor.extract(YouTubeUrl.watch(id), false, 1))
            synchronized(cache) {
                if (cache.size >= 8) cache.remove(cache.keys.first())
                val lifetime = listOfNotNull(parsed.audio, parsed.video).map { signed ->
                    val expiry = signed.toHttpUrl().queryParameter("expire")?.toLongOrNull()
                    if (expiry == null || expiry > Long.MAX_VALUE / 1000) 120_000L
                    else if (expiry <= 0) 0L
                    else (expiry * 1000 - clock.wallMillis() - 60_000L).coerceIn(0L, 30 * 60_000L)
                }.minOrNull() ?: 0L
                cache[id] = clock.elapsedNanos() + lifetime * 1_000_000 to parsed
            }
            parsed
        }
        val task = inFlight.putIfAbsent(id, created) ?: created
        if (task === created) created.run()
        try { return task.get() }
        catch (e: ExecutionException) { throw (e.cause ?: e) }
        finally { if (task.isDone) inFlight.remove(id, task) }
    }
}

internal fun selectStreams(data: JSONObject): YouTubeStreams {
    require(!data.optBoolean("is_live") && data.optString("live_status") != "is_upcoming") { "Live videos are not supported" }
    val formats = data.optJSONArray("formats")?.objects().orEmpty().filter { f ->
        !f.optBoolean("has_drm") && f.optString("protocol") == "https" &&
            runCatching { f.getString("url").toHttpUrl() }.getOrNull()?.let { u ->
                u.scheme == "https" && u.username.isEmpty() && u.password.isEmpty() &&
                    (u.host == "googlevideo.com" || u.host.endsWith(".googlevideo.com"))
            } == true
    }
    // yt-dlp marks original audio above auto-dubs with language_preference. Do not pick by locale.
    val audio = formats.filter { it.optString("vcodec") == "none" && it.optString("acodec") !in setOf("", "none") }
        .maxWithOrNull(compareBy<JSONObject> { it.optDouble("language_preference", -1.0) }
            .thenBy { if (it.optString("ext") == "m4a") 1 else 0 }.thenBy { it.optDouble("abr", 0.0) })
        ?: throw IOException("YouTube did not provide a supported audio stream")
    val video = formats.filter { it.optString("acodec") == "none" && it.optString("vcodec").startsWith("avc1") && it.optInt("height") in 1..720 }
        .maxByOrNull { it.optInt("height") }
    return YouTubeStreams(audio.getString("url"), video?.getString("url"))
}
