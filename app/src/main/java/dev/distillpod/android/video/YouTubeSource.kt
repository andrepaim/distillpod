package dev.distillpod.android.video

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dev.distillpod.android.data.PodcastCandidate
import dev.distillpod.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

fun interface YouTubeExtractor {
    fun extract(url: String, flat: Boolean, limit: Int): JSONObject
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
private fun JSONObject.picture(): String? = optJSONArray("thumbnails")?.objects()
    ?.lastOrNull()?.optString("url")?.takeIf { it.startsWith("https://") }

data class YouTubeStreams(val audio: String, val video: String?)

/** Network extraction is injectable; parsing and identity are shared by live and fixture tests. */
class YouTubeSource(private val extractor: YouTubeExtractor) {
    var onStreamRefresh: () -> Unit = {}
    internal fun refreshed() { runCatching { onStreamRefresh() } }
    suspend fun search(query: String): List<PodcastCandidate> = withTimeoutOrNull(45_000) {
        runInterruptible(Dispatchers.IO) {
            val url = "https://www.youtube.com/results".toHttpUrl().newBuilder()
                .addQueryParameter("search_query", query).addQueryParameter("sp", "EgIQAw%3D%3D").build()
            val data = extractor.extract(url.toString(), true, 12)
            data.optJSONArray("entries")?.objects().orEmpty().mapNotNull { item ->
                val canonical = YouTubeUrl.playlistId(item.optString("url"))?.let(YouTubeUrl::list) ?: return@mapNotNull null
                PodcastCandidate(item.optString("title"), item.optString("channel").ifBlank { item.optString("uploader") },
                    canonical, item.picture(), "YouTube · videocast")
            }.distinctBy { it.feedUrl }
        }
    } ?: throw IOException("YouTube search timed out. Try again later.")

    suspend fun feed(id: String, raw: String): Feed = withTimeout(60_000) {
        runInterruptible(Dispatchers.IO) {
            val url = requireNotNull(YouTubeUrl.source(raw)) { "Use a public YouTube playlist or video link" }
            val playlist = YouTubeUrl.playlistId(url) != null
            val data = extractor.extract(url, playlist, 200)
            val entries = if (playlist) data.optJSONArray("entries")?.objects().orEmpty() else listOf(data)
            val episodes = entries.mapNotNull { item ->
                val videoId = YouTubeUrl.videoId(item.optString("url"))
                    ?: item.optString("id").takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) } ?: return@mapNotNull null
                if (item.optString("availability") in setOf("private", "premium_only", "subscriber_only", "needs_auth") ||
                    item.optString("live_status") in setOf("is_live", "is_upcoming")) return@mapNotNull null
                val watch = YouTubeUrl.watch(videoId)
                val published = runCatching {
                    LocalDate.parse(item.getString("upload_date"), DateTimeFormatter.BASIC_ISO_DATE)
                        .atStartOfDay().toInstant(ZoneOffset.UTC).toString()
                }.getOrNull()
                FeedEpisode(episodeId(id, videoId), videoId, item.optString("title"), watch,
                    item.optDouble("duration", 0.0).takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toLong() },
                    null, published, item.optString("description"), item.picture(), "video/youtube", webpageUrl = watch)
            }.distinctBy { it.id }
            require(episodes.isNotEmpty()) { "This YouTube source has no available episodes" }
            Feed(data.optString("title"), episodes, data.optString("channel").ifBlank { data.optString("uploader") },
                data.optString("description"), data.picture(), url)
        }
    }

    // Short-lived, memory-only cache. Re-resolve on later playback/download; never sync signed URLs.
    private val cache = linkedMapOf<String, Pair<Long, YouTubeStreams>>()
    private val inFlight = ConcurrentHashMap<String, FutureTask<YouTubeStreams>>()
    fun invalidate(raw: String) { YouTubeUrl.videoId(raw)?.let { synchronized(cache) { cache.remove(it) } } }
    fun streams(raw: String): YouTubeStreams {
        val id = requireNotNull(YouTubeUrl.videoId(raw)) { "Invalid YouTube episode" }
        val now = System.nanoTime()
        synchronized(cache) { cache[id]?.takeIf { now - it.first < 120_000_000_000L }?.let { return it.second } }
        val created = FutureTask {
            val cached = synchronized(cache) { cache[id]?.takeIf { System.nanoTime() - it.first < 120_000_000_000L }?.second }
            if (cached != null) return@FutureTask cached
            val parsed = selectStreams(extractor.extract(YouTubeUrl.watch(id), false, 1))
            synchronized(cache) {
                if (cache.size >= 8) cache.remove(cache.keys.first())
                cache[id] = System.nanoTime() to parsed
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
