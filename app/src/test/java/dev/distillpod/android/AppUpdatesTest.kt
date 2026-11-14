package dev.distillpod.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.AvailableUpdate
import dev.distillpod.android.data.ReleaseFeed
import dev.distillpod.android.data.UpdateChecker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdatesTest {
    @get:Rule val folder = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    /** Published path → body; anything else is a 404. */
    private val files = mutableMapOf<String, String>()

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                files[request.path]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun url(path: String) = server.url(path).toString()

    private fun release(version: String, draft: Boolean = false, apk: Boolean = true, info: Boolean = true,
        body: String = "DistillPod $version adds **share cards**.\n\n- More detail.") = JSONObject()
        .put("tag_name", "v$version").put("draft", draft).put("prerelease", true)
        .put("html_url", url("/releases/tag/v$version")).put("body", body)
        .put("assets", JSONArray().apply {
            if (apk) put(JSONObject().put("name", "distillpod-$version.apk").put("browser_download_url", url("/download/v$version/distillpod-$version.apk")))
            if (info) put(JSONObject().put("name", "build-info.json").put("browser_download_url", url("/download/v$version/build-info.json")))
            put(JSONObject().put("name", "SHA256SUMS").put("browser_download_url", url("/download/v$version/SHA256SUMS")))
        })

    private fun buildInfo(version: String, code: Long, app: String = "dev.distillpod.android", tag: String = "v$version") {
        files["/download/v$version/build-info.json"] = JSONObject().put("applicationId", app).put("versionName", version)
            .put("versionCode", code).put("tag", tag).toString()
    }

    private fun releases(vararg items: JSONObject) { files["/releases"] = JSONArray(items.toList()).toString() }

    private fun feed(trusted: Boolean = true) = ReleaseFeed(OkHttpClient(), url("/releases")) { trusted }

    @Test fun `newest published release with an APK and matching build record wins`() = runBlocking {
        releases(release("0.30.0-alpha.1", draft = true), release("0.29.0-alpha.1"), release("0.28.0-alpha.1"))
        buildInfo("0.29.0-alpha.1", 53); buildInfo("0.28.0-alpha.1", 52)
        val update = feed().newest()!!
        assertEquals("0.29.0-alpha.1", update.versionName)
        assertEquals(53L, update.versionCode)
        assertEquals(url("/releases/tag/v0.29.0-alpha.1"), update.pageUrl)
        assertEquals(url("/download/v0.29.0-alpha.1/distillpod-0.29.0-alpha.1.apk"), update.apkUrl)
        assertEquals("DistillPod 0.29.0-alpha.1 adds share cards.", update.summary)
    }

    @Test fun `a release still uploading or with a foreign build record is skipped`() = runBlocking {
        releases(release("0.31.0-alpha.1", info = false), release("0.30.0-alpha.1", apk = false),
            release("0.29.2-alpha.1"), release("0.29.1-alpha.1"), release("0.29.0-alpha.1"))
        buildInfo("0.29.2-alpha.1", 56, app = "dev.distillpod.android.sharecards")
        buildInfo("0.29.1-alpha.1", 55, tag = "v0.29.0-alpha.1")
        buildInfo("0.29.0-alpha.1", 53)
        assertEquals(53L, feed().newest()!!.versionCode)
    }

    @Test fun `links outside the project's releases are never offered`() = runBlocking {
        releases(release("0.29.0-alpha.1")); buildInfo("0.29.0-alpha.1", 53)
        assertNull(feed(trusted = false).newest())
        // The production rule: only https GitHub release pages and downloads of this repository.
        assertTrue(ReleaseFeed.isProjectRelease("https://github.com/andrepaim/distillpod/releases/download/v1.0.0/distillpod-1.0.0.apk"))
        assertTrue(ReleaseFeed.isProjectRelease("https://github.com/andrepaim/distillpod/releases/tag/v1.0.0"))
        for (bad in listOf("http://github.com/andrepaim/distillpod/releases/tag/v1.0.0", "https://github.com/someone/distillpod/releases/tag/v1.0.0",
            "https://github.com.evil.example/andrepaim/distillpod/releases/tag/v1.0.0", "https://github.com/andrepaim/distillpod/archive/v1.zip", "not a url"))
            assertFalse(bad, ReleaseFeed.isProjectRelease(bad))
    }

    @Test fun `no release, a failed request and an oversized answer behave distinctly`() = runBlocking {
        releases()
        assertNull(feed().newest())
        files.remove("/releases")
        try { feed().newest(); fail("A failed request must not look like 'up to date'") } catch (_: IOException) {}
        files["/releases"] = "[" + " ".repeat(ReleaseFeed.API_LIMIT.toInt()) + "]"
        try { feed().newest(); fail("Oversized answer accepted") } catch (_: IOException) {}
    }

    @Test fun `summary is the first paragraph as plain text, shortened at a word`() {
        assertEquals("Turns a moment into a card you can share.",
            ReleaseFeed.summary("Turns a **moment** into a [card](https://x.test) you can `share`.\n\n- **Detail.**"))
        assertEquals("", ReleaseFeed.summary("   "))
        val long = ReleaseFeed.summary("word ".repeat(200))
        assertTrue(long.length <= ReleaseFeed.SUMMARY_MAX + 1 && long.endsWith("…") && !long.endsWith(" …"))
    }

    @Test fun `checker keeps only a newer release and announces each version once`() = runTest {
        val update = AvailableUpdate("0.29.0-alpha.1", 53, "https://github.com/andrepaim/distillpod/releases/tag/v0.29.0-alpha.1",
            "https://github.com/andrepaim/distillpod/releases/download/v0.29.0-alpha.1/distillpod-0.29.0-alpha.1.apk", "Summary.")
        settingsSession(context, folder.newFile("settings.preferences_pb").apply { delete() }) { settings ->
            var newest: AvailableUpdate? = update
            val checker = UpdateChecker({ newest }, settings, installedCode = 52, now = { 1_000L })
            assertTrue(settings.updates.first().enabled)
            assertEquals(update, checker.check())
            assertEquals(update, settings.updates.first().available)
            assertEquals(1_000L, settings.updates.first().checkedAt)
            assertTrue(checker.announce(update)); assertFalse(checker.announce(update))
            // The installed build is already this version or newer, such as a private phone build.
            assertNull(UpdateChecker({ update }, settings, installedCode = 53, now = { 2_000L }).check())
            assertNull(settings.updates.first().available)
            newest = null
            assertNull(checker.check()); assertNull(settings.updates.first().available)
            // A stored update survives as JSON and is shown only while it is still newer than the installed app.
            assertEquals(update, AvailableUpdate.parse(update.json()))
            assertNull(AvailableUpdate.parse("{broken"))
        }
    }
}
