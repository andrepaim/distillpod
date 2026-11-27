package dev.distillpod.android

import android.app.Activity
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import dev.distillpod.android.SocialFixtures.CARD
import dev.distillpod.android.SocialFixtures.SOURCE
import dev.distillpod.android.SocialFixtures.cardArtifact
import dev.distillpod.android.SocialFixtures.png
import dev.distillpod.android.data.*
import dev.distillpod.android.sharing.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Card sharing, Story export and saving, and earlier results sharing as they always did. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PostShareTest {
    @After fun resetProviderCache() {
        // Robolectric gives each test a new data directory, while FileProvider caches roots statically.
        (FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as MutableMap<*, *>).clear()
    }
    private val context = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val assets = PostAssets(java.nio.file.Files.createTempDirectory("post-share").toFile())
    private fun card(card: CardContent = CARD) = SocialResult(SOURCE, SocialResult.CARD, "ready", artifact = cardArtifact(card))

    /** The intent inside the chooser most recently started. */
    private fun shared(): Intent {
        val chooser = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Share post", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
        return chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
    }
    private fun stream(intent: Intent): ByteArray =
        context.contentResolver.openInputStream(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!)!!.use { it.readBytes() }
    private fun decode(bytes: ByteArray): Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
    private fun rendered(card: CardContent, cover: Bitmap? = null, size: CardSize = CardSize.Feed) =
        ShareCardRenderer.render(context, card, SOURCE.show, SOURCE.episode, cover, size)

    @Test fun `a card shares as a feed PNG with the caption as text and on the clipboard`() = runBlocking {
        val result = card()
        PostShareController.shareCard(context, assets, result)
        val intent = shared()
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/png", intent.type)
        assertEquals(result.artifact!!.text, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertEquals(result.artifact!!.text, intent.clipData!!.getItemAt(0).text)
        val image = decode(stream(intent))
        assertEquals(1080, image.width); assertEquals(1350, image.height)
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals(result.artifact!!.text, clip.getItemAt(0).text.toString())
    }

    @Test fun `story export is 9 by 16`() = runBlocking {
        PostShareController.shareCard(context, assets, card(), CardSize.Story)
        val image = decode(stream(shared()))
        assertEquals(1080, image.width); assertEquals(1920, image.height)
        assertTrue(image.sameAs(rendered(CARD, size = CardSize.Story)))
    }

    @Test fun `share draws the stored style and look`() = runBlocking {
        val stored = CARD.copy(style = CardContent.STYLE_TAKEAWAYS, look = CardContent.LOOK_SIGNAL)
        PostShareController.shareCard(context, assets, card(stored))
        val image = decode(stream(shared()))
        assertTrue(image.sameAs(rendered(stored)))
        assertFalse(image.sameAs(rendered(CARD)))
        assertEquals(0xFFFFB547.toInt(), image.getPixel(4, 4))
    }

    @Test fun `a card whose cover is missing still shares as Ember and a stored cover is drawn`() = runBlocking {
        val missing = CARD.copy(look = CardContent.LOOK_COVER, cover = "c".repeat(64), tint = 0xFF2266AA.toInt())
        PostShareController.shareCard(context, assets, card(missing))
        val fallback = decode(stream(shared()))
        assertEquals(1350, fallback.height)
        assertTrue(fallback.sameAs(rendered(CARD.copy(look = CardContent.LOOK_EMBER))))
        val hash = assets.put(png(CardContent.COVER_PX, CardContent.COVER_PX))
        val stored = missing.copy(cover = hash)
        PostShareController.shareCard(context, assets, card(stored))
        val withCover = decode(stream(shared()))
        assertTrue(withCover.sameAs(rendered(stored, decode(assets.read(hash)))))
        assertFalse(withCover.sameAs(fallback))
    }

    /** The one image saved through MediaStore (Robolectric's fake media provider), once it is no longer pending. */
    private fun savedImage(): ByteArray {
        val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.IS_PENDING)
        val id = context.contentResolver.query(images, columns, null, null, null)!!.use { row ->
            assertEquals(1, row.count)
            row.moveToFirst()
            assertEquals("image/png", row.getString(1))
            assertEquals("Pictures/DistillPod", row.getString(2).trimEnd('/'))
            assertEquals(0, row.getInt(3))
            row.getLong(0)
        }
        return context.contentResolver.openInputStream(ContentUris.withAppendedId(images, id))!!.use { it.readBytes() }
    }

    @Test fun `save writes the feed card to Pictures DistillPod`() = runBlocking {
        PostShareController.saveCard(context, assets, card())
        val image = decode(savedImage())
        assertEquals(1080, image.width); assertEquals(1350, image.height)
        assertTrue(image.sameAs(rendered(CARD)))
    }

    @Test fun `earlier infographic and text results share as before`() = runBlocking {
        val hash = assets.put(png(1080, 1350))
        val infographic = SocialResult(SOURCE, "infographic", "ready", artifact = SocialArtifact(image = hash, description = "Sources and accessible description"))
        PostShareController.shareResult(context, assets, infographic)
        val image = shared()
        assertEquals("image/png", image.type)
        assertEquals("Sources and accessible description", image.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(image.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertArrayEquals(assets.read(hash), stream(image))
        // Content decides, not format: Undo can return a card post to its earlier image.
        PostShareController.shareResult(context, assets, infographic.copy(format = SocialResult.CARD))
        assertArrayEquals(assets.read(hash), stream(shared()))
        val text = SocialResult(SOURCE, "discussion", "ready", artifact = SocialArtifact(text = "A thoughtful discussion.", quote = SOURCE.quote))
        PostShareController.shareResult(context, assets, text)
        val plain = shared()
        assertEquals("text/plain", plain.type)
        assertEquals(text.shareText(), plain.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(plain.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        PostShareController.saveResult(context, assets, infographic.artifact!!)
        assertArrayEquals(assets.read(hash), savedImage())
    }
}
