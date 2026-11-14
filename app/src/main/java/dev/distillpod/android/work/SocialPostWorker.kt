package dev.distillpod.android.work

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import androidx.work.*
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import dev.distillpod.android.appGraph
import dev.distillpod.android.data.*
import dev.distillpod.android.features.cardSwatch
import dev.distillpod.android.sharing.*
import kotlinx.coroutines.*

object SocialPostJobs {
    fun enqueue(context: Context, id: String, attempt: String) {
        WorkManager.getInstance(context).enqueueUniqueWork("social:$id", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SocialPostWorker>().setInputData(workDataOf("post" to id, "attempt" to attempt))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun cancel(context: Context, id: String) { WorkManager.getInstance(context).cancelUniqueWork("social:$id") }
}
class SocialPostWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.appGraph
        graph.ready.await()
        val post = inputData.getString("post") ?: return Result.failure()
        val attempt = inputData.getString("attempt") ?: return Result.failure()
        val result = graph.socialResults.get(post) ?: return Result.success()
        if (result.attempt != attempt || result.stage !in SocialResult.ACTIVE_STAGES) return Result.success()
        if (result.stage != "queued") {
            graph.socialResults.update(post,attempt) { it.copy(stage = "failed", error = "Creation was interrupted. Your last version is safe. Retry when ready.") }
            return Result.success()
        }
        return try {
            setForeground(workForeground(applicationContext, id, "Creating your post"))
            SocialPostGenerator(graph.socialResults, SocialComposer(graph.ai), graph.postAssets, CoilCoverLoader(applicationContext)).run(post,attempt)
            Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { graph.socialResults.update(post,attempt) { it.copy(stage = "canceled") } }
            throw cancelled
        } catch (error: Exception) {
            graph.socialResults.update(post,attempt) { it.copy(stage = "failed", error = (if (error is SocialGenerationFailure) error.message.orEmpty() else safeFailure(error)).take(400)) }
            Result.success() // Never silently retry a request that uses the owner's AI allowance.
        }
    }
}

/** Loads podcast artwork for a card cover. Null means no cover; it never fails the post. */
fun interface CoverLoader { suspend fun load(url: String): Bitmap? }

/** Best effort through Coil's caches, which normally already hold the cover shown in the app. */
class CoilCoverLoader(private val context: Context, private val timeoutMs: Long = 15_000) : CoverLoader {
    override suspend fun load(url: String): Bitmap? = try {
        withTimeoutOrNull(timeoutMs) {
            val request = ImageRequest.Builder(context).data(url).size(CardContent.COVER_PX).scale(Scale.FILL).allowHardware(false).build()
            (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
        }
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive(); null
    } catch (_: Exception) {
        null
    }
}

/** A stored square cover and the opaque colour the looks derive their tones from. */
data class StoredCover(val hash: String, val tint: Int)

/** Centre-crops [artwork] to the stored cover size. Null when no representative colour can be found. */
internal fun storeCover(artwork: Bitmap, assets: PostAssets): StoredCover? {
    val side = minOf(artwork.width, artwork.height)
    if (side <= 0) return null
    val left = (artwork.width - side) / 2; val top = (artwork.height - side) / 2
    val cover = Bitmap.createBitmap(CardContent.COVER_PX, CardContent.COVER_PX, Bitmap.Config.ARGB_8888)
    try {
        Canvas(cover).drawBitmap(artwork, Rect(left, top, left + side, top + side), Rect(0, 0, CardContent.COVER_PX, CardContent.COVER_PX), Paint(Paint.FILTER_BITMAP_FLAG))
        val tint = cardSwatch(Palette.from(cover).maximumColorCount(16).generate())?.let { it or 0xFF000000.toInt() } ?: return null
        val png = java.io.ByteArrayOutputStream().use { check(cover.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray() }
        return StoredCover(assets.put(png), tint)
    } finally { cover.recycle() }
}

class SocialPostGenerator(private val repository: SocialResultRepository, private val composer: CardComposer, private val assets: PostAssets,
    private val covers: CoverLoader) {
    suspend fun run(id: String, attempt: String) = withContext(Dispatchers.IO) {
        check(repository.update(id,attempt) { it.copy(stage = "preparing") }) { "This generation was superseded" }
        val result = repository.get(id)!!
        val composed = composer.card(result)
        val previous = result.artifact?.cardContent()
        val cover = previous?.takeIf { it.cover.isNotEmpty() }?.let { StoredCover(it.cover, it.tint) } ?: loadCover(result.source.artworkUrl)
        currentCoroutineContext().ensureActive()
        val hash = cover?.hash.orEmpty()
        // A Change keeps the chosen style and look; a first card opens on the defaults.
        val style = previous?.style?.takeIf { it in CardContent.STYLES } ?: CardContent.defaultStyle(composed.card.quote)
        val look = previous?.look?.takeIf { it in CardContent.LOOKS && (it != CardContent.LOOK_COVER || hash.isNotEmpty()) } ?: CardContent.defaultLook(hash)
        val card = composed.card.copy(style = style, look = look, cover = hash, tint = cover?.tint ?: 0).validated(result.source.excerpt)
        val artifact = SocialArtifact(text = composed.fullCaption(result.source), quote = card.quote, title = card.headline, card = card.json().toString())
        repository.update(id,attempt) { it.copy(stage = "ready", artifact = artifact, previous = it.artifact, error = "") }
    }

    private suspend fun loadCover(url: String): StoredCover? {
        if (url.isBlank()) return null
        val artwork = covers.load(url) ?: return null
        return try { storeCover(artwork, assets) } catch (e: Exception) { if (e is CancellationException) throw e; null }
    }
}
