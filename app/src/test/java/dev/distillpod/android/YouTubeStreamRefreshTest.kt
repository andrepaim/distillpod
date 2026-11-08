package dev.distillpod.android

import android.net.Uri
import androidx.media3.datasource.*
import dev.distillpod.android.video.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class YouTubeStreamRefreshTest {
    private fun factory(server: MockWebServer): HttpDataSource.Factory = object : HttpDataSource.Factory by DefaultHttpDataSource.Factory() {
        override fun createDataSource(): HttpDataSource {
            val network = DefaultHttpDataSource.Factory().createDataSource()
            return object : HttpDataSource by network {
                override fun open(spec: DataSpec): Long = network.open(spec.withUri(Uri.parse(server.url(spec.uri.path!!).toString())))
            }
        }
    }
    @Test fun `an expired stream is resolved once again and bounded ranges are retained`() = runBlocking(Dispatchers.IO) {
        MockWebServer().use { server ->
            var resolutions = 0
            val youtube = YouTubeSource { _, _, _ ->
                resolutions++
                JSONObject("""{"formats":[{"protocol":"https","url":"https://a.googlevideo.com/stream$resolutions","vcodec":"none","acodec":"aac"}]}""")
            }
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-1/2").setBody("ab"))
            val source = RefreshingYouTubeDataSource(youtube, factory(server))
            try {
                assertEquals(2L, source.open(DataSpec.Builder().setUri("https://www.youtube.com/watch?v=heoWstJTwu4").build()))
                val bytes = ByteArray(2); assertEquals(2, source.read(bytes, 0, 2)); assertEquals("ab", String(bytes))
                assertEquals(2, resolutions)
                assertEquals("/stream1", server.takeRequest().path)
                val refreshed = server.takeRequest(); assertEquals("/stream2", refreshed.path)
                assertEquals("bytes=0-1048575", refreshed.getHeader("Range"))
            } finally { source.close() }
        }
    }
    @Test fun `repeated rejection stops after one refresh`() = runBlocking(Dispatchers.IO) {
        MockWebServer().use { server ->
            var resolutions = 0
            val youtube = YouTubeSource { _, _, _ -> resolutions++; JSONObject("""{"formats":[{"protocol":"https","url":"https://a.googlevideo.com/audio","vcodec":"none","acodec":"aac"}]}""") }
            repeat(2) { server.enqueue(MockResponse().setResponseCode(403)) }
            val source = RefreshingYouTubeDataSource(youtube, factory(server))
            try {
                assertTrue(runCatching { source.open(DataSpec.Builder().setUri("https://youtu.be/heoWstJTwu4").build()) }.isFailure)
                assertEquals(2, resolutions); assertEquals(2, server.requestCount)
            } finally { source.close() }
        }
    }
}
