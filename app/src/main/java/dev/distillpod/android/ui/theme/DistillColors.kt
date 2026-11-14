package dev.distillpod.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Ember palette tokens (docs/android/design-system-proposal.md, section 3.1). Chrome stays
 * warm-neutral; the amber accent is reserved for the Distill action, saved moments, selection
 * and at most one filled button per screen.
 */
internal object Ember {
    val Ink = Color(0xFF0F0E0D)
    val S0 = Color(0xFF0B0A09)
    val S1 = Color(0xFF191816)
    val S2 = Color(0xFF22201D)
    val S3 = Color(0xFF2C2925)
    val S4 = Color(0xFF36322D)
    val SurfaceBright = Color(0xFF3D3933)
    val Text = Color(0xFFF2EEE8)
    val Muted = Color(0xFFABA397)
    val Outline = Color(0xFF7A7266)
    val OutlineVariant = Color(0xFF3A3631)
    val Accent = Color(0xFFFFB547)
    val OnAccent = Color(0xFF2A1800)
    val AccentContainer = Color(0xFF3B2A12)
    val OnAccentContainer = Color(0xFFFFDDB0)
    val Secondary = Color(0xFFD6CCBE)
    val OnSecondary = Color(0xFF2A251E)
    val Warning = Color(0xFFFF8A65)
    val OnWarning = Color(0xFF3A1206)
    val WarningContainer = Color(0xFF4A1F10)
    val OnWarningContainer = Color(0xFFFFDBCF)
    val Error = Color(0xFFFF6B6B)
    val OnError = Color(0xFF3B0606)
    val ErrorContainer = Color(0xFF5C1A1A)
    val OnErrorContainer = Color(0xFFFFDAD6)
    val Success = Color(0xFF7BD88F)
    val InversePrimary = Color(0xFF8A5300)
}

/**
 * Every Material 3 role is set explicitly. An unset role silently falls back to the M3 baseline
 * purple, which previously showed on screen as #4A4458 and #36343B (#203).
 * `background` is the page; `surface` is the card colour the screens already build on.
 */
internal val EmberDarkColors: ColorScheme = darkColorScheme(
    primary = Ember.Accent,
    onPrimary = Ember.OnAccent,
    primaryContainer = Ember.AccentContainer,
    onPrimaryContainer = Ember.OnAccentContainer,
    inversePrimary = Ember.InversePrimary,
    secondary = Ember.Secondary,
    onSecondary = Ember.OnSecondary,
    secondaryContainer = Ember.AccentContainer,
    onSecondaryContainer = Ember.OnAccentContainer,
    tertiary = Ember.Warning,
    onTertiary = Ember.OnWarning,
    tertiaryContainer = Ember.WarningContainer,
    onTertiaryContainer = Ember.OnWarningContainer,
    background = Ember.Ink,
    onBackground = Ember.Text,
    surface = Ember.S1,
    onSurface = Ember.Text,
    surfaceVariant = Ember.S4,
    onSurfaceVariant = Ember.Muted,
    surfaceTint = Ember.Accent,
    inverseSurface = Ember.Text,
    inverseOnSurface = Ember.S2,
    error = Ember.Error,
    onError = Ember.OnError,
    errorContainer = Ember.ErrorContainer,
    onErrorContainer = Ember.OnErrorContainer,
    outline = Ember.Outline,
    outlineVariant = Ember.OutlineVariant,
    scrim = Color.Black,
    surfaceBright = Ember.SurfaceBright,
    surfaceDim = Ember.Ink,
    surfaceContainerLowest = Ember.S0,
    surfaceContainerLow = Ember.S1,
    surfaceContainer = Ember.S2,
    surfaceContainerHigh = Ember.S3,
    surfaceContainerHighest = Ember.S4,
)

/** Semantic colours that Material 3 has no role for. */
@Immutable internal data class DistillExtras(val success: Color, val warning: Color)

internal val EmberExtras = DistillExtras(success = Ember.Success, warning = Ember.Warning)

internal val LocalDistillExtras = staticCompositionLocalOf { EmberExtras }

internal val MaterialTheme.extras: DistillExtras
    @Composable @ReadOnlyComposable get() = LocalDistillExtras.current
