package dev.distillpod.android

import androidx.core.graphics.ColorUtils
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.palette.graphics.Palette
import dev.distillpod.android.features.cardSwatch
import dev.distillpod.android.features.darkArtworkTone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.hypot

/** The player backdrop is a dark, moderate tone of the cover (proposal 3.3), whatever the cover's colour. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkTintTest {
    private fun lab(argb: Int) = DoubleArray(3).also { ColorUtils.colorToLAB(argb, it) }

    @Test fun brightSaturatedCoversBecomeADeepModerateTone() {
        listOf(0xFFFF0000.toInt(), 0xFFFFEB3B.toInt(), 0xFF00E5FF.toInt(), 0xFF7C4DFF.toInt(), 0xFFFFFFFF.toInt()).forEach { cover ->
            val tone = lab(darkArtworkTone(cover))
            assertEquals("lightness for ${Integer.toHexString(cover)}", 25.0, tone[0], 1.5)
            assertTrue("chroma for ${Integer.toHexString(cover)}", hypot(tone[1], tone[2]) <= 36.5)
        }
    }

    @Test fun keepsTheCoversHue() {
        val red = darkArtworkTone(0xFFE53935.toInt())
        assertTrue(android.graphics.Color.red(red) > android.graphics.Color.blue(red))
        val blue = darkArtworkTone(0xFF1E88E5.toInt())
        assertTrue(android.graphics.Color.blue(blue) > android.graphics.Color.red(blue))
    }

    @Test fun lightTextStaysReadableOnTheTone() {
        val text = 0xFFF2EEE8.toInt()
        listOf(0xFFFFEB3B.toInt(), 0xFF00E676.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt()).forEach { cover ->
            assertTrue(ColorUtils.calculateContrast(text, darkArtworkTone(cover)) >= 7.0)
        }
    }

    /** Two blocks: [first] over the top [share] of the picture, [second] below it. */
    private fun cover(first: Int, second: Int, share: Float): Palette {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawRect(0f, 0f, 100f, share * 100, Paint().apply { color = first })
            drawRect(0f, share * 100, 100f, 100f, Paint().apply { color = second })
        }
        return Palette.from(bitmap).maximumColorCount(16).generate()
    }

    private fun near(expected: Int, actual: Int?) = actual != null && listOf(16, 8, 0).all { shift ->
        kotlin.math.abs((expected shr shift and 0xFF) - (actual shr shift and 0xFF)) <= 12
    }

    @Test fun theCardTakesTheColourMostOfTheCoverCarriesOverADullLargestBlock() {
        // An olive-grey background slightly larger than a dusty red: the card should be red, not muddy olive.
        val swatch = cardSwatch(cover(0xFF686850.toInt(), 0xFF8A3C38.toInt(), .55f))
        assertTrue("got ${swatch?.let(Integer::toHexString)}", near(0xFF8A3C38.toInt(), swatch))
    }

    @Test fun theCardKeepsAColourfulLargestBlockAndIgnoresNearBlack() {
        assertTrue(near(0xFF1E6FB0.toInt(), cardSwatch(cover(0xFF1E6FB0.toInt(), 0xFFB0302A.toInt(), .7f))))
        assertTrue(near(0xFF2D545E.toInt(), cardSwatch(cover(0xFF050505.toInt(), 0xFF2D545E.toInt(), .7f))))
    }
}
