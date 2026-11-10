package dev.distillpod.android.data

import androidx.room.withTransaction
import dev.distillpod.core.episodeId
import dev.distillpod.core.parseFeed
import dev.distillpod.core.parseFeedStream
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import dev.distillpod.core.YouTubeUrl
import dev.distillpod.android.video.YouTubeSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant

data class PodcastCandidate(val title: String, val author: String, val feedUrl: String, val imageUrl: String?, val category: String = "")
data class ProviderSearchResult(val provider: String, val results: List<PodcastCandidate> = emptyList(), val failed: Boolean = false)

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
    private val directory: HttpUrl = "https://itunes.apple.com/search".toHttpUrl(),
    private val onSubscriptionChanged: () -> Unit = {},
    private val onFeedNeeded: (String) -> Unit = {},
    private val youtube: YouTubeSource? = null) {
    private val dao = db.library()
    private val subscriptionSync = dev.distillpod.android.sync.SubscriptionSyncStore(db)
    suspend fun search(query: String): List<PodcastCandidate> = withContext(Dispatchers.IO) {
        require(query.trim().length in 2..200) { "Enter at least two characters" }
        if (youtube == null) return@withContext searchDirectory(query)
        val received = java.util.concurrent.CopyOnWriteArrayList<ProviderSearchResult>()
        searchProviders(query, publish = { received.add(it) })
        val results = received.flatMap { it.results }.distinctBy { it.feedUrl }
            .sortedByDescending { it.title.equals(query.trim(), ignoreCase = true) }
        if (results.isEmpty() && received.any { it.failed }) throw java.io.IOException("Search could not finish. Please retry.")
        results
    }

    val searchProviderNames get() = if (youtube == null) setOf("apple") else setOf("apple", "youtube")

    /** Each provider publishes immediately; a slow or failed peer cannot hide its results. */
    suspend fun searchProviders(query: String, only: String? = null, publish: (ProviderSearchResult) -> Unit) = withContext(Dispatchers.IO) {
        require(query.trim().length in 2..200) { "Enter at least two characters" }
        require(only == null || only in searchProviderNames)
        coroutineScope {
            searchProviderNames.filter { only == null || it == only }.forEach { provider -> launch {
                val result = try {
                    val rows = withTimeout(if (provider == "apple") 30_000L else 45_000L) {
                        if (provider == "apple") searchDirectory(query) else youtube!!.search(query.trim())
                    }
                    ProviderSearchResult(provider, rows)
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    ProviderSearchResult(provider, failed = true)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { ProviderSearchResult(provider, failed = true) }
                publish(result)
            } }
        }
    }

    private suspend fun searchDirectory(query: String): List<PodcastCandidate> {
        val url = directory.newBuilder().addQueryParameter("term", query.trim()).addQueryParameter("media", "podcast")
            .addQueryParameter("entity", "podcast").addQueryParameter("country", "BR").addQueryParameter("limit", "30").build()
        return suspendCancellableCoroutine { continuation ->
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

    suspend fun follow(candidate: PodcastCandidate): String = withContext(Dispatchers.IO) {
        if (YouTubeUrl.source(candidate.feedUrl) != null) return@withContext subscribe(candidate.feedUrl)
        val url=publicHttpUrl(candidate.feedUrl).toString()
        val old=dao.podcastByUrl(url)
        val value=old?.copy(subscribed=true) ?: PodcastEntity(episodeId("podcast",url),url,candidate.title,
            author=candidate.author,imageUrl=candidate.imageUrl,originalFeedUrl=url)
        subscriptionSync.follow(value,publicSource=true)
        onFeedNeeded(value.id)
        onSubscriptionChanged()
        value.id
    }

    suspend fun subscribe(rawUrl: String): String = withContext(Dispatchers.IO) {
        val youtubeUrl = YouTubeUrl.source(rawUrl)
        val url = youtubeUrl ?: publicHttpUrl(rawUrl).toString()
        val existing = dao.podcastByUrl(url)
        if (existing != null) {
            subscriptionSync.follow(existing.copy(subscribed = true))
            onSubscriptionChanged()
            try { refresh(existing.id) } catch(cancelled:kotlinx.coroutines.CancellationException) { throw cancelled } catch(_:Exception) { /* Follow remains saved offline. */ }
            return@withContext existing.id
        }
        if (youtubeUrl != null) {
            val source = requireNotNull(youtube) { "YouTube support is unavailable" }
            val id = episodeId("podcast", url)
            val feed = source.feed(id, url)
            db.withTransaction {
                subscriptionSync.follow(PodcastEntity(id, url, feed.title, feed.author, feed.description, feed.imageUrl,
                    originalFeedUrl = url, websiteUrl = url, refreshedAt = System.currentTimeMillis()))
                saveEpisodes(id, feed.episodes)
            }
            onSubscriptionChanged()
            return@withContext id
        }
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "Could not load that feed. Check the URL and connection." }
            val finalUrl = response.request.url.toString()
            val existingRedirect = dao.podcastByUrl(finalUrl)
            val id = existingRedirect?.id ?: episodeId("podcast", finalUrl)
            val feed = response.body!!.byteStream().use { parseFeedStream(id, it, finalUrl) }
            db.withTransaction {
                subscriptionSync.follow(PodcastEntity(id, finalUrl, feed.title, feed.author, feed.description, feed.imageUrl, true,
                    response.header("ETag"), response.header("Last-Modified"), System.currentTimeMillis(), originalFeedUrl = url, websiteUrl = feed.websiteUrl))
                saveEpisodes(id, feed.episodes)
            }
            onSubscriptionChanged()
            id
        }
    }

    suspend fun refresh(id: String) = withContext(Dispatchers.IO) {
        val podcast = dao.podcast(id) ?: return@withContext
        try {
            YouTubeUrl.source(podcast.feedUrl)?.let { url ->
                val feed = requireNotNull(youtube) { "YouTube support is unavailable" }.feed(id, url)
                db.withTransaction {
                    val latest = dao.podcast(id) ?: return@withTransaction
                    dao.putPodcast(latest.copy(title = feed.title, author = feed.author, description = feed.description,
                        imageUrl = feed.imageUrl, websiteUrl = url, refreshedAt = System.currentTimeMillis(), error = null))
                    saveEpisodes(id, feed.episodes)
                }
                return@withContext
            }
            val request = Request.Builder().url(publicHttpUrl(podcast.feedUrl)).apply {
                podcast.etag?.let { header("If-None-Match", it) }
                podcast.lastModified?.let { header("If-Modified-Since", it) }
            }.build()
            http.newCall(request).execute().use { response ->
                if (response.code == 304) { initializeAutomation(id); dao.putPodcast(podcast.copy(refreshedAt = System.currentTimeMillis(), error = null)); return@use }
                check(response.isSuccessful) { "Feed refresh failed" }
                val feed = response.body!!.byteStream().use { parseFeedStream(id, it, response.request.url.toString()) }
                db.withTransaction {
                    // Keep stable IDs and locally saved state when redirect/metadata changes.
                    val latest = dao.podcast(id) ?: return@withTransaction
                    dao.putPodcast(latest.copy(title = feed.title, author = feed.author, description = feed.description,
                        imageUrl = feed.imageUrl, websiteUrl = feed.websiteUrl, etag = response.header("ETag"), lastModified = response.header("Last-Modified"),
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
            base.copy(webpageUrl = item.webpageUrl ?: base.webpageUrl, title = item.title, audioUrl = item.audioUrl, description = item.description,
                imageUrl = item.imageUrl, sourceOrder = item.sourceOrder ?: base.sourceOrder, publishedAt = item.publishedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0) } ?: base.publishedAt,
                durationMs = item.durationMs ?: base.durationMs, mimeType = item.mimeType, transcriptUrl = item.transcriptUrl, transcriptType = item.transcriptType,
                localFile = if (changed) null else base.localFile, downloadState = if (changed) "none" else base.downloadState,
                localDownloadedAt = if (changed) 0 else base.localDownloadedAt,
                downloadedBytes = if (changed) 0 else base.downloadedBytes, totalBytes = if (changed) 0 else base.totalBytes,
                etag = if (changed) null else base.etag, lastModified = if (changed) null else base.lastModified,
                error = if (changed) null else base.error,
                mediaFingerprint = if (changed || base.mediaFingerprint.isBlank()) episodeId("enclosure", item.audioUrl) else base.mediaFingerprint)
        }
        // Entries outside a bounded refresh remain available, after the current snapshot.
        // Never delete archived episodes or reset their listening/download state.
        val present = items.map { it.guid }.toSet()
        val retained = if (items.any { it.sourceOrder != null }) saved.values.filter { it.guid !in present }
            .sortedWith(compareBy<EpisodeEntity> { it.sourceOrder ?: Int.MAX_VALUE }.thenBy { it.id })
            .mapIndexed { index, episode -> episode.copy(sourceOrder = items.size + index) } else emptyList()
        dao.putEpisodes(episodes + retained)
        val automation = db.automation().show(podcastId)
        if (automation != null) {
            if (automation.initialized) episodes.filter { it.guid !in saved && (it.publishedAt == 0L || it.publishedAt >= automation.enabledAt) }
                .forEach { db.automation().insertJob(EpisodeAutomationEntity(it.id, podcastId)) }
            initializeAutomation(podcastId)
        }
    }

    private suspend fun initializeAutomation(id: String) {
        db.automation().initialize(id)
    }
    suspend fun unsubscribe(id: String) { dao.podcast(id)?.let { subscriptionSync.follow(it.copy(subscribed = false)); onSubscriptionChanged() } }
    suspend fun exportOpml(): String = dev.distillpod.core.writeOpml(dao.podcasts().filter { it.subscribed }.map { dev.distillpod.core.OpmlFeed(it.title, it.feedUrl) })
    suspend fun importOpml(xml: String): Int {
        val feeds = dev.distillpod.core.parseOpml(xml)
        var added = 0
        db.withTransaction {
            feeds.forEach { feed ->
                val url = publicHttpUrl(feed.url).toString()
                val old = dao.podcastByUrl(url)
                if (old == null) {
                    subscriptionSync.follow(PodcastEntity(episodeId("podcast", url), url, feed.title.ifBlank { publicHttpUrl(url).host }, originalFeedUrl = url))
                    added++
                } else if (!old.subscribed) { subscriptionSync.follow(old.copy(subscribed = true)); added++ }
            }
        }
        if(added>0) onSubscriptionChanged()
        return added
    }
    suspend fun refreshAll() { dao.podcasts().filter { it.subscribed }.forEach { runCatching { refresh(it.id) } } }
}
