package dev.distillpod.android

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import dev.distillpod.android.SocialFixtures.CARD
import dev.distillpod.android.SocialFixtures.SOURCE
import dev.distillpod.android.SocialFixtures.cardArtifact
import dev.distillpod.android.SocialFixtures.png
import dev.distillpod.android.data.*
import dev.distillpod.android.features.PostActions
import dev.distillpod.android.features.PostResult
import dev.distillpod.android.features.cardDescription
import dev.distillpod.android.features.postKind
import dev.distillpod.android.sharing.PostAssets
import dev.distillpod.android.ui.theme.DistillTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The post screen composed from plain values and callbacks; the page only wires these to the app graph. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h823dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PostResultScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val calls = mutableListOf<String>()
    private val assets = PostAssets(java.nio.file.Files.createTempDirectory("post-screen").toFile())
    private val actions = PostActions(share = { calls += "share" }, shareStory = { calls += "story" }, saveImage = { calls += "save" },
        copy = { calls += "copy" }, change = { calls += "change:$it" }, retry = { calls += "retry" }, editText = { calls += "edit:$it" },
        restyle = { style, look -> calls += "restyle:$style:$look" }, undo = { calls += "undo" }, cancel = { calls += "cancel" },
        delete = { calls += "delete" })
    private fun card(card: CardContent = CARD, stage: String = "ready", previous: SocialArtifact? = null) =
        SocialResult(SOURCE, SocialResult.CARD, stage, artifact = cardArtifact(card), previous = previous)

    private fun show(result: SocialResult, fontScale: Float = 1f) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            // The app's Scaffold supplies the page colour and content colour.
            DistillTheme { Surface(color = MaterialTheme.colorScheme.background) { PostResult(result, assets, actions) } }
        }
    }
    // waitUntil alone does not move the paused test clock far enough for the restyle effect to start.
    private fun awaitCall(call: String) {
        repeat(300) { if (call in calls) return; compose.mainClock.advanceTimeBy(16); compose.waitForIdle() }
        fail("$call was not called: $calls")
    }
    private fun awaitCard(style: String, card: CardContent = CARD) = compose.waitUntil(20_000) {
        compose.onAllNodesWithContentDescription(cardDescription(card, style)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun `a card shows the card, its styles and looks, the caption and Share with Change`() {
        val result = card()
        show(result)
        awaitCard(CardContent.STYLE_QUOTE)
        compose.onNodeWithContentDescription(cardDescription(CARD, CardContent.STYLE_QUOTE)).assertIsDisplayed()
        for (name in listOf("Quote", "Big idea", "Takeaways")) compose.onNodeWithText(name).assertExists()
        compose.onNodeWithText("Quote").assertIsSelected()
        compose.onNodeWithContentDescription("Ember look").assertIsSelected()
        compose.onNodeWithContentDescription("Signal look").assertIsNotSelected()
        // Without stored artwork there is no Cover look to offer.
        compose.onNodeWithContentDescription("Cover look").assertDoesNotExist()
        compose.onNodeWithText("Caption").assertExists()
        compose.onNodeWithText(result.artifact!!.text).assertExists()
        compose.onNodeWithText("Share").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Change").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Made with an earlier version of posts.").assertDoesNotExist()
        assertEquals("Post", postKind(result))
        compose.onNodeWithText("Share").performClick()
        // The Change and Edit caption dialogs hold a text field, which never idles in a Robolectric dialog window.
        assertEquals(listOf("share"), calls)
    }

    @Test fun `swatches and style names restyle locally and persist`() {
        show(card())
        awaitCard(CardContent.STYLE_QUOTE)
        compose.onNodeWithContentDescription("Signal look").performClick()
        awaitCall("restyle:quote:signal")
        compose.onNodeWithContentDescription("Signal look").assertIsSelected()
        compose.onNodeWithText("Takeaways").performClick()
        awaitCall("restyle:takeaways:signal")
        awaitCard(CardContent.STYLE_TAKEAWAYS)
        compose.onNodeWithText("Takeaways").assertIsSelected()
        compose.onNodeWithContentDescription(cardDescription(CARD, CardContent.STYLE_TAKEAWAYS)).assertIsDisplayed()
    }

    @Test fun `a stored cover offers the Cover look and a swipe changes the style`() {
        val cover = CARD.copy(cover = assets.put(png(CardContent.COVER_PX, CardContent.COVER_PX)), tint = 0xFF2266AA.toInt(),
            look = CardContent.LOOK_COVER, style = CardContent.STYLE_IDEA)
        show(card(cover))
        awaitCard(CardContent.STYLE_IDEA, cover)
        compose.onNodeWithContentDescription("Cover look").assertIsSelected()
        compose.onNodeWithText("Big idea").assertIsSelected()
        compose.onNodeWithContentDescription(cardDescription(cover, CardContent.STYLE_IDEA)).performTouchInput { swipeLeft() }
        awaitCall("restyle:takeaways:cover")
    }

    @Test fun `overflow lists card actions in order`() {
        show(card(previous = cardArtifact(CARD.copy(headline = "An earlier headline"))))
        compose.onNodeWithContentDescription("More post actions").performClick()
        val labels = listOf("Copy caption", "Save image", "Share as Story (9:16)", "Edit caption", "Undo last change", "Delete post")
        val tops = labels.map { compose.onNode(hasText(it) and hasAnyAncestor(isPopup())).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
        compose.onNodeWithText("Share as Story (9:16)").performClick()
        assertEquals(listOf("story"), calls)
    }

    @Test fun `generating keeps the last version visible with styles locked`() {
        show(card(stage = "preparing"))
        compose.onNodeWithText("Writing your post…").assertIsDisplayed()
        compose.onNodeWithText("You can leave this screen. Your post will stay here.").assertIsDisplayed()
        compose.onNodeWithText("Your last completed version").assertExists()
        compose.onNodeWithText("Takeaways").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Signal look").assertIsNotEnabled()
        compose.onNodeWithText("Change").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test fun `a first generation shows progress and only Delete in the menu`() {
        show(SocialResult(SOURCE, SocialResult.CARD, "queued"))
        compose.onNodeWithText("Waiting to connect…").assertIsDisplayed()
        compose.onNodeWithText("Share").assertDoesNotExist()
        compose.onNodeWithContentDescription("More post actions").performClick()
        compose.onNodeWithText("Delete post").assertIsDisplayed()
        compose.onNodeWithText("Copy caption").assertDoesNotExist()
    }

    @Test fun `earlier results show as before without Change and can be retried`() {
        val hash = assets.put(png(1080, 1350))
        val infographic = SocialResult(SOURCE, "infographic", "failed", artifact = SocialArtifact(image = hash, title = "Idea", description = "Described"), error = "No connection")
        show(infographic)
        compose.onNodeWithText("Made with an earlier version of posts.").assertExists()
        compose.onNodeWithText("Share").assertExists()
        compose.onNodeWithText("Change").assertDoesNotExist()
        compose.onNodeWithText("Caption").assertDoesNotExist()
        compose.onNodeWithText("No connection").assertExists()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithContentDescription("More post actions").performClick()
        compose.onNodeWithText("Copy description & sources").assertIsDisplayed()
        compose.onNodeWithText("Save image").assertIsDisplayed()
        assertEquals(listOf("retry"), calls)
        assertEquals("Earlier infographic", postKind(infographic))
        assertEquals("Earlier text post", postKind(SocialResult(SOURCE, "discussion", "ready", artifact = SocialArtifact(text = "Text"))))
        assertEquals("Earlier draft", postKind(SocialResult.parse(PostContent(body = "Old").json())))
    }

    @Test fun `an earlier text post reads as before without Change`() {
        show(SocialResult(SOURCE, "discussion", "ready", artifact = SocialArtifact(text = "A thoughtful discussion.", quote = SOURCE.quote)))
        compose.onNodeWithText("“${SOURCE.quote}”").assertExists()
        compose.onNodeWithText("A thoughtful discussion.").assertExists()
        compose.onNodeWithText("Made with an earlier version of posts.").assertExists()
        compose.onNodeWithText("Change").assertDoesNotExist()
    }

    @Test fun `large text keeps the actions apart and the layout readable`() {
        show(card(), fontScale = 1.6f)
        awaitCard(CardContent.STYLE_QUOTE)
        val share = compose.onNodeWithText("Share").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val change = compose.onNodeWithText("Change").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertFalse(share.overlaps(change))
        val styles = listOf("Quote", "Big idea", "Takeaways").map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        for (i in styles.indices) for (j in styles.indices) if (i < j) assertFalse(styles[i].overlaps(styles[j]))
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertTrue(styles.all { it.right <= width })
    }

    @Test fun `screenshot of the card result`() {
        show(card())
        awaitCard(CardContent.STYLE_QUOTE)
        compose.waitForIdle()
        save("screen-card-result", screen())
    }

    /** The whole scrolling page in one picture, for layout review. */
    @Config(qualifiers = "w384dp-h1500dp-xxhdpi")
    @Test fun `screenshot of the full card result`() {
        show(card(previous = cardArtifact()))
        awaitCard(CardContent.STYLE_QUOTE)
        compose.waitForIdle()
        save("screen-card-result-full", screen())
    }

    @Test fun `screenshot at large text`() {
        show(card(), fontScale = 1.6f)
        awaitCard(CardContent.STYLE_QUOTE)
        compose.waitForIdle()
        save("screen-card-result-font-1.6", screen())
    }

    /** Draws the window in software; captureToImage waits for a hardware redraw Robolectric never reports. */
    private fun screen(): Bitmap {
        val view = compose.activity.window.decorView
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
    }

    private fun save(name: String, image: Bitmap) {
        val directory = File(System.getProperty("user.dir"), "build/share-card-samples").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
