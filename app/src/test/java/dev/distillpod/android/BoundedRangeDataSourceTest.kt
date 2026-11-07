package dev.distillpod.android

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import dev.distillpod.android.video.BoundedRangeDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class BoundedRangeDataSourceTest {
    @Test fun `default requests satisfy a CDN that rejects ranges larger than one MiB`() = runBlocking(Dispatchers.IO) {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val end = request.getHeader("Range")?.substringAfter("bytes=0-")?.toLongOrNull()
                    return if (end == null || end >= 1024 * 1024) MockResponse().setResponseCode(403)
                    else MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-1/2").setBody("ab")
                }
            }
            val source = BoundedRangeDataSource(DefaultHttpDataSource.Factory().createDataSource())
            try {
                assertEquals(2L, source.open(DataSpec.Builder().setUri(server.url("/audio").toString()).build()))
                assertEquals(2, source.read(ByteArray(2), 0, 2))
                assertEquals(-1, source.read(ByteArray(2), 0, 2))
            } finally { source.close() }
        }
    }

    @Test fun `an unknown length stream spans bounded ranges and exposes the complete resource length`() = runBlocking(Dispatchers.IO) {
        MockWebServer().use { server ->
            for ((start, body) in listOf(0 to "abcd", 4 to "efgh", 8 to "ij")) {
                server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $start-${start + body.length - 1}/10").setBody(body))
            }
            val source = BoundedRangeDataSource(DefaultHttpDataSource.Factory().createDataSource(), 4)
            try {
                assertEquals(10L, source.open(DataSpec.Builder().setUri(server.url("/audio").toString()).build()))
                val output = java.io.ByteArrayOutputStream()
                val bytes = ByteArray(3)
                while (true) {
                    val n = source.read(bytes, 0, bytes.size)
                    if (n < 0) break
                    output.write(bytes, 0, n)
                }
                assertEquals("abcdefghij", output.toString("UTF-8"))
                assertEquals(listOf("bytes=0-3", "bytes=4-7", "bytes=8-9"), (1..3).map { server.takeRequest().getHeader("Range") })
            } finally { source.close() }
        }
    }

    @Test fun `seeks respect requested length and close allows reuse at a new offset`() = runBlocking(Dispatchers.IO) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/10").setBody("def"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 1-2/10").setBody("bc"))
            val source = BoundedRangeDataSource(DefaultHttpDataSource.Factory().createDataSource(), 4)
            val bytes = ByteArray(8)
            try {
                assertEquals(3L, source.open(DataSpec.Builder().setUri(server.url("/audio").toString()).setPosition(3).setLength(3).build()))
                assertEquals(3, source.read(bytes, 0, bytes.size))
                assertEquals(-1, source.read(bytes, 0, bytes.size))
                source.close()
                assertEquals(2L, source.open(DataSpec.Builder().setUri(server.url("/audio").toString()).setPosition(1).setLength(2).build()))
                assertEquals(2, source.read(bytes, 0, bytes.size))
                assertEquals("bc", String(bytes, 0, 2))
                assertEquals("bytes=3-5", server.takeRequest().getHeader("Range"))
                assertEquals("bytes=1-2", server.takeRequest().getHeader("Range"))
            } finally { source.close() }
        }
    }

    @Test fun `a mismatched or changing range fails before bytes can be spliced`() = runBlocking(Dispatchers.IO) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-3/10").setBody("abcd"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 4-7/11").setBody("efgh"))
            val source = BoundedRangeDataSource(DefaultHttpDataSource.Factory().createDataSource(), 4)
            try {
                source.open(DataSpec.Builder().setUri(server.url("/audio").toString()).build())
                val bytes = ByteArray(4)
                assertEquals(4, source.read(bytes, 0, 4))
                assertTrue(runCatching { source.read(bytes, 0, 4) }.exceptionOrNull() is java.io.IOException)
            } finally { source.close() }
        }
    }

    @Test fun `a resource shorter than the first chunk ends cleanly and an ignored range still seeks`() = runBlocking(Dispatchers.IO) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-1/2").setBody("ab"))
            server.enqueue(MockResponse().setBody("abcdef"))
            val source = BoundedRangeDataSource(DefaultHttpDataSource.Factory().createDataSource(), 4)
            val bytes = ByteArray(8)
            try {
                assertEquals(2L, source.open(DataSpec.Builder().setUri(server.url("/audio").toString()).build()))
                assertEquals(2, source.read(bytes, 0, bytes.size))
                assertEquals(-1, source.read(bytes, 0, bytes.size))
                source.close()
                assertEquals(2L, source.open(DataSpec.Builder().setUri(server.url("/audio").toString()).setPosition(3).setLength(2).build()))
                assertEquals(2, source.read(bytes, 0, bytes.size))
                assertEquals("de", String(bytes, 0, 2))
                assertEquals(-1, source.read(bytes, 0, bytes.size))
            } finally { source.close() }
        }
    }
}
