package dev.distillpod.android

import android.content.Intent
import dev.distillpod.android.SocialFixtures.CAPTION
import dev.distillpod.android.SocialFixtures.CARD
import dev.distillpod.android.SocialFixtures.SOURCE
import dev.distillpod.android.SocialFixtures.cardArtifact
import dev.distillpod.android.data.*
import dev.distillpod.android.features.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #324: what a report of an AI result contains, and what it never contains. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiResultReportTest {
    private val report = AiResultReport("Ask this episode", "The guest says rest matters more than effort.", "gpt-5.1")

    @Test fun `reports go to the public contact address next to the privacy policy`() {
        assertEquals("andrepaim@gmail.com", ReportEmail)
        assertEquals("DistillPod AI result report: Ask this episode", report.subject())
    }

    @Test fun `a report holds the feature, model, app version and result and nothing else`() {
        assertEquals("""
            What is wrong with this result?


            Feature: Ask this episode
            Model: gpt-5.1
            App version: 0.38.1-alpha.1

            Reported result:
            The guest says rest matters more than effort.
        """.trimIndent(), report.body("0.38.1-alpha.1", includeResult = true))
    }

    @Test fun `removing the result text leaves only the feature, model and version`() {
        val body = report.body("0.38.1-alpha.1", includeResult = false)
        assertFalse(body.contains("rest matters"))
        assertFalse(body.contains("Reported result"))
        assertTrue(body.endsWith("Feature: Ask this episode\nModel: gpt-5.1\nApp version: 0.38.1-alpha.1"))
    }

    @Test fun `a result that records no model has no model line`() {
        val body = report.copy(model = null).body("1.0", includeResult = true)
        assertFalse(body.contains("Model:"))
        assertFalse(report.copy(model = " ").body("1.0", includeResult = true).contains("Model:"))
    }

    @Test fun `the email is addressed through the user's own email app with only the subject and body`() {
        val intent = report.emailIntent("1.0", includeResult = true)
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto", intent.data!!.scheme)
        assertTrue(intent.data!!.schemeSpecificPart.startsWith("andrepaim@gmail.com?"))
        assertEquals(listOf("andrepaim@gmail.com"), intent.getStringArrayExtra(Intent.EXTRA_EMAIL)!!.toList())
        assertEquals(report.subject(), intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(report.body("1.0", true), intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(setOf(Intent.EXTRA_EMAIL, Intent.EXTRA_SUBJECT, Intent.EXTRA_TEXT), intent.extras!!.keySet())
        // Some email apps read only the mailto address, so it repeats the same subject and body.
        assertEquals("mailto:andrepaim@gmail.com?subject=" + android.net.Uri.encode(report.subject()) + "&body=" + android.net.Uri.encode(report.body("1.0", true)),
            intent.dataString)
        assertFalse(report.emailIntent("1.0", includeResult = false).getStringExtra(Intent.EXTRA_TEXT)!!.contains("rest matters"))
    }

    @Test fun `a saved moment reports its insight and quote with its model but not the transcript passage`() {
        val moment = DistillationEntity("m", "ep", 0, 20_000, excerpt = "Full transcript passage around the moment.",
            quote = "Rest is part of the work.", insight = "Recovery makes effort last.", model = "gpt-5.1-mini")
        val r = momentReport(moment)
        assertEquals("Saved moment", r.feature)
        assertEquals("gpt-5.1-mini", r.model)
        assertEquals("Recovery makes effort last.\n\n“Rest is part of the work.”", r.result)
        assertFalse(r.body("1", true).contains("Full transcript passage"))
    }

    @Test fun `episode insights report the summary, chapters and suggested ad ranges`() {
        val row = AnalysisEntity("ep", "r1", "succeeded", summary = "An episode about rest.",
            chaptersJson = JSONArray().put(JSONObject().put("startMs", 0).put("title", "Opening")).put(JSONObject().put("startMs", 65_000).put("title", "Sleep")).toString(),
            adsJson = JSONArray().put(JSONObject().put("startMs", 120_000).put("endMs", 150_000).put("reason", "Sponsor read")).toString(), model = "gpt-5.1")
        val r = analysisReport(row)
        assertEquals("Episode insights", r.feature)
        assertEquals("gpt-5.1", r.model)
        assertEquals("Summary:\nAn episode about rest.\n\nChapters:\n0:00 · Opening\n1:05 · Sleep\n\nSuggested ads:\n2:00 – 2:30 · Sponsor read", r.result)
    }

    @Test fun `an episode chat answer reports the reply only, not the question`() {
        val reply = MessageEntity("a", "ep", "assistant", "Rest comes first, the guest says.", model = "gpt-5.1")
        val r = chatReport(reply)
        assertEquals(AiResultReport("Ask this episode", "Rest comes first, the guest says.", "gpt-5.1"), r)
    }

    @Test fun `an explanation reports its text and recorded model but not the saved source`() {
        val row = ResearchEntity("x", "m", "succeeded", report = "The idea is that recovery is work.",
            sourcesJson = JSONArray().put(JSONObject().put("kind", "transcript").put("model", "gpt-5.1")
                .put("snapshot", JSONObject().put("excerpt", "Secret source passage"))).toString())
        val r = explanationReport(row)
        assertEquals(AiResultReport("Explain this idea", "The idea is that recovery is work.", "gpt-5.1"), r)
        assertNull(explanationReport(row.copy(sourcesJson = "not json")).model)
    }

    @Test fun `a post reports its card words and caption, not the source excerpt`() {
        val result = SocialResult(SOURCE, SocialResult.CARD, "ready", artifact = cardArtifact(CARD, CAPTION))
        val r = postReport(result)!!
        assertEquals("Share post", r.feature)
        assertNull(r.model)
        assertEquals(cardDescription(CARD, CARD.style) + "\n\n" + CAPTION, r.result)
        assertFalse(r.result.contains(SOURCE.excerpt))
        assertNull(postReport(result.copy(artifact = null)))
    }

    @Test fun `a suggestion reports the suggested show and its reason`() {
        val r = suggestionReport(SuggestionEntity("https://example.test/feed", "Dog Talk", "Heather Parsons", null, "Title mentions “dog”."))
        assertEquals(AiResultReport("Find new voices", "Dog Talk · Heather Parsons\nTitle mentions “dog”."), r)
    }

    @Test fun `a spoken answer reports its transcript text`() {
        assertEquals(AiResultReport("Voice answer", "Rest first."), voiceReport("  Rest first. "))
    }
}
