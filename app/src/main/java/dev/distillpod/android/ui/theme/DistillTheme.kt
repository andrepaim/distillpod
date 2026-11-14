package dev.distillpod.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

/** Corner radii: xs 8, sm 12, md 16, lg 24 (proposal section 3.5). */
internal val DistillShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 4 dp spacing grid. */
internal object DistillSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val item = 12.dp
    val gutter = 16.dp
    val page = 20.dp
    val section = 24.dp
}

/** The app theme: Ember dark colours, Inter/Newsreader type and the shared shapes. */
@Composable internal fun DistillTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDistillExtras provides EmberExtras) {
        MaterialTheme(colorScheme = EmberDarkColors, typography = DistillTypography, shapes = DistillShapes, content = content)
    }
}
