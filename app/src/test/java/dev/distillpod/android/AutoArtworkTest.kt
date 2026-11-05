package dev.distillpod.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.auto.AutoArtwork
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class AutoArtworkTest {
    @Test fun `cached thumbnail remains usable offline and provider rejects traversal`()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val server=MockWebServer();server.start()
        try {
            val bitmap=android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888)
            val out=java.io.ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out)
            server.enqueue(MockResponse().setBody(okio.Buffer().write(out.toByteArray())))
            val art=AutoArtwork(context,OkHttpClient(),scope)
            val url=server.url("/cover-${System.nanoTime()}.png").toString()
            art.warm(url)
            val uri=art.uri(url)
            assertEquals("content",uri.scheme)
            assertNotNull(art.file(uri.lastPathSegment!!))
            server.shutdown()
            assertEquals(uri,AutoArtwork(context,OkHttpClient(),scope).uri(url))
            assertNull(art.file("../database.db"))
            assertNull(art.file("arbitrary"))
        } finally {scope.cancel();server.shutdown()}
    }
}
