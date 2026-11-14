package dev.distillpod.android

import android.content.Context
import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.CardContent
import dev.distillpod.android.data.CardSize
import dev.distillpod.android.data.CardTakeaway
import dev.distillpod.android.sharing.ShareCardRenderer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareCardRendererTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val samples = File("build/share-card-samples").apply { mkdirs() }

    private val teal = 0xFF2D545E.toInt()
    private val red = 0xFFB0302A.toInt()

    private val english = CardContent(
        quote = "Feedback is useful when it helps you see the gap between what you tried and what happened.",
        highlight = "the gap",
        headline = "More feedback isn't better feedback.",
        pull = "A specific observation gives you something to change on the next attempt.",
        takeawaysTitle = "Feedback that actually helps",
        takeaways = listOf(CardTakeaway("Show the gap", "Between what you tried and what happened."),
            CardTakeaway("Be specific", "Give one thing to change next time."),
            CardTakeaway("More isn't better", "Timing and the task decide if it lands.")),
        cover = "a".repeat(64), tint = teal, topic = "Feedback")

    private val portuguese = CardContent(
        quote = "Mas uma interrupção também pode ser necessária: a prioridade deve considerar o trabalho e as outras pessoas, não apenas proteger o foco.",
        highlight = "as outras pessoas",
        headline = "Foco é meio, não fim.",
        pull = "Agrupar tarefas semelhantes pode reduzir essas transições.",
        takeawaysTitle = "Foco a serviço do trabalho",
        takeaways = listOf(CardTakeaway("Agrupe tarefas parecidas", "Menos trocas de contexto, menos tempo para retomar o ritmo."),
            CardTakeaway("Algumas pausas são necessárias", "O trabalho e as outras pessoas também contam."),
            CardTakeaway("Combine critérios", "Decida antes o que justifica interromper um período de foco.")),
        cover = "b".repeat(64), tint = red, topic = "Foco no trabalho")

    // Every field at its contract limit, written with long real words rather than filler.
    private val longPortuguese = CardContent(
        quote = "A responsabilidade compartilhada é central: quando as organizações convertem reuniões intermináveis em comunicação assíncrona, documentada e auditável, as equipes reconquistam concentração, autonomia e previsibilidade no trabalho cotidiano.",
        highlight = "responsabilidade compartilhada é central",
        headline = "Responsabilidade compartilhada exige comunicação documentada",
        pull = "Quando as organizações documentam claramente decisões, as equipes recuperam concentração, autonomia e previsibilidade no trabalho cotidiano.",
        takeawaysTitle = "Responsabilidade compartilhada, sem interrupções",
        takeaways = listOf(CardTakeaway("Documentação assíncrona primeiro", "Registre decisões importantes para que ninguém dependa de mais reuniões."),
            CardTakeaway("Interrupções só intencionalmente", "Combine antecipadamente que situações justificam interromper os colegas."),
            CardTakeaway("Previsibilidade e transparência!", "Compartilhe prioridades semanalmente, evitando surpresas desnecessárias.")),
        cover = "c".repeat(64), tint = red, topic = "Documentação e confiança")

    private val longEnglish = CardContent(
        quote = "Counterproductive feedback usually arrives as uncharacteristically enthusiastic praise, while what people really need is a specific, actionable observation about the distance between their intentions and the consequences of their decisions.",
        highlight = "uncharacteristically enthusiastic praise",
        headline = "Counterproductive feedback undermines organisational growth.",
        pull = "What people actually need is a specific, actionable observation about the distance between their intentions and their eventual consequences.",
        takeawaysTitle = "Counterproductive feedback and how to replace it",
        takeaways = listOf(CardTakeaway("Acknowledge the measurable gaps.", "Describe the distance between intentions and consequences, specifically."),
            CardTakeaway("Recommend one concrete change...", "Uncharacteristically enthusiastic generalisations rarely help teammates."),
            CardTakeaway("Synchronise timing with the task", "Feedback delivered immediately after an important attempt is remembered.")),
        cover = "d".repeat(64), tint = teal, topic = "Organisational learning!")

    // Contract-valid text with one word wider than the column at the 60% floor in some sizes.
    private val longWordPortuguese = longPortuguese.copy(
        headline = "Reuniões desproporcionalmente longas custam o foco da equipe",
        takeawaysTitle = "Desproporcionalmente: três ideias para reuniões.",
        takeaways = listOf(CardTakeaway("Desproporcionalmente demoradas", longPortuguese.takeaways[0].detail)) + longPortuguese.takeaways.drop(1))

    private val longWordEnglish = longEnglish.copy(
        headline = "Pseudopseudohypoparathyroidism is rarer than people suspect.",
        takeawaysTitle = "Pseudopseudohypoparathyroidism, briefly put",
        takeaways = listOf(CardTakeaway("A pseudopseudohypoparathyroidism", longEnglish.takeaways[0].detail)) + longEnglish.takeaways.drop(1))

    private val minimal = CardContent(quote = "Rest more.", headline = "Less is more.", takeawaysTitle = "Three notes",
        takeaways = listOf(CardTakeaway("Rest", "Sleep well."), CardTakeaway("Walk", "Every day."), CardTakeaway("Read", "A little.")),
        cover = "e".repeat(64), tint = teal)

    private val longShow = "The Extraordinarily Long-Running Conversation About Organisational Psychology and Leadership"
    private val longEpisode = "Episode 412: Why counterproductive feedback loops persist in otherwise high-performing distributed teams"

    /** A synthetic square cover: a tinted field with a few shapes, so crop, rounding and grayscale are visible. */
    private fun cover(tint: Int): Bitmap = Bitmap.createBitmap(CardContent.COVER_PX, CardContent.COVER_PX, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.drawColor(tint)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFFE1B382.toInt(); canvas.drawCircle(220f, 110f, 70f, paint)
        paint.color = 0xFF12343B.toInt(); canvas.drawRect(0f, 220f, 320f, 320f, paint)
        paint.color = 0xFFF4F1EC.toInt(); canvas.drawRect(40f, 250f, 200f, 270f, paint)
    }

    private fun write(name: String, bitmap: Bitmap) = File(samples, "$name.png").writeBytes(ShareCardRenderer.png(bitmap))

    private fun sizeName(size: CardSize) = size.name.lowercase()

    /** Renders every style, look and size, checks the basics and writes the samples. */
    private fun matrix(prefix: String, card: CardContent, show: String, episode: String, withCover: Boolean) {
        val art = if (withCover) cover(card.tint) else null
        try {
            for (style in CardContent.STYLES) for (look in CardContent.LOOKS) for (size in CardSize.entries) {
                val bitmap = ShareCardRenderer.render(context, card, show, episode, art, size, style, look)
                try {
                    assertEquals(size.width, bitmap.width)
                    assertEquals(size.height, bitmap.height)
                    assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
                    assertFalse(bitmap.isRecycled)
                    val drawnLook = if (look == CardContent.LOOK_COVER && art == null) CardContent.LOOK_EMBER else look
                    when (drawnLook) {
                        CardContent.LOOK_SIGNAL -> {
                            assertEquals(0xFFFFB547.toInt(), bitmap.getPixel(0, 0))
                            assertMarginsClear(bitmap, 0xFFFFB547.toInt())
                        }
                        CardContent.LOOK_EMBER -> assertNear(0xFF0F0E0D.toInt(), bitmap.getPixel(0, bitmap.height - 1), 6)
                        else -> assertTrue("cover background is dark", luminance(bitmap.getPixel(0, bitmap.height - 1)) < .08)
                    }
                    write("$prefix-$style-$look-${sizeName(size)}", bitmap)
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            art?.recycle()
        }
    }

    @Test fun `english sample renders in every style look and size`() = matrix("en", english, "Sample show", "Learning from feedback", true)

    @Test fun `portuguese sample renders in every style look and size`() = matrix("pt", portuguese, "Amostra pública", "Atenção no trabalho", true)

    @Test fun `a quote cut from mid-sentence opens with a capital and keeps its highlight`() {
        fun draw(card: CardContent, style: String, look: String) =
            ShareCardRenderer.render(context, card, "Show", "Episode", cover(teal), style = style, look = look)
        val capital = english.copy(quote = "Small steps make practice easier.", highlight = "Small steps", pull = "Big plans often stall.")
        val lower = capital.copy(quote = "small steps make practice easier.", highlight = "small steps", pull = "big plans often stall.")
        for (look in CardContent.LOOKS) {
            assertTrue(look, draw(capital, CardContent.STYLE_QUOTE, look).sameAs(draw(lower, CardContent.STYLE_QUOTE, look)))
        }
        assertTrue(draw(capital, CardContent.STYLE_IDEA, CardContent.LOOK_EMBER).sameAs(draw(lower, CardContent.STYLE_IDEA, CardContent.LOOK_EMBER)))
        // A highlight elsewhere in the quote is untouched.
        val later = lower.copy(highlight = "practice")
        assertTrue(draw(capital.copy(highlight = "practice"), CardContent.STYLE_QUOTE, CardContent.LOOK_SIGNAL).sameAs(draw(later, CardContent.STYLE_QUOTE, CardContent.LOOK_SIGNAL)))
    }

    @Test fun `maximum length content fits everywhere`() {
        for (card in listOf(longPortuguese, longEnglish)) {
            assertEquals(CardContent.QUOTE_MAX, card.quote.length)
            assertEquals(CardContent.HIGHLIGHT_MAX, card.highlight.length)
            assertTrue(card.quote.contains(card.highlight))
            assertEquals(CardContent.HEADLINE_MAX, card.headline.length)
            assertEquals(CardContent.PULL_MAX, card.pull.length)
            assertEquals(CardContent.TAKEAWAYS_TITLE_MAX, card.takeawaysTitle.length)
            assertEquals(CardContent.TOPIC_MAX, card.topic.length)
            card.takeaways.forEach {
                assertEquals(CardContent.TAKEAWAY_TITLE_MAX, it.title.length)
                assertEquals(CardContent.TAKEAWAY_DETAIL_MAX, it.detail.length)
            }
        }
        matrix("max-pt", longPortuguese, longShow, longEpisode, true)
        matrix("max-en", longEnglish, longShow, longEpisode, true)
    }

    @Test fun `every fixture is valid under the contract`() {
        for (card in listOf(english, portuguese, longPortuguese, longEnglish, longWordPortuguese, longWordEnglish, minimal)) {
            card.validated(card.quote + " " + card.pull)
        }
    }

    @Test fun `one very long word still draws instead of failing`() {
        assertTrue(longWordPortuguese.headline.contains("desproporcionalmente"))
        assertTrue(longWordEnglish.headline.split(" ").any { it.length == 30 })
        assertTrue(longWordEnglish.takeaways[0].title.split(" ").any { it.length == 30 })
        matrix("longword-pt", longWordPortuguese, "Amostra pública", "Atenção no trabalho", true)
        matrix("longword-en", longWordEnglish, "Sample show", "Learning from feedback", true)
    }

    @Test fun `minimal content renders`() = matrix("min", minimal, "Sample show", "Learning from feedback", true)

    @Test fun `missing cover draws ember for the cover look and omits the thumbnail`() {
        matrix("nocover", english, "Sample show", "Learning from feedback", false)
        val noTint = ShareCardRenderer.render(context, english.copy(tint = 0), "Sample show", "Episode", cover(teal), look = CardContent.LOOK_COVER)
        try { assertNear(0xFF0F0E0D.toInt(), noTint.getPixel(0, noTint.height - 1), 6) } finally { noTint.recycle() }
    }

    @Test fun `very long show and episode names are ellipsized`() = matrix("longnames", portuguese, longShow, longEpisode, true)

    @Test fun `unknown style or look is refused`() {
        try { ShareCardRenderer.render(context, english, "Show", "Episode", null, style = "poster"); fail("unknown style accepted") }
        catch (_: IllegalArgumentException) {}
        try { ShareCardRenderer.render(context, english, "Show", "Episode", null, look = "neon"); fail("unknown look accepted") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun `text beyond the contract is refused instead of clipped`() {
        val tooLong = longEnglish.copy(quote = (longEnglish.quote + " ").repeat(3).trim())
        for (look in CardContent.LOOKS) {
            try {
                ShareCardRenderer.render(context, tooLong, "Show", "Episode", cover(teal), style = CardContent.STYLE_QUOTE, look = look)
                fail("overlong quote accepted in $look")
            } catch (e: IllegalArgumentException) { assertEquals("The card text needs shortening.", e.message) }
        }
    }

    @Test fun `png encodes the card`() {
        val bitmap = ShareCardRenderer.render(context, english, "Sample show", "Learning from feedback", cover(teal))
        try {
            val bytes = ShareCardRenderer.png(bitmap)
            assertArrayEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), bytes.copyOf(4))
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(1080, decoded.width); assertEquals(1350, decoded.height)
            decoded.recycle()
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun `rendering is deterministic`() {
        val first = ShareCardRenderer.render(context, portuguese, "Amostra pública", "Atenção no trabalho", cover(red), style = CardContent.STYLE_QUOTE, look = CardContent.LOOK_SIGNAL)
        val second = ShareCardRenderer.render(context, portuguese, "Amostra pública", "Atenção no trabalho", cover(red), style = CardContent.STYLE_QUOTE, look = CardContent.LOOK_SIGNAL)
        try { assertTrue(first.sameAs(second)) } finally { first.recycle(); second.recycle() }
    }

    /** The bundled fonts are Latin subsets, so every character the samples draw must be in each face's cmap. */
    @Test fun `bundled fonts contain every glyph the cards draw`() {
        val text = listOf(english, portuguese, longPortuguese, longEnglish).joinToString(" ") { card ->
            listOf(card.quote, card.headline, card.pull, card.takeawaysTitle).plus(card.takeaways.flatMap { listOf(it.title, it.detail) }).joinToString(" ")
        } + "“”‘’…·—–123ÁÉÍÓÚÂÊÔÃÕÇÀáéíóúâêôãõçàDISTILLPOD" + longShow + longEpisode
        val needed = text.filterNot { it.isWhitespace() }.toSet()
        for (name in listOf("inter_bold", "inter_semibold", "inter_regular", "newsreader_regular", "newsreader_italic")) {
            val covered = cmap(File("src/main/res/font/$name.ttf").readBytes())
            val missing = needed.filter { it.code !in covered }
            assertTrue("$name lacks ${missing.joinToString("")}", missing.isEmpty())
        }
    }

    /** Code points mapped by the font's format 4 and 12 cmap subtables. */
    private fun cmap(font: ByteArray): Set<Int> {
        val data = ByteBuffer.wrap(font)
        val tables = data.getShort(4).toInt() and 0xFFFF
        val cmap = (0 until tables).map { 12 + 16 * it }.first { String(font, it, 4) == "cmap" }.let { data.getInt(it + 8) }
        val codes = mutableSetOf<Int>()
        for (i in 0 until (data.getShort(cmap + 2).toInt() and 0xFFFF)) {
            val offset = cmap + data.getInt(cmap + 8 + 8 * i)
            when (data.getShort(offset).toInt() and 0xFFFF) {
                4 -> {
                    val segments = (data.getShort(offset + 6).toInt() and 0xFFFF) / 2
                    for (s in 0 until segments) {
                        val end = data.getShort(offset + 14 + 2 * s).toInt() and 0xFFFF
                        val start = data.getShort(offset + 16 + 2 * segments + 2 * s).toInt() and 0xFFFF
                        if (start != 0xFFFF) codes.addAll(start..end)
                    }
                }
                12 -> for (g in 0 until data.getInt(offset + 12)) {
                    val base = offset + 16 + 12 * g
                    codes.addAll(data.getInt(base)..data.getInt(base + 4))
                }
            }
        }
        return codes
    }

    /** Nothing may be drawn in the outer 6u of a solid card: text never escapes its margins. */
    private fun assertMarginsClear(bitmap: Bitmap, background: Int) {
        val band = (bitmap.width * .06f).toInt()
        for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
            val inside = x in band until bitmap.width - band && y in band until bitmap.height - band
            if (!inside) assertEquals("pixel $x,$y", background, bitmap.getPixel(x, y))
        }
    }

    private fun assertNear(expected: Int, actual: Int, tolerance: Int) {
        val ok = listOf(16, 8, 0).all { abs((expected shr it and 0xFF) - (actual shr it and 0xFF)) <= tolerance }
        assertTrue("expected ${Integer.toHexString(expected)} near ${Integer.toHexString(actual)}", ok)
    }

    private fun luminance(color: Int) = androidx.core.graphics.ColorUtils.calculateLuminance(color)
}
