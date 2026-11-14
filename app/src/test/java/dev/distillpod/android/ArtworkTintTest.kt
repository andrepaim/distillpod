package dev.distillpod.android

import androidx.core.graphics.ColorUtils
import dev.distillpod.android.features.darkArtworkTone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.hypot

/** The player backdrop is a dark, moderate tone of the cover (proposal 3.3), whatever the cover's colour. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
}
