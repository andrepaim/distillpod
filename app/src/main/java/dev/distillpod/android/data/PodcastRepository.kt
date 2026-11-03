package dev.distillpod.android.data

import androidx.room.withTransaction
import dev.distillpod.core.episodeId
import dev.distillpod.core.parseFeed
import dev.distillpod.core.parseFeedStream
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant

data class PodcastCandidate(val title: String, val author: String, val feedUrl: String, val imageUrl: String?, val category: String = "")

fun publicHttpUrl(raw: String): HttpUrl = raw.trim().toHttpUrl().newBuilder().fragment(null).build().also {
    require(it.username.isEmpty() && it.password.isEmpty()) { "Feed URLs with embedded passwords are not supported" }
}

fun okhttp3.ResponseBody.boundedText(limit: Int = 10_000_000): String {
    require(contentLength() <= limit) { "Response exceeds the supported size" }
    val input = source()
    require(!input.request((limit + 1).toLong())) { "Response exceeds the supported size" }
    val buffer = input.readByteArray()
    return buffer.toString(contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
}

class PodcastRepository(private val db: AppDatabase, private val http: OkHttpClient,
    private val directory: HttpUrl = "https://itunes.apple.com/search".toHttpUrl()) {
    private val dao = db.library()
    suspend fun search(query: String): List<PodcastCandidate> = withContext(Dispatchers.IO) {
        require(query.trim().length in 2..200) { "Enter at least two characters" }
        val url = directory.newBuilder().addQueryParameter("term", query.trim()).addQueryParameter("media", "podcast")
            .addQueryParameter("entity", "podcast").addQueryParameter("country", "BR").addQueryParameter("limit", "30").build()
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, error: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val parsed = runCatching { response.use {
                        check(it.isSuccessful) { "Podcast search is unavailable. Try again later." }
                        val items = JSONObject(it.body!!.boundedText(3_000_000)).getJSONArray("results")
                        (0 until items.length()).mapNotNull { i ->
                            val item = items.getJSONObject(i)
                            val feed = runCatching { publicHttpUrl(item.optString("feedUrl")).toString() }.getOrNull() ?: return@mapNotNull null
                            PodcastCandidate(item.optString("collectionName").ifBlank { "Untitled podcast" }, item.optString("artistName"), feed,
                                listOf("artworkUrl600", "artworkUrl512", "artworkUrl100", "artworkUrl60").map { key -> item.optString(key) }
                                    .firstOrNull { image -> image.startsWith("https://", true) || image.startsWith("http://", true) }, item.optString("primaryGenreName"))
                        }.distinctBy { candidate -> candidate.feedUrl }
                    } }
                    if (continuation.isActive) continuation.resumeWith(parsed)
                }
            })
        }
    }

    suspend fun subscribe(rawUrl: String): String = withContext(Dispatchers.IO) {
        val url = publicHttpUrl(rawUrl).toString()
        val existing = dao.podcastByUrl(url)
        if (existing != null) {
            dao.putPodcast(existing.copy(subscribed = true))
            refresh(existing.id)
            return@withContext existing.id
        }
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "Could not load that feed. Check the URL and connection." }
            val finalUrl = response.request.url.toString()
            val existingRedirect = dao.podcastByUrl(finalUrl)
            val id = existingRedirect?.id ?: episodeId("podcast", finalUrl)
            val feed = response.body!!.byteStream().use { parseFeedStream(id, it, finalUrl) }
            db.withTransaction {
                dao.putPodcast(PodcastEntity(id, finalUrl, feed.title, feed.author, feed.description, feed.imageUrl, true,
                    response.header("ETag"), response.header("Last-Modified"), System.currentTimeMillis(), originalFeedUrl = url))
                saveEpisodes(id, feed.episodes)
            }
            id
        }
    }

    suspend fun refresh(id: String) = withContext(Dispatchers.IO) {
        val podcast = dao.podcast(id) ?: return@withContext
        try {
            val request = Request.Builder().url(publicHttpUrl(podcast.feedUrl)).apply {
                podcast.etag?.let { header("If-None-Match", it) }
                podcast.lastModified?.let { header("If-Modified-Since", it) }
            }.build()
            http.newCall(request).execute().use { response ->
                if (response.code == 304) { dao.putPodcast(podcast.copy(refreshedAt = System.currentTimeMillis(), error = null)); return@use }
                check(response.isSuccessful) { "Feed refresh failed" }
                val feed = response.body!!.byteStream().use { parseFeedStream(id, it, response.request.url.toString()) }
                db.withTransaction {
                    // Keep stable IDs and locally saved state when redirect/metadata changes.
                    val latest = dao.podcast(id) ?: return@withTransaction
                    dao.putPodcast(latest.copy(title = feed.title, author = feed.author, description = feed.description,
                        imageUrl = feed.imageUrl, etag = response.header("ETag"), lastModified = response.header("Last-Modified"),
                        refreshedAt = System.currentTimeMillis(), error = null))
                    saveEpisodes(id, feed.episodes)
                }
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            dao.podcast(id)?.let { dao.putPodcast(it.copy(error = "Refresh failed. Saved episodes are still available.")) }
            throw error
        }
    }

    private suspend fun saveEpisodes(podcastId: String, items: List<dev.distillpod.core.FeedEpisode>) {
        val saved = dao.episodesForPodcast(podcastId).associateBy { it.guid }
        val episodes = items.map { item ->
            val old = saved[item.guid]
            val changed = old != null && old.audioUrl != item.audioUrl
            if (changed) {
                dao.transcript(old!!.id)?.let { dao.putTranscript(it.copy(state = "stale", error = "The audio enclosure changed. Prepare the current source again.")) }
                dao.analysis(old.id)?.let { dao.putAnalysis(it.copy(state = "stale", error = "The audio enclosure changed. Analyze the current source again.")) }
            }
            val base = old ?: EpisodeEntity(item.id, podcastId, item.guid, item.title, item.audioUrl)
            base.copy(title = item.title, audioUrl = item.audioUrl, description = item.description,
                imageUrl = item.imageUrl, publishedAt = item.publishedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0) } ?: base.publishedAt,
                durationMs = item.durationMs ?: base.durationMs, mimeType = item.mimeType, transcriptUrl = item.transcriptUrl, transcriptType = item.transcriptType,
                localFile = if (changed) null else base.localFile, downloadState = if (changed) "none" else base.downloadState,
                downloadedBytes = if (changed) 0 else base.downloadedBytes, totalBytes = if (changed) 0 else base.totalBytes,
                etag = if (changed) null else base.etag, lastModified = if (changed) null else base.lastModified,
                error = if (changed) null else base.error,
                mediaFingerprint = if (changed || base.mediaFingerprint.isBlank()) episodeId("enclosure", item.audioUrl) else base.mediaFingerprint)
        }
        dao.putEpisodes(episodes)
    }

    suspend fun unsubscribe(id: String) { dao.podcast(id)?.let { dao.putPodcast(it.copy(subscribed = false)) } }
    suspend fun exportOpml(): String = dev.distillpod.core.writeOpml(dao.podcasts().filter { it.subscribed }.map { dev.distillpod.core.OpmlFeed(it.title, it.feedUrl) })
    suspend fun importOpml(xml: String): Int {
        val feeds = dev.distillpod.core.parseOpml(xml)
        var added = 0
        db.withTransaction {
            feeds.forEach { feed ->
                val url = publicHttpUrl(feed.url).toString()
                val old = dao.podcastByUrl(url)
                if (old == null) {
                    dao.putPodcast(PodcastEntity(episodeId("podcast", url), url, feed.title.ifBlank { publicHttpUrl(url).host }, originalFeedUrl = url))
                    added++
                } else if (!old.subscribed) { dao.putPodcast(old.copy(subscribed = true)); added++ }
            }
        }
        return added
    }
    suspend fun refreshAll() { dao.podcasts().filter { it.subscribed }.forEach { runCatching { refresh(it.id) } } }
}
