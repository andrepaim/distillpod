package dev.distillpod.android.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import dev.distillpod.android.data.AudioFileOwnership
import java.io.IOException

/** Keeps readers alive independently of playlist membership, including loader cancellation. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OwnedAudioDataSource(
    private val selection: AudioPlaybackSelection,
    private val ownership: AudioFileOwnership,
    private val delegate: DataSource,
) : DataSource {
    private var lease: AudioFileOwnership.Lease? = null
    private var active = false

    override fun addTransferListener(listener: TransferListener) = delegate.addTransferListener(listener)

    override fun open(spec: DataSpec): Long {
        check(!active) { "Audio source is already open" }
        if (spec.uri != selection.uri) {
            throw IOException("Local audio source changed")
        }
        val acquired = ownership.acquire(selection.copy)
            ?: throw IOException("Local audio copy was removed")
        lease = acquired
        active = true
        try {
            return delegate.open(spec)
        } catch (failure: Throwable) {
            try {
                delegate.close()
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            } finally {
                active = false
                lease = null
                acquired.close()
            }
            throw failure
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(active) { "Audio source is not open" }
        return delegate.read(buffer, offset, length)
    }
    override fun getUri(): Uri? = if (active) delegate.uri else null
    override fun getResponseHeaders(): Map<String, List<String>> =
        if (active) delegate.responseHeaders else emptyMap()

    override fun close() {
        if (!active) return
        active = false
        val acquired = lease
        lease = null
        try {
            delegate.close()
        } finally {
            acquired?.close()
        }
    }
}
