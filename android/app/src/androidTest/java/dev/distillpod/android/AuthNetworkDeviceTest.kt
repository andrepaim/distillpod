package dev.distillpod.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nimbusds.jose.jwk.JWKSet
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class AuthNetworkDeviceTest {
    @Test fun largePortugueseFeedLoadsRecentEpisodesOnAndroid() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, dev.distillpod.android.data.AppDatabase::class.java).build()
        try {
            val http = OkHttpClient.Builder().addInterceptor { it.proceed(it.request().newBuilder().header("User-Agent", "DistillPod/0.2 (Android)").build()) }.build()
            val repository = dev.distillpod.android.data.PodcastRepository(db, http)
            val id = repository.subscribe("https://feeds.simplecast.com/hwQVm5gy")
            assertEquals("Naruhodo", db.library().podcast(id)!!.title)
            assertEquals(200, db.library().episodesForPodcast(id).size)
        } finally { db.close() }
    }
    /** Explicitly invoked integration check, not part of offline or emulator smoke suites. */
    @Test fun publicIdentityKeysAreReachableFromTheApp() {
        assertTrue(InetAddress.getAllByName("auth.openai.com").isNotEmpty())
        val request = Request.Builder().url("https://auth.openai.com/.well-known/jwks.json").build()
        OkHttpClient().newCall(request).execute().use { response ->
            assertTrue("Public identity keys request must succeed", response.isSuccessful)
            assertTrue(JWKSet.parse(response.body!!.string()).keys.isNotEmpty())
        }
        println("DISTILLPOD_PUBLIC_AUTH_NETWORK_OK")
    }
}
