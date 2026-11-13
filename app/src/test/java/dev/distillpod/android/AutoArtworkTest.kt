package dev.distillpod.android

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.auto.AutoArtwork
import dev.distillpod.android.auto.AutoArtworkProvider
import java.io.File
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
    @Test fun `provider is private and artwork is granted per URI only to allowed controllers`()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Context>()
        val info=app.packageManager.getProviderInfo(ComponentName(app,AutoArtworkProvider::class.java),0)
        assertFalse("Other apps must not probe which shows are followed",info.exported)
        assertTrue(info.grantUriPermissions)
        val grants=java.util.Collections.synchronizedList(mutableListOf<Pair<String,Uri>>())
        val context=object:ContextWrapper(app) {
            override fun grantUriPermission(toPackage:String,uri:Uri,modeFlags:Int) {
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,modeFlags)
                grants+=toPackage to uri
            }
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            fun key(url:String)=java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString(""){"%02x".format(it)}+".jpg"
            val directory=File(app.cacheDir,"auto-art").apply {mkdirs()}
            // Robolectric has no launcher icon drawable for the generated placeholder.
            File(directory,"placeholder.jpg").takeUnless {it.isFile}?.writeBytes(byteArrayOf(0))
            val earlier="https://example.test/earlier-${System.nanoTime()}.png"
            File(directory,key(earlier)).writeBytes(byteArrayOf(1))
            val art=AutoArtwork(context,OkHttpClient(),scope)
            val placeholder=art.uri(null)
            val earlierUri=art.uri(earlier)
            assertTrue("Nothing is granted before a controller is allowed",grants.isEmpty())
            art.allow(app.packageName).join()
            assertTrue("The app needs no grant for its own provider",grants.isEmpty())
            art.allow("android.auto.test").join()
            assertTrue(("android.auto.test" to placeholder) in grants)
            assertTrue("URIs handed out before the controller connected stay readable",("android.auto.test" to earlierUri) in grants)
            grants.clear()
            val later="https://example.test/later-${System.nanoTime()}.png"
            File(directory,key(later)).writeBytes(byteArrayOf(2))
            val laterUri=art.uri(later)
            assertEquals(listOf("android.auto.test" to laterUri),grants.toList())
            art.uri(later);art.uri(null)
            assertEquals("Each URI is granted once per controller",1,grants.size)
        } finally {scope.cancel()}
    }
}
