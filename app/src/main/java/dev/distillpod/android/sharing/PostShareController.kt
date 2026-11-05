package dev.distillpod.android.sharing

import android.content.*
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.core.content.FileProvider
import dev.distillpod.android.data.PostContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object PostShareController {
    fun text(context: Context, post: PostContent) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, post.validated().shareText()), "Share post"))
    }
    fun copy(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("DistillPod post", text))
    }
    suspend fun image(context: Context, post: PostContent) {
        val file = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "post-exports").apply { mkdirs() }
            directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7 * 86400000L }?.forEach { it.delete() }
            val output = File(directory, "post-${UUID.randomUUID()}.png")
            val bitmap = PostCardRenderer.render(post)
            try { output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() }
            output
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.post-files", file)
        val intent = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, post.shareText()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newUri(context.contentResolver, "Post image", uri)
        context.startActivity(Intent.createChooser(intent, "Share post image"))
    }
    suspend fun save(context: Context, post: PostContent) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "distillpod-${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DistillPod")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not save image")
        try {
            val bitmap = PostCardRenderer.render(post)
            try { resolver.openOutputStream(uri)!!.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
        } catch (error: Exception) { resolver.delete(uri, null, null); throw error }
    }
}
