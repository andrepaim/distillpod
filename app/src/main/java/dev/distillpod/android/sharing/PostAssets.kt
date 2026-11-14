package dev.distillpod.android.sharing

import android.graphics.BitmapFactory
import dev.distillpod.android.data.CardContent
import java.io.File
import java.security.MessageDigest

/**
 * Immutable, content-addressed images: 1080x1350 earlier infographics and square card covers. Card images
 * themselves are drawn on demand and never stored. Paths supplied by backups never enter the filesystem.
 */
class PostAssets(private val directory: File) {
    fun file(hash: String): File { require(hash.matches(Regex("[a-f0-9]{64}"))); return File(directory, "$hash.png") }
    fun put(bytes: ByteArray): String {
        validate(bytes)
        val hash = hash(bytes)
        directory.mkdirs()
        val target = file(hash)
        if (target.exists()) { require(hash(target.readBytes()) == hash); return hash }
        val temp = File.createTempFile("image-", ".tmp", directory)
        try { temp.outputStream().use { it.write(bytes); it.fd.sync() }; check(temp.renameTo(target)) { "Could not save post image" } }
        finally { temp.delete() }
        return hash
    }
    fun read(hash: String): ByteArray = file(hash).readBytes().also { require(hash(it) == hash); validate(it) }
    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        private val SHAPES = setOf(1080 to 1350, CardContent.COVER_PX to CardContent.COVER_PX)
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun validate(bytes: ByteArray) {
            require(bytes.size in 1..MAX_BYTES) { "Image exceeds 8 MB" }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            require((options.outWidth to options.outHeight) in SHAPES && options.outMimeType == "image/png") { "Invalid post image" }
        }
    }
}
