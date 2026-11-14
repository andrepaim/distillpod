package dev.distillpod.android

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.distillpod.android.ui.theme.Ember
import dev.distillpod.android.ui.theme.EmberDarkColors
import dev.distillpod.android.ui.theme.EmberExtras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ember theme guards (#203): no role falls back to the M3 baseline, and key pairs stay readable. */
class EmberThemeTest {
    private val roles: List<Pair<String, (ColorScheme) -> Color>> = listOf(
        "primary" to { it.primary }, "onPrimary" to { it.onPrimary },
        "primaryContainer" to { it.primaryContainer }, "onPrimaryContainer" to { it.onPrimaryContainer },
        "inversePrimary" to { it.inversePrimary },
        "secondary" to { it.secondary }, "onSecondary" to { it.onSecondary },
        "secondaryContainer" to { it.secondaryContainer }, "onSecondaryContainer" to { it.onSecondaryContainer },
        "tertiary" to { it.tertiary }, "onTertiary" to { it.onTertiary },
        "tertiaryContainer" to { it.tertiaryContainer }, "onTertiaryContainer" to { it.onTertiaryContainer },
        "background" to { it.background }, "onBackground" to { it.onBackground },
        "surface" to { it.surface }, "onSurface" to { it.onSurface },
        "surfaceVariant" to { it.surfaceVariant }, "onSurfaceVariant" to { it.onSurfaceVariant },
        "surfaceTint" to { it.surfaceTint }, "inverseSurface" to { it.inverseSurface }, "inverseOnSurface" to { it.inverseOnSurface },
        "error" to { it.error }, "onError" to { it.onError },
        "errorContainer" to { it.errorContainer }, "onErrorContainer" to { it.onErrorContainer },
        "outline" to { it.outline }, "outlineVariant" to { it.outlineVariant },
        "surfaceBright" to { it.surfaceBright }, "surfaceDim" to { it.surfaceDim },
        "surfaceContainerLowest" to { it.surfaceContainerLowest }, "surfaceContainerLow" to { it.surfaceContainerLow },
        "surfaceContainer" to { it.surfaceContainer }, "surfaceContainerHigh" to { it.surfaceContainerHigh },
        "surfaceContainerHighest" to { it.surfaceContainerHighest },
    )

    @Test fun everyRoleIsSetExplicitly() {
        val baseline = darkColorScheme()
        val leaked = roles.filter { (_, role) -> role(EmberDarkColors) == role(baseline) }.map { it.first }
        assertEquals("Roles left at the M3 baseline", emptyList<String>(), leaked)
    }

    @Test fun baselineLeaksFromTheReviewAreGone() {
        val leaks = listOf(0xFF4A4458, 0xFF36343B, 0xFF2B2930, 0xFF211F26).map { Color(it) }
        val used = roles.map { it.second(EmberDarkColors) }
        assertTrue(used.none { it in leaks })
    }

    @Test fun keyPairsMeetContrast() {
        val c = EmberDarkColors
        val text = listOf(
            "text on background" to (c.onBackground to c.background),
            "text on card" to (c.onSurface to c.surface),
            "muted on card" to (c.onSurfaceVariant to c.surface),
            "muted on high container" to (c.onSurfaceVariant to c.surfaceContainerHigh),
            "accent on background" to (c.primary to c.background),
            "on accent" to (c.onPrimary to c.primary),
            "on accent container" to (c.onPrimaryContainer to c.primaryContainer),
            "on selected chip" to (c.onSecondaryContainer to c.secondaryContainer),
            "error on card" to (c.error to c.surface),
            "warning on card" to (c.tertiary to c.surface),
            "success on card" to (EmberExtras.success to c.surface),
        )
        text.forEach { (name, pair) -> assertTrue("$name: ${ratio(pair.first, pair.second)}", ratio(pair.first, pair.second) >= 4.5) }
        // Component boundaries need 3:1 (WCAG 1.4.11).
        assertTrue(ratio(c.outline, c.background) >= 3.0)
        assertTrue(ratio(c.outline, c.surfaceContainer) >= 3.0)
        assertTrue(ratio(Ember.Accent, Ember.S3) >= 3.0)
    }

    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
