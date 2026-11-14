package dev.distillpod.android.features

import android.content.Context
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/** CIELAB lightness of the backdrop tone; L* is the HCT tone axis (proposal 3.3). */
private const val TINT_LIGHTNESS = 25.0
/** Chroma ceiling, so a saturated cover gives a deep colour rather than a loud one. */
private const val TINT_MAX_CHROMA = 36.0

/**
 * The player backdrop tone for a cover colour: lightness fixed at L* 25 and chroma at most 36.
 * Light text keeps its contrast on it. It is never used for text or controls.
 */
internal fun darkArtworkTone(argb: Int): Int {
    val lab = DoubleArray(3)
    val check = DoubleArray(3)
    ColorUtils.colorToLAB(argb, lab)
    val chroma = hypot(lab[1], lab[2])
    var scale = if (chroma > TINT_MAX_CHROMA) TINT_MAX_CHROMA / chroma else 1.0
    // Some hues (cyan, green) are outside sRGB at this tone; reduce chroma until the tone survives clamping.
    repeat(24) {
        val tone = ColorUtils.LABToColor(TINT_LIGHTNESS, lab[1] * scale, lab[2] * scale)
        ColorUtils.colorToLAB(tone, check)
        if (kotlin.math.abs(check[0] - TINT_LIGHTNESS) <= 1.0) return tone
        scale *= .85
    }
    return ColorUtils.LABToColor(TINT_LIGHTNESS, 0.0, 0.0)
}

/** The swatch that best represents a cover: a colourful dominant colour, else its vibrant one. */
internal fun artworkSwatch(palette: Palette): Int? {
    val dominant = palette.dominantSwatch
    val colourful = dominant?.hsl?.let { it[1] >= .2f && it[2] in .08f..0.92f } == true
    return (if (colourful) dominant else palette.vibrantSwatch ?: palette.mutedSwatch ?: dominant)?.rgb
}

/** Tones per artwork URL for this process, so returning to the player does not decode the cover again. */
internal object ArtworkTints {
    private val cache = LruCache<String, Int>(64)

    fun cached(url: String): Int? = cache.get(url)

    /** Uses Coil's loader and caches, so this normally reads the already downloaded cover. Null when it cannot load. */
    suspend fun load(context: Context, url: String): Int? {
        cache.get(url)?.let { return it }
        return try {
            val request = ImageRequest.Builder(context).data(url).size(112).allowHardware(false).build()
            val drawable = (context.imageLoader.execute(request) as? SuccessResult)?.drawable ?: return null
            withContext(Dispatchers.Default) {
                artworkSwatch(Palette.from(drawable.toBitmap()).maximumColorCount(16).generate())?.let(::darkArtworkTone)
            }?.also { cache.put(url, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

/** The current cover's dark tone, or the accent container while it loads or when there is no usable cover. */
@Composable internal fun rememberArtworkTint(url: String?): State<Color> {
    val fallback = MaterialTheme.colorScheme.primaryContainer
    val context = LocalContext.current.applicationContext
    var tone by remember(url) { mutableStateOf(url?.let(ArtworkTints::cached)) }
    LaunchedEffect(url) { if (!url.isNullOrBlank() && tone == null) tone = ArtworkTints.load(context, url) }
    return animateColorAsState(tone?.let(::Color) ?: fallback, tween(450), label = "Artwork tint")
}

/** A radial wash of [tint], 85% at the artwork and fading to nothing. Read at draw time only. */
internal fun Modifier.artworkBackdrop(tint: State<Color>, centerY: Float = .24f): Modifier = drawBehind {
    val colour = tint.value
    drawRect(Brush.radialGradient(listOf(colour.copy(alpha = .85f), colour.copy(alpha = .35f), Color.Transparent),
        center = Offset(size.width / 2, size.height * centerY), radius = maxOf(size.width, size.height * .62f)))
}
