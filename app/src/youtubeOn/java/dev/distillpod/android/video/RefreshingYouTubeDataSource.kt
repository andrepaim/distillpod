package dev.distillpod.android.video

import android.net.Uri
import androidx.media3.datasource.*

/** Retry an expired URL once. Every reopened stream still uses bounded byte ranges. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class RefreshingYouTubeDataSource(
    private val youtube: YouTubeSource,
    private val factory: HttpDataSource.Factory,
) : DataSource {
    private var upstream: DataSource = BoundedRangeDataSource(factory.createDataSource())
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(listener: TransferListener) { listeners += listener; upstream.addTransferListener(listener) }
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders() = upstream.responseHeaders
    override fun open(dataSpec: DataSpec): Long {
        val canonical = dataSpec.uri.toString()
        repeat(2) { attempt ->
            val streams = youtube.streams(canonical)
            val url = if (dataSpec.uri.getQueryParameter("distillpod_track") == "video")
                streams.video ?: throw YouTubeFailure("formats") else streams.audio
            try { return upstream.open(dataSpec.withUri(Uri.parse(url))) }
            catch (error: HttpDataSource.InvalidResponseCodeException) {
                upstream.close()
                if (attempt != 0 || error.responseCode !in setOf(401, 403, 410)) throw error
                youtube.invalidate(canonical)
                youtube.refreshed()
                upstream = BoundedRangeDataSource(factory.createDataSource()).also { source -> listeners.forEach(source::addTransferListener) }
            }
        }
        error("Unreachable stream state")
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = upstream.read(buffer, offset, length)
    override fun close() = upstream.close()
}
