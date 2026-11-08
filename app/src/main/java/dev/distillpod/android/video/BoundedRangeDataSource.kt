package dev.distillpod.android.video

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.io.IOException

// Some audio CDN responses reject 5 MiB ranges even when 1 MiB ranges succeed.
internal const val YOUTUBE_RANGE_BYTES = 1024L * 1024

/** YouTube's CDN requires bounded ranges, including the first request and after a seek. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class BoundedRangeDataSource(
    private val upstream: HttpDataSource,
    private val chunkBytes: Long = YOUTUBE_RANGE_BYTES,
) : DataSource {
    init { require(chunkBytes > 0) }
    private var spec: DataSpec? = null
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var total: Long? = null
    private var chunkRemaining = 0L
    private var opened = false
    private var validator: String? = null

    override fun addTransferListener(listener: TransferListener) = upstream.addTransferListener(listener)
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        position = dataSpec.position
        remaining = dataSpec.length
        total = null
        validator = null
        openChunk()
        val available = total?.let { (it - position).coerceAtLeast(0) }
        return if (remaining == C.LENGTH_UNSET.toLong()) available ?: C.LENGTH_UNSET.toLong()
            else available?.let { minOf(it, remaining) } ?: remaining
    }

    private fun openChunk() {
        val length = minOf(chunkBytes, remaining.takeIf { it >= 0 } ?: Long.MAX_VALUE,
            total?.let { it - position } ?: Long.MAX_VALUE)
        if (length <= 0) { chunkRemaining = 0; return }
        opened = true
        upstream.open(checkNotNull(spec).buildUpon().setPosition(position).setLength(length).build())
        fun header(name: String) = upstream.responseHeaders.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
        val nextValidator = header("ETag")?.takeUnless { it.startsWith("W/") } ?: header("Last-Modified")
        if (validator != null && validator != nextValidator) throw IOException("Media server changed its representation")
        validator = nextValidator
        if (upstream.responseCode == 206) {
            val range = header("Content-Range")?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it) }
                ?: throw IOException("Media server returned an invalid byte range")
            val (start, end, size) = range.destructured.toList().map { it.toLong() }
            if (start != position || end < start || end >= size || end - start >= length || (total != null && total != size))
                throw IOException("Media server changed its byte range")
            total = size
            chunkRemaining = end - start + 1
        } else {
            // A server which ignores Range still gets read at the correct offset by Media3.
            header("Content-Length")?.toLongOrNull()?.let {
                if (total != null && total != it) throw IOException("Media server changed its length")
                total = it
            }
            chunkRemaining = minOf(length, total?.let { (it - position).coerceAtLeast(0) } ?: length)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        checkNotNull(spec)
        if (remaining == 0L || total?.let { position >= it } == true) return C.RESULT_END_OF_INPUT
        if (chunkRemaining == 0L) {
            upstream.close(); opened = false
            openChunk()
        }
        val count = upstream.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
        if (count == C.RESULT_END_OF_INPUT) throw EOFException("Media range ended before completion")
        position += count
        chunkRemaining -= count
        if (remaining >= 0) remaining -= count
        return count
    }

    override fun close() {
        spec = null
        if (opened) { opened = false; upstream.close() }
    }
}
