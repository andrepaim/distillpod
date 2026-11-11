package dev.distillpod.android

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import dev.distillpod.android.data.AudioFileOwnership
import dev.distillpod.android.playback.AudioPlaybackSelection
import dev.distillpod.android.playback.OwnedAudioDataSource
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OwnedAudioDataSourceTest {
    private val path = "/managed/audio"
    private val uri = Uri.parse("file://$path")
    private fun selection(ownership: AudioFileOwnership) =
        AudioPlaybackSelection(ownership.select(path)!!, Uri.parse("https://example.test/audio"))

    @Test fun readerKeepsTheCopyAfterQueueRemovalUntilClose() {
        val ownership = AudioFileOwnership()
        val selected = selection(ownership)
        val queue = ownership.acquire(selected.copy)!!
        val source = OwnedAudioDataSource(selected, ownership, ProbeSource())
        source.open(DataSpec(uri))
        queue.close()
        assertNull(ownership.reserve(listOf(path)))
        source.close()
        ownership.reserve(listOf(path))!!.close()
        source.close()
    }

    @Test fun retiredRemovedLoaderCannotOpenTheOldCopy() {
        val ownership = AudioFileOwnership()
        val selected = selection(ownership)
        ownership.reserve(listOf(path))!!.close()
        val delegate = ProbeSource()
        val source = OwnedAudioDataSource(selected, ownership, delegate)
        assertTrue(runCatching { source.open(DataSpec(uri)) }.exceptionOrNull() is IOException)
        assertNull(delegate.opened)
        source.close()
        assertEquals(0, delegate.closes)
    }

    @Test fun failedOpenKeepsOriginalFailureAndCleansItsPartialEffects() {
        val ownership = AudioFileOwnership()
        val selected = selection(ownership)
        val expected = IOException("Synthetic open failure")
        val delegate = ProbeSource(openFailure = expected)
        val source = OwnedAudioDataSource(selected, ownership, delegate)
        assertSame(expected, runCatching { source.open(DataSpec(uri)) }.exceptionOrNull())
        source.close()
        assertEquals(1, delegate.closes)
        ownership.reserve(listOf(path))!!.close()
    }

    @Test fun failedCloseStillReleasesOwnershipAndRepeatedCloseIsHarmless() {
        val ownership = AudioFileOwnership()
        val expected = IOException("Synthetic close failure")
        val delegate = ProbeSource(closeFailure = expected)
        val source = OwnedAudioDataSource(selection(ownership), ownership, delegate)
        source.open(DataSpec(uri))
        assertNull(ownership.reserve(listOf(path)))
        assertSame(expected, runCatching { source.close() }.exceptionOrNull())
        source.close()
        assertEquals(1, delegate.closes)
        ownership.reserve(listOf(path))!!.close()
    }

    @Test fun openPreservesTheLocalRangeAndHeaders() {
        val ownership = AudioFileOwnership()
        val delegate = ProbeSource()
        val source = OwnedAudioDataSource(selection(ownership), ownership, delegate)
        val spec = DataSpec.Builder().setUri(uri).setPosition(42).setLength(15)
            .setHttpRequestHeaders(mapOf("Fixture" to "one")).build()
        try {
            assertEquals(15L, source.open(spec))
            assertSame(spec, delegate.opened)
            assertEquals(uri, source.uri)
            assertEquals(mapOf("Fixture" to listOf("response")), source.responseHeaders)
            assertNull(ownership.reserve(listOf(path)))
        } finally { source.close() }
        assertNull(source.uri)
    }

    @Test fun openFailurePreservesItsIdentityAndSuppressesCloseFailure() {
        val ownership = AudioFileOwnership()
        val openFailure = IOException("Synthetic open failure")
        val closeFailure = IOException("Synthetic close failure")
        val source = OwnedAudioDataSource(selection(ownership), ownership, ProbeSource(openFailure, closeFailure))
        assertSame(openFailure, runCatching { source.open(DataSpec(uri)) }.exceptionOrNull())
        assertEquals(listOf(closeFailure), openFailure.suppressed.toList())
        ownership.reserve(listOf(path))!!.close()
        source.close()
    }

    @Test fun closingAndReopeningClaimsEachReadSeparately() {
        val ownership = AudioFileOwnership()
        val source = OwnedAudioDataSource(selection(ownership), ownership, ProbeSource())
        repeat(2) {
            source.open(DataSpec(uri))
            assertNull(ownership.reserve(listOf(path)))
            source.close()
            assertFalse(ownership.busy(path))
        }
        ownership.reserve(listOf(path))!!.close()
        assertTrue(runCatching { source.open(DataSpec(uri)) }.exceptionOrNull() is IOException)
    }

    private class ProbeSource(
        private val openFailure: IOException? = null,
        private val closeFailure: IOException? = null,
    ) : DataSource {
        var opened: DataSpec? = null
        var closes = 0
        override fun addTransferListener(listener: TransferListener) {}
        override fun open(spec: DataSpec): Long {
            opened = spec
            openFailure?.let { throw it }
            return spec.length
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
        override fun getUri(): Uri? = opened?.uri
        override fun getResponseHeaders() = mapOf("Fixture" to listOf("response"))
        override fun close() {
            closes++
            opened = null
            closeFailure?.let { throw it }
        }
    }
}
