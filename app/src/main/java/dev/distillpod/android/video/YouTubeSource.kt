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

fun interface YouTubeExtractor {
    fun extract(url: String, flat: Boolean, limit: Int): JSONObject
}

/** No account, cookies, API key, remote service or runtime code update. */
class AndroidYouTubeExtractor(private val context: Context) : YouTubeExtractor {
    override fun extract(url: String, flat: Boolean, limit: Int): JSONObject {
        YoutubeDL.getInstance().init(context)
        val request = YoutubeDLRequest(url).addOption("--ignore-config").addOption("--no-update")
            .addOption("--skip-download").addOption("--dump-single-json")
            .addOption("--socket-timeout", 15).addOption("--retries", 1).addOption("--extractor-retries", 1)
        if (flat) request.addOption("--flat-playlist").addOption("--playlist-end", limit)
        else request.addOption("--no-playlist")
        val response = try { YoutubeDL.getInstance().execute(request, null, null) }
        catch (e: InterruptedException) { throw e }
        catch (_: Exception) { throw IOException("YouTube is unavailable. Try again later.") }
        require(response.out.length <= 10_000_000) { "YouTube response is too large" }
        return JSONObject(response.out)
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONObject.picture(): String? = optJSONArray("thumbnails")?.objects()
    ?.lastOrNull()?.optString("url")?.takeIf { it.startsWith("https://") }

data class YouTubeStreams(val audio: String, val video: String?)

/** Network extraction is injectable; parsing and identity are shared by live and fixture tests. */
class YouTubeSource(private val extractor: YouTubeExtractor) {
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
    @Synchronized fun streams(raw: String): YouTubeStreams {
        val id = requireNotNull(YouTubeUrl.videoId(raw)) { "Invalid YouTube episode" }
        val now = System.nanoTime()
        cache[id]?.takeIf { now - it.first < 120_000_000_000L }?.let { return it.second }
        val parsed = selectStreams(extractor.extract(YouTubeUrl.watch(id), false, 1))
        if (cache.size >= 8) cache.remove(cache.keys.first())
        cache[id] = System.nanoTime() to parsed
        return parsed
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
