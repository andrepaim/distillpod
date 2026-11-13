package dev.distillpod.android

import dev.distillpod.android.data.ResumableDownloader
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class DownloadTest {
    private fun files() = File.createTempFile("download-", ".tmp").apply { delete(); mkdirs() }
    @Test fun `validated range resume produces the whole file`() = runBlocking {
        val dir = files()
        try { MockWebServer().use { server ->
            val part = File(dir, "partial").apply { writeText("hello ") }
            server.enqueue(MockResponse().setResponseCode(206).setBody("world").setHeader("ETag", "\"v1\"").setHeader("Content-Range", "bytes 6-10/11"))
            val target = File(dir, "final")
            ResumableDownloader(OkHttpClient()).download(server.url("/audio").toString(), part, target, etag = "\"v1\"")
            assertEquals("hello world", target.readText())
            assertEquals("bytes=6-", server.takeRequest().getHeader("Range"))
            assertFalse(part.exists())
        } } finally { dir.deleteRecursively() }
    }
    @Test fun `changed range validator restarts and unvalidated partials are never appended`() = runBlocking {
        val dir = files()
        try { MockWebServer().use { server ->
            val part = File(dir, "partial").apply { writeText("old ") }
            server.enqueue(MockResponse().setResponseCode(206).setBody("data").setHeader("ETag", "\"new\"").setHeader("Content-Range", "bytes 4-7/8"))
            server.enqueue(MockResponse().setBody("new complete").setHeader("ETag", "\"new\""))
            val target = File(dir, "final")
            ResumableDownloader(OkHttpClient()).download(server.url("/audio").toString(), part, target, etag = "\"old\"")
            assertEquals("new complete", target.readText())
            server.takeRequest(); assertNull(server.takeRequest().getHeader("Range"))
        } } finally { dir.deleteRecursively() }
    }
    @Test fun `truncated and hash-mismatched files never become completed downloads`() = runBlocking {
        val dir = files()
        try { MockWebServer().use { server ->
            val target = File(dir, "final")
            server.enqueue(MockResponse().setBody("a".repeat(10000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            assertTrue(runCatching { ResumableDownloader(OkHttpClient()).download(server.url("/audio").toString(), File(dir, "partial"), target) }.isFailure)
            assertFalse(target.exists())
            server.enqueue(MockResponse().setBody("wrong model"))
            assertTrue(runCatching { ResumableDownloader(OkHttpClient()).download(server.url("/model").toString(), File(dir, "model.partial"), target, expectedSha256 = "0".repeat(64)) }.exceptionOrNull() is IOException)
            assertFalse(target.exists()); assertFalse(File(dir, "model.partial").exists())
        } } finally { dir.deleteRecursively() }
    }
    @Test fun `an oversized fixed-size resource is not reported as a storage-limit problem`() = runBlocking {
        val dir = files()
        try { MockWebServer().use { server ->
            val target = File(dir, "final")
            server.enqueue(MockResponse().setBody("longer than declared"))
            val error = runCatching { ResumableDownloader(OkHttpClient()).download(server.url("/model").toString(), File(dir, "model.partial"), target,
                expectedBytes = 4, maximumBytes = 4) }.exceptionOrNull()!!
            assertFalse(dev.distillpod.android.data.safeFailure(error), dev.distillpod.android.data.safeFailure(error).contains("Storage"))
            assertFalse(target.exists())
            server.enqueue(MockResponse().setBody("too large for the remaining quota"))
            val quota = runCatching { ResumableDownloader(OkHttpClient()).download(server.url("/audio").toString(), File(dir, "audio.partial"), target,
                maximumBytes = 4) }.exceptionOrNull()!!
            assertTrue(dev.distillpod.android.data.safeFailure(quota), dev.distillpod.android.data.safeFailure(quota).contains("Settings → Storage"))
        } } finally { dir.deleteRecursively() }
    }
    @Test fun `cancellation closes the writer before a caller can reuse its partial file`() = runBlocking {
        val dir = files()
        try { MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("a".repeat(100000)).setHeader("ETag", "\"v1\"").throttleBody(1024, 100, java.util.concurrent.TimeUnit.MILLISECONDS))
            val part = File(dir, "partial"); val target = File(dir, "final")
            val job = launch { ResumableDownloader(OkHttpClient()).download(server.url("/audio").toString(), part, target) }
            withTimeout(5000) { while (part.length() == 0L) delay(20) }
            job.cancelAndJoin()
            val length = part.length()
            assertTrue(length > 0); assertFalse(target.exists())
            delay(250)
            assertEquals(length, part.length())
            part.writeText("replacement")
            delay(150)
            assertEquals("replacement", part.readText())
        } } finally { dir.deleteRecursively() }
    }

    @Test fun `bounded ranges download the entire resource including a shorter final chunk`() = runBlocking {
        val dir = files()
        try { MockWebServer().use { server ->
            for ((start, body) in listOf(0 to "abcd", 4 to "efgh", 8 to "ij"))
                server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $start-${start + body.length - 1}/10").setBody(body))
            val target = File(dir, "final")
            val result = ResumableDownloader(OkHttpClient()).download(server.url("/audio").toString(), File(dir, "partial"), target, rangeChunkBytes = 4)
            assertEquals("abcdefghij", target.readText())
            assertEquals(10L, result.bytes)
            assertEquals(listOf("bytes=0-3", "bytes=4-7", "bytes=8-9"), (1..3).map { server.takeRequest().getHeader("Range") })
        } } finally { dir.deleteRecursively() }
    }

    @Test fun `bounded download restarts on a changed representation and never finalizes truncated chunks`() = runBlocking {
        val dir = files()
        try { MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-3/6").setHeader("ETag", "old").setBody("old!"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 4-5/6").setHeader("ETag", "new").setBody("xx"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-3/6").setHeader("ETag", "new").setBody("new!"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 4-5/6").setHeader("ETag", "new").setBody("ok"))
            val target = File(dir, "final")
            ResumableDownloader(OkHttpClient()).download(server.url("/audio").toString(), File(dir, "partial"), target, rangeChunkBytes = 4)
            assertEquals("new!ok", target.readText())
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-3/6").setBody("ab"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-3/6").setBody("ab"))
            val truncated = File(dir, "truncated")
            assertTrue(runCatching { ResumableDownloader(OkHttpClient()).download(server.url("/audio").toString(), File(dir, "new.partial"), truncated, rangeChunkBytes = 4) }.isFailure)
            assertFalse(truncated.exists())
        } } finally { dir.deleteRecursively() }
    }
}
