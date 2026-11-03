package dev.distillpod.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Uses public sample inputs and isolated library/preferences; owner history is never sent. */
@RunWith(AndroidJUnit4::class)
class LiveExploreDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun actualAccountExplainsSavedTextAndResolvesPublicPodcastSuggestions() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowLiveAccount") == "true")
        val graph = compose.activity.applicationContext.appGraph
        graph.ready.await()
        val db = Room.inMemoryDatabaseBuilder(graph.context, AppDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(graph.context.cacheDir, "explore-test-${UUID.randomUUID()}.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        try {
            val dao = db.library()
            dao.putPodcast(PodcastEntity("p", "https://example.test/feed", "Public sample fixture"))
            dao.putEpisode(EpisodeEntity("e", "p", "guid", "Public speech", "https://example.test/audio"))
            dao.putDistillation(DistillationEntity("d", "e", 0, 10000, excerpt = "Ask not what your country can do for you, ask what you can do for your country.", state = "succeeded"))
            val explanation = MomentExplanation(db, graph.ai, {})
            val id = explanation.create("d"); explanation.run(id)
            val report = dao.research(id)!!
            assertEquals(report.error, "succeeded", report.state)
            assertEquals("transcript", report.mode)
            val requests = mutableListOf<Triple<String, String, Boolean>>()
            val settings = AppSettings(graph.context, store)
            val suggestions = Recommendations(db, settings, PodcastRepository(db, graph.http), graph.http, graph.ai,
                { request, topics, history -> requests.add(Triple(request, topics, history)) })
            suggestions.request("Brazilian Portuguese science podcasts", false)
            val request = requests.single()
            suggestions.run(request.first, request.second, request.third)
            val state = suggestions.state.first()
            assertEquals(suggestions.error.first(), "succeeded", state)
            val feeds = dao.suggestions()
            assertTrue(feeds.size in 1..6)
            assertTrue(feeds.all { it.feedUrl.startsWith("http") && it.title.isNotBlank() })
            File(graph.context.cacheDir, "exploration-integration.json").writeText(JSONObject().put("date", "2026-11-02")
                .put("explanationCompleted", true).put("explanationCharacters", report.report.length).put("verifiedSuggestions", feeds.size)
                .put("ownerHistoryShared", false).put("publicTestInterest", "Brazilian Portuguese science podcasts").toString(2))
        } finally {
            db.close(); scope.coroutineContext.job.cancelAndJoin(); file.delete()
        }
    }
}
