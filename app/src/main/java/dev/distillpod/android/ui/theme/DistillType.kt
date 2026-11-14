package dev.distillpod.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.distillpod.android.R

/** Bundled, Latin-subset static instances (OFL 1.1; see THIRD_PARTY_NOTICES.md). */
internal object DistillFonts {
    val Inter = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
        Font(R.font.inter_bold, FontWeight.Bold),
    )
    val Newsreader = FontFamily(
        Font(R.font.newsreader_regular, FontWeight.Normal),
        Font(R.font.newsreader_medium, FontWeight.Medium),
        Font(R.font.newsreader_italic, FontWeight.Normal, FontStyle.Italic),
    )
}

private fun inter(size: Number, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = DistillFonts.Inter, fontWeight = weight, fontSize = size.toFloat().sp, lineHeight = line.sp, letterSpacing = tracking.sp)

/**
 * Interface type scale. Section headings use titleSmall (15 sp bold) so the top bar stays the
 * largest text on a screen; reading text uses [DistillType].
 */
internal val DistillTypography = Typography(
    displayLarge = inter(52, 60, FontWeight.Bold, -1.0),
    displayMedium = inter(40, 48, FontWeight.Bold, -0.8),
    displaySmall = inter(28, 34, FontWeight.Bold, -0.5),
    headlineLarge = inter(30, 36, FontWeight.Bold, -0.5),
    headlineMedium = inter(26, 32, FontWeight.Bold, -0.4),
    headlineSmall = inter(22, 28, FontWeight.Bold, -0.3),
    titleLarge = inter(20, 26, FontWeight.Bold, -0.2),
    titleMedium = inter(17, 22, FontWeight.SemiBold, -0.1),
    titleSmall = inter(15, 20, FontWeight.SemiBold),
    bodyLarge = inter(16, 24, FontWeight.Normal),
    bodyMedium = inter(14, 20, FontWeight.Normal),
    bodySmall = inter(12.5, 17, FontWeight.Normal, 0.1),
    labelLarge = inter(14, 20, FontWeight.SemiBold, 0.1),
    labelMedium = inter(12.5, 16, FontWeight.Medium, 0.2),
    labelSmall = inter(11, 14, FontWeight.SemiBold, 0.3).copy(fontFeatureSettings = "tnum"),
)

/** Editorial styles for text people read rather than scan: quotes, insights and transcripts. */
internal object DistillType {
    val quote = TextStyle(fontFamily = DistillFonts.Newsreader, fontWeight = FontWeight.Normal, fontSize = 21.sp, lineHeight = 28.sp)
    val quoteCompact = TextStyle(fontFamily = DistillFonts.Newsreader, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 23.sp)
    val reading = TextStyle(fontFamily = DistillFonts.Newsreader, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp)
}
