package dev.distillpod.android

import android.content.Context
import android.graphics.BitmapFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.SocialFixtures.CAPTION
import dev.distillpod.android.SocialFixtures.CARD
import dev.distillpod.android.SocialFixtures.EXCERPT
import dev.distillpod.android.SocialFixtures.SOURCE
import dev.distillpod.android.SocialFixtures.answer
import dev.distillpod.android.data.*
import dev.distillpod.android.sharing.PostAssets
import dev.distillpod.android.work.SocialPostGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SocialCardTest {
    private val failure = "The post could not be checked against its source. Try again."
    private fun result(instruction: String = "", artifact: SocialArtifact? = null) =
        SocialResult(SOURCE, SocialResult.CARD, artifact = artifact, instruction = instruction)

    @Test fun `composer writes the card and caption in one request from the saved distill`() = runBlocking {
        val ai = ScriptedAi { _, _ -> "```json\n${answer()}\n```" }
        val composed = SocialComposer(ai).card(result())
        assertEquals(1, ai.inputs.size)
        assertEquals(SocialComposer.PROMPT, ai.prompts.single())
        val input = ai.inputs.single()
        assertEquals(EXCERPT, input.getString("excerpt"))
        assertEquals("Small steps make practice easier.", input.getString("savedQuote"))
        assertEquals("Small steps beat big plans.", input.getString("savedInsight"))
        assertEquals("Show", input.getString("show")); assertEquals("Episode", input.getString("episode"))
        assertFalse(input.has("previousCard") || input.has("change"))
        assertEquals(CARD, composed.card); assertEquals("Habits", composed.card.topic)
        assertEquals(CardContent.STYLE_QUOTE, composed.card.style); assertEquals(CardContent.LOOK_EMBER, composed.card.look)
        assertEquals("", composed.card.cover); assertEquals(0, composed.card.tint)
        assertEquals(CAPTION, composed.caption)
        assertEquals(listOf("habits", "practice", "learning"), composed.hashtags)
    }

    @Test fun `each invalid answer gets exactly one repair with its reason and then fails`() = runBlocking {
        val words = "word ".repeat(30).trim()
        val cases = listOf(
            answer(CARD.copy(quote = "Small steps make practise easier.")) to "quote must be copied exactly",
            answer(CARD.copy(highlight = "tiny steps")) to "highlight must be an exact part",
            answer(CARD.copy(headline = "H".repeat(61))) to "headline has 61 characters",
            answer(CARD.copy(pull = "Invented pull quote.")) to "pull must be copied exactly",
            answer(CARD.copy(takeaways = CARD.takeaways.take(2))) to "exactly three",
            answer(CARD.copy(topic = "T".repeat(25))) to "topic has 25 characters",
            answer(CARD.copy(topic = " ")) to "topic must name the subject",
            answer(CARD.copy(topic = "#Habits")) to "only letters, digits and spaces",
            answer().apply { remove("topic") } to "topic",
            answer(caption = "Too short.\n\nStill short.") to "caption has 4 words",
            answer(caption = "$CAPTION ${"more ".repeat(80)}") to "allowed 40–120",
            answer(caption = CAPTION.replace("\n\n", " ")) to "caption has 1 paragraphs",
            answer(caption = "${"A very long opening line ".repeat(6)}\n\n$words") to "first line has",
            answer(caption = "$CAPTION #habits") to "must not contain hashtags",
            answer(caption = "$CAPTION See www.example.org now.") to "must not contain links",
            answer(caption = CAPTION.replace("Small steps beat", "**Small** steps beat")) to "without Markdown",
            answer(caption = "$CAPTION\n- one more point") to "without Markdown",
            answer(caption = CAPTION.replace("Small steps beat", "The excerpt says small steps beat")) to "caption must not mention the excerpt",
            answer(CARD.copy(headline = "What the Excerpt shows")) to "headline must not mention the excerpt",
            answer(CARD.copy(takeaways = CARD.takeaways.mapIndexed { i, t -> if (i == 2) t.copy(detail = "As the excerpts say.") else t })) to
                "takeaways[2] must not mention the excerpt",
            answer(hashtags = listOf("habits", "practice")) to "hashtags has 2 items",
            answer(hashtags = listOf("habits", "good practice", "learning")) to "hashtag \"good practice\"",
            answer(hashtags = listOf("habits", "Habits", "learning")) to "must not repeat",
        )
        for ((bad, reason) in cases) {
            val ai = ScriptedAi { _, _ -> bad.toString() }
            try { SocialComposer(ai).card(result()); fail("accepted: $reason") } catch (e: SocialGenerationFailure) { assertEquals(failure, e.message) }
            assertEquals(reason, 2, ai.inputs.size)
            val repair = ai.inputs[1]
            assertEquals(bad.toString(), repair.getString("invalidPreviousAnswer"))
            assertTrue("${repair.getString("repairReason")} lacks $reason", repair.getString("repairReason").contains(reason))
            assertEquals(EXCERPT, repair.getString("excerpt"))
            assertTrue(ai.prompts[1].startsWith(SocialComposer.PROMPT) && ai.prompts[1].contains("repairReason"))
        }
        val malformed = ScriptedAi { _, _ -> "not json" }
        try { SocialComposer(malformed).card(result()); fail() } catch (e: SocialGenerationFailure) { assertEquals(failure, e.message) }
        assertEquals(2, malformed.inputs.size); assertTrue(malformed.inputs[1].getString("repairReason").isNotBlank())
    }

    @Test fun `a repaired answer is accepted`() = runBlocking {
        val ai = ScriptedAi { call, _ -> if (call == 1) answer(CARD.copy(quote = "Invented")).toString() else answer().toString() }
        assertEquals(CARD, SocialComposer(ai).card(result()).card)
        assertEquals(2, ai.inputs.size)
    }

    @Test fun `request failures propagate without a repair request`() = runBlocking {
        val failures = listOf<() -> Exception>({ QuotaExceeded() }, { ReauthorizationRequired() }, { AiRateLimited() }, { AccountEntitlementDenied() },
            { AiRequestRejected(400) }, { AiServiceUnavailable(503) }, { java.io.IOException("offline") }, { CancellationException("stopped") })
        for (make in failures) {
            val expected = make()
            for (failingCall in 1..2) {
                val ai = ScriptedAi { call, _ -> if (call == failingCall) throw expected else answer(CARD.copy(quote = "Invented")).toString() }
                try { SocialComposer(ai).card(result()); fail() } catch (e: Exception) { assertSame(expected, e) }
                assertEquals(failingCall, ai.inputs.size)
            }
        }
    }

    @Test fun `change sends the previous card and caption and may shorten the caption`() = runBlocking {
        val previous = CARD.copy(style = CardContent.STYLE_IDEA, look = CardContent.LOOK_SIGNAL)
        val short = "Small steps beat big plans.\n\nRepeat a little every day and the habit grows."
        val ai = ScriptedAi { _, _ -> answer(caption = short).toString() }
        val composed = SocialComposer(ai).card(result("Make it shorter", SocialFixtures.cardArtifact(previous)))
        val input = ai.inputs.single()
        assertEquals("Make it shorter", input.getString("change"))
        assertEquals(CAPTION, input.getString("previousCaption"))
        assertEquals(listOf("habits", "practice", "learning"), input.getJSONArray("previousHashtags").let { a -> (0 until a.length()).map(a::getString) })
        val card = input.getJSONObject("previousCard")
        assertEquals(CARD.quote, card.getString("quote")); assertEquals(CARD.headline, card.getString("headline")); assertEquals("Habits", card.getString("topic"))
        assertFalse(card.has("style") || card.has("look") || card.has("cover") || card.has("tint"))
        assertEquals(short, composed.caption)
        // Without a change request the same short caption is below the minimum.
        val strict = ScriptedAi { _, _ -> answer(caption = short).toString() }
        try { SocialComposer(strict).card(result()); fail() } catch (_: SocialGenerationFailure) {}
    }

    @Test fun `stored caption adds the source line, optional public link and hashtags`() {
        val composed = ComposedCard(CARD, "Hook line.\n\nBody text.", listOf("habits", "practice", "learning"))
        assertEquals("Hook line.\n\nBody text.\n\n🎧 Show · Episode\n\n#habits #practice #learning", composed.fullCaption(SOURCE))
        assertEquals("Hook line.\n\nBody text.\n\n🎧 Show · Episode\nhttps://host.test/episode\n\n#habits #practice #learning",
            composed.fullCaption(SOURCE.copy(sourceUrl = "https://host.test/episode")))
        assertEquals("Hook line.\n\nBody text.\n\n🎧 Episode\n\n#habits #practice #learning", composed.fullCaption(SOURCE.copy(show = " ")))
        assertEquals("Hook line.\n\nBody text.\n\n#habits #practice #learning", composed.fullCaption(SOURCE.copy(show = "", episode = "")))
        assertEquals("Hook line.\n\nBody text." to listOf("habits", "practice", "learning"),
            SocialComposer.splitCaption(composed.fullCaption(SOURCE.copy(sourceUrl = "https://host.test/episode"))))
    }

    // Generator: a fake composer and cover loader, the real repository and asset store.

    private fun db() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    private fun assets() = PostAssets(java.nio.file.Files.createTempDirectory("cover-test").toFile())
    private suspend fun seed(db: AppDatabase, artwork: Boolean = true) {
        db.library().putPodcast(PodcastEntity("p", "https://host.test/feed", "Show", imageUrl = if (artwork) "https://host.test/show.png" else null))
        db.library().putEpisode(EpisodeEntity("e", "p", "g", "Episode", "https://host.test/audio", imageUrl = if (artwork) "https://host.test/episode.png?w=600" else null))
        db.library().putDistillation(DistillationEntity("m", "e", 0, 1000, EXCERPT, "Small steps make practice easier.", "Small steps beat big plans.", state = "succeeded"))
    }
    private fun composer(card: CardContent = CARD, caption: String = CAPTION, seen: MutableList<SocialResult> = mutableListOf()) =
        CardComposer { seen += it; ComposedCard(card, caption, listOf("habits", "practice", "learning")) }

    @Test fun `generator stores the cover, its tint and opens on the cover look`() = runBlocking {
        val db = db()
        try {
            seed(db); val assets = assets(); val repo = SocialResultRepository(db) { _, _ -> }
            val id = repo.create("m"); val attempt = repo.get(id)!!.attempt
            val loaded = mutableListOf<String>()
            SocialPostGenerator(repo, composer(), assets) { loaded += it; SocialFixtures.bitmap(600, 400) }.run(id, attempt)
            assertEquals(listOf("https://host.test/episode.png?w=600"), loaded)
            val result = repo.get(id)!!
            assertEquals("ready", result.stage); assertNull(result.previous)
            val artifact = result.artifact!!; val card = artifact.cardContent()!!
            assertEquals(CardContent.LOOK_COVER, card.look); assertEquals(CardContent.STYLE_QUOTE, card.style)
            assertNotEquals(0, card.tint); assertEquals(0xFF, card.tint ushr 24)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val png = assets.read(card.cover); BitmapFactory.decodeByteArray(png, 0, png.size, options)
            assertEquals(CardContent.COVER_PX, options.outWidth); assertEquals(CardContent.COVER_PX, options.outHeight)
            assertEquals("$CAPTION\n\n🎧 Show · Episode\n\n#habits #practice #learning", artifact.text)
            assertEquals(CARD.quote, artifact.quote); assertEquals(CARD.headline, artifact.title); assertEquals(CARD.topic, card.topic)
            assertEquals(artifact.text, result.shareText())
        } finally { db.close() }
    }

    @Test fun `generator without artwork opens on ember and long quotes on the big idea`() = runBlocking {
        val db = db()
        try {
            seed(db, artwork = false); val repo = SocialResultRepository(db) { _, _ -> }
            val id = repo.create("m")
            assertEquals("", repo.get(id)!!.source.artworkUrl)
            val long = CARD.copy(quote = EXCERPT, highlight = "")
            SocialPostGenerator(repo, composer(long), assets()) { fail("no artwork to load"); null }.run(id, repo.get(id)!!.attempt)
            val card = repo.get(id)!!.artifact!!.cardContent()!!
            assertEquals(CardContent.LOOK_EMBER, card.look); assertEquals(CardContent.STYLE_IDEA, card.style)
            assertEquals("", card.cover); assertEquals(0, card.tint)
        } finally { db.close() }
        val other = db()
        try { // Artwork that cannot be loaded is no cover, not a failure.
            seed(other); val repo = SocialResultRepository(other) { _, _ -> }
            val id = repo.create("m")
            SocialPostGenerator(repo, composer(), assets()) { null }.run(id, repo.get(id)!!.attempt)
            assertEquals(CardContent.LOOK_EMBER, repo.get(id)!!.artifact!!.cardContent()!!.look)
        } finally { other.close() }
    }

    @Test fun `change keeps style, look and the stored cover without loading artwork again`() = runBlocking {
        val db = db()
        try {
            seed(db); val assets = assets(); val repo = SocialResultRepository(db) { _, _ -> }
            val id = repo.create("m")
            SocialPostGenerator(repo, composer(), assets) { SocialFixtures.bitmap(400, 400) }.run(id, repo.get(id)!!.attempt)
            val first = repo.get(id)!!.artifact!!; val firstCard = first.cardContent()!!
            repo.restyle(id, CardContent.STYLE_TAKEAWAYS, CardContent.LOOK_SIGNAL)
            repo.change(id, "Make it shorter")
            val seen = mutableListOf<SocialResult>()
            val revised = CARD.copy(headline = "Repeat small steps", topic = "Daily practice")
            SocialPostGenerator(repo, composer(revised, seen = seen), assets) { fail("cover must be reused"); null }.run(id, repo.get(id)!!.attempt)
            assertEquals("Make it shorter", seen.single().instruction)
            val result = repo.get(id)!!; val card = result.artifact!!.cardContent()!!
            assertEquals("Repeat small steps", card.headline); assertEquals("Daily practice", card.topic)
            assertEquals(CardContent.STYLE_TAKEAWAYS, card.style); assertEquals(CardContent.LOOK_SIGNAL, card.look)
            assertEquals(firstCard.cover, card.cover); assertEquals(firstCard.tint, card.tint)
            assertEquals(CardContent.LOOK_SIGNAL, result.previous!!.cardContent()!!.look)
            repo.undo(id)
            assertEquals(CARD.headline, repo.get(id)!!.artifact!!.cardContent()!!.headline)
        } finally { db.close() }
    }

    @Test fun `a canceled generation never writes its card`() = runBlocking {
        val db = db()
        try {
            seed(db, artwork = false); val repo = SocialResultRepository(db) { _, _ -> }
            val id = repo.create("m")
            val cancelling = CardComposer { repo.cancel(id); ComposedCard(CARD, CAPTION, listOf("habits", "practice", "learning")) }
            SocialPostGenerator(repo, cancelling, assets()) { null }.run(id, repo.get(id)!!.attempt)
            val result = repo.get(id)!!
            assertEquals("canceled", result.stage); assertNull(result.artifact)
        } finally { db.close() }
    }
}
