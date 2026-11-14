package dev.distillpod.android.sharing

import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import dev.distillpod.android.data.CardContent
import dev.distillpod.android.data.CardSize
import dev.distillpod.android.data.SocialArtifact
import dev.distillpod.android.data.SocialResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object PostShareController {
    /**
     * Sends the card as a PNG with the caption as share text, and copies the caption too: image-sharing
     * apps commonly drop the share text, so the user can paste it.
     */
    suspend fun shareCard(context: Context, assets: PostAssets, result: SocialResult, size: CardSize = CardSize.Feed) {
        val artifact = result.artifact ?: error("This post is still being prepared")
        val card = artifact.cardContent() ?: error("This post has no card")
        val bytes = cardPng(context, assets, result, card, size)
        val uri = export(context, bytes)
        val intent = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, artifact.text).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The same shape Android builds from EXTRA_STREAM itself, so targets reading the clip also get the caption.
        intent.clipData = ClipData("Post image", arrayOf("image/png"), ClipData.Item(artifact.text, null, null, uri))
        copy(context, artifact.text)
        context.startActivity(Intent.createChooser(intent, "Share post"))
    }

    /** Writes the feed-size card to Pictures/DistillPod. */
    suspend fun saveCard(context: Context, assets: PostAssets, result: SocialResult) {
        val card = result.artifact?.cardContent() ?: error("This post has no card")
        savePng(context, cardPng(context, assets, result, card, CardSize.Feed))
    }

    /** Earlier results: an infographic shares its stored image, a text post plain text. */
    suspend fun shareResult(context: Context, assets: PostAssets, result: SocialResult) {
        val artifact = result.artifact ?: error("This post is still being prepared")
        if (artifact.card.isNotEmpty()) return shareCard(context, assets, result)
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, result.shareText())
        if (artifact.image.isNotEmpty()) {
            val uri = export(context, withContext(Dispatchers.IO) { assets.read(artifact.image) })
            intent.type = "image/png"
            intent.putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newUri(context.contentResolver, "Infographic", uri)
        } else intent.type = "text/plain"
        context.startActivity(Intent.createChooser(intent, "Share post"))
    }

    suspend fun saveResult(context: Context, assets: PostAssets, artifact: SocialArtifact) =
        savePng(context, withContext(Dispatchers.IO) { assets.read(artifact.image) })

    fun copy(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("DistillPod post", text))
    }

    /** The stored square cover, or null when there is none or it cannot be read; the card then draws as Ember. */
    fun cover(assets: PostAssets, hash: String): Bitmap? = hash.takeIf { it.isNotEmpty() }?.let {
        runCatching { assets.read(it).let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } }.getOrNull()
    }

    private suspend fun cardPng(context: Context, assets: PostAssets, result: SocialResult, card: CardContent, size: CardSize): ByteArray =
        withContext(Dispatchers.Default) {
            val cover = withContext(Dispatchers.IO) { cover(assets, card.cover) }
            try {
                val bitmap = ShareCardRenderer.render(context, card, result.source.show, result.source.episode, cover, size)
                try { ShareCardRenderer.png(bitmap) } finally { bitmap.recycle() }
            } finally { cover?.recycle() }
        }

    private suspend fun export(context: Context, bytes: ByteArray): Uri {
        val file = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "post-exports").apply { mkdirs() }
            directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7 * 86400000L }?.forEach { it.delete() }
            File(directory, "post-${UUID.randomUUID()}.png").apply { writeBytes(bytes) }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.post-files", file)
    }

    private suspend fun savePng(context: Context, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "distillpod-${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DistillPod")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not save image")
        try {
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }
}
