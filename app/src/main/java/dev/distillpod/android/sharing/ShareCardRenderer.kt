package dev.distillpod.android.sharing

import android.content.Context
import android.graphics.*
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import dev.distillpod.android.R
import dev.distillpod.android.data.CardContent
import dev.distillpod.android.data.CardContent.Companion.LOOK_COVER
import dev.distillpod.android.data.CardContent.Companion.LOOK_EMBER
import dev.distillpod.android.data.CardContent.Companion.LOOK_SIGNAL
import dev.distillpod.android.data.CardContent.Companion.STYLE_IDEA
import dev.distillpod.android.data.CardContent.Companion.STYLE_QUOTE
import dev.distillpod.android.data.CardContent.Companion.STYLE_TAKEAWAYS
import dev.distillpod.android.data.CardSize
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Paints a [CardContent] as a share card with the bundled fonts. It is local and deterministic, so the
 * in-app preview and every export are the same picture. See "Card design" in docs/android/social-share-cards.md;
 * below, u is 1% of the canvas width.
 */
object ShareCardRenderer {
    /** Caller owns and recycles the returned bitmap. [cover] is the square podcast thumbnail, or null. */
    fun render(context: Context, card: CardContent, show: String, episode: String, cover: Bitmap?,
        size: CardSize = CardSize.Feed, style: String = card.style, look: String = card.look): Bitmap {
        require(style in CardContent.STYLES) { "Unknown card style: $style." }
        require(look in CardContent.LOOKS) { "Unknown card look: $look." }
        require(style != STYLE_TAKEAWAYS || card.takeaways.size == 3) { "The Takeaways card needs exactly three items." }
        val faces = faces(context)
        val drawn = if (look == LOOK_COVER && (card.tint == 0 || cover == null)) LOOK_EMBER else look
        val thumbnail = cover?.let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        try {
            Card(faces, palette(drawn, card.tint), card, show, episode, thumbnail, size, style).draw(Canvas(bitmap))
        } catch (e: Throwable) {
            bitmap.recycle()
            throw e
        } finally {
            if (thumbnail !== cover) thumbnail?.recycle()
        }
        return bitmap
    }

    fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "The card image could not be encoded." }
        it.toByteArray()
    }

    /** Large text steps down to this share of its nominal size before the card is refused. */
    private const val FLOOR = .6f
    private const val STEPS = 20
    /**
     * Below [FLOOR] only for a block whose single word is wider than the column, so contract text still draws.
     * Measured against the Feed size: a Story block never ends up smaller than the same block on a Feed card.
     */
    private const val WORD_FLOOR = .4f
    /** A quote of at most [GROW_LINES] lines may grow this many steps (2% each) above its nominal size. */
    private const val GROW_STEPS = 12
    private const val GROW_LINES = 3
    private const val MIN_TEXT_PX = 28f
    /** Where a block that does not fill its space sits: 0 top, 1 bottom. Slightly above centre reads as centred. */
    private const val PLACEMENT = .4f
    private const val AMBER = 0xFFFFB547.toInt()
    private const val WORDMARK = "DISTILLPOD"

    private class Faces(val bold: Typeface, val semibold: Typeface, val regular: Typeface, val serif: Typeface, val serifItalic: Typeface)

    @Volatile private var cachedFaces: Faces? = null

    private fun faces(context: Context): Faces = cachedFaces ?: synchronized(this) {
        cachedFaces ?: Faces(font(context, R.font.inter_bold), font(context, R.font.inter_semibold), font(context, R.font.inter_regular),
            font(context, R.font.newsreader_regular), font(context, R.font.newsreader_italic)).also { cachedFaces = it }
    }

    // ResourcesCompat throws when a resource is missing; the null check covers a provider that answers with nothing.
    private fun font(context: Context, @FontRes id: Int): Typeface = ResourcesCompat.getFont(context, id)
        ?: throw IllegalStateException("Bundled font ${context.resources.getResourceEntryName(id)} could not be loaded.")

    private class Palette(val look: String, val text: Int, val accent: Int, val paint: (Canvas, Float, Float) -> Unit)

    private fun palette(look: String, tint: Int): Palette = when (look) {
        LOOK_EMBER -> Palette(look, 0xFFF2EEE8.toInt(), AMBER) { canvas, w, h ->
            val shader = RadialGradient(0f, 0f, 1000f, intArrayOf(0xFF3B2A12.toInt(), 0xFF191816.toInt(), 0xFF0F0E0D.toInt()),
                floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
            // The CSS ellipse is 120% x 80% of the card, centred on the top-right corner.
            shader.setLocalMatrix(Matrix().apply { setScale(1.2f * w / 1000f, .8f * h / 1000f); postTranslate(w, 0f) })
            canvas.drawRect(0f, 0f, w, h, Paint().apply { this.shader = shader; isDither = true })
        }
        LOOK_SIGNAL -> Palette(look, 0xFF1A1206.toInt(), 0xFF1A1206.toInt()) { canvas, _, _ -> canvas.drawColor(AMBER) }
        else -> {
            val lab = DoubleArray(3).also { ColorUtils.colorToLAB(tint, it) }
            val chroma = hypot(lab[1], lab[2])
            // A grey cover has no hue to borrow, so its accent falls back to the app's amber hue.
            val hue = if (chroma < 8) AMBER else tint
            // A faintly coloured cover would give a muddy grey-green; it gets a little more of its own hue.
            val backdrop = if (chroma < 8) chroma else chroma.coerceIn(16.0, 36.0)
            val deep = tone(tint, 20.0, backdrop)
            val middle = tone(tint, 26.0, backdrop)
            val light = tone(tint, 32.0, backdrop)
            // Clearly coloured next to the off-white text, so a highlight stands out, but never neon.
            val accentChroma = chroma.coerceIn(42.0, 52.0)
            var lightness = 76.0
            var accent = tone(hue, lightness, accentChroma)
            // Stops well short of the text's lightness, where the accent would fade into it.
            while (ColorUtils.calculateContrast(accent, light) < 4.5 && lightness < 88) {
                lightness += 2
                accent = tone(hue, lightness, accentChroma)
            }
            Palette(look, 0xFFF4F1EC.toInt(), accent) { canvas, w, h ->
                // CSS linear-gradient(165deg): mostly downwards, drifting right.
                val angle = Math.toRadians(165.0)
                val dx = sin(angle).toFloat(); val dy = -cos(angle).toFloat()
                val half = (abs(w * dx) + abs(h * dy)) / 2
                val shader = LinearGradient(w / 2 - dx * half, h / 2 - dy * half, w / 2 + dx * half, h / 2 + dy * half,
                    intArrayOf(deep, middle, light), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, Paint().apply { this.shader = shader; isDither = true })
            }
        }
    }

    /** [argb]'s hue at CIELAB [lightness], chroma at most [chroma], reduced until it survives sRGB clamping. */
    private fun tone(argb: Int, lightness: Double, chroma: Double): Int {
        val lab = DoubleArray(3).also { ColorUtils.colorToLAB(argb, it) }
        val current = hypot(lab[1], lab[2])
        if (current < 1e-6) return ColorUtils.LABToColor(lightness, 0.0, 0.0)
        val a = lab[1] / current; val b = lab[2] / current
        val check = DoubleArray(3)
        var c = chroma
        repeat(30) {
            val tone = ColorUtils.LABToColor(lightness, a * c, b * c)
            ColorUtils.colorToLAB(tone, check)
            if (abs(check[0] - lightness) <= 1.0 && hypot(check[1] - a * c, check[2] - b * c) <= 2.5) return tone
            c *= .9
        }
        return ColorUtils.LABToColor(lightness, 0.0, 0.0)
    }

    private fun alpha(color: Int, opacity: Float) = ColorUtils.setAlphaComponent(color, (opacity * 255).toInt())

    /** Single-spaced, with typographic apostrophes; the wording itself is never changed. */
    private fun clean(text: String) = text.trim().replace(Regex("\\s+"), " ").replace(Regex("(?<=\\p{L})'(?=\\p{L})"), "’")

    /**
     * A quote that starts mid-sentence ("…some CFOs play") opens with a capital, as quotation style
     * allows; the stored words stay exact. [emphasis] follows when it starts the quote.
     */
    private fun opening(text: String, emphasis: String): Pair<String, String> {
        val index = text.indexOfFirst(Char::isLetter)
        if (index < 0 || !text[index].isLowerCase()) return text to emphasis
        fun capital(s: String, at: Int) = s.substring(0, at) + s.substring(at, at + 1).uppercase(Locale.ROOT) + s.substring(at + 1)
        val upper = capital(text, index)
        val start = if (emphasis.isEmpty()) -1 else text.indexOf(emphasis)
        return upper to if (start in 0..index && start + emphasis.length > index) capital(emphasis, index - start) else emphasis
    }

    private class Ink(face: Typeface, val size: Float, color: Int, val tracking: Float = 0f, val box: Int = 0) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = face; textSize = size; this.color = color; letterSpacing = tracking
        }
        val ascent = -paint.fontMetrics.ascent
        val descent = paint.fontMetrics.descent

        /** Baseline below the top of a line box of [leading] times the size, centred as CSS does. */
        fun baseline(leading: Float) = (size * leading - ascent - descent) / 2 + ascent

        fun bounds(text: String) = Rect().also { paint.getTextBounds(text, 0, text.length, it) }
    }

    /**
     * Left-aligned text with one optional emphasised range. Words never break inside; a word that is
     * wider than the column makes [wrap] fail so the caller steps the size down.
     */
    private class Paragraph(text: String, emphasis: String, private val base: Ink, emphasized: Ink, leading: Float, private val pad: Float = 0f) {
        private class Run(val text: String, val ink: Ink) { val width = ink.paint.measureText(text) }

        private val words: List<List<Run>>
        /** Whether each word holds emphasised text. */
        private val marked: List<Boolean>
        private val space = base.paint.measureText(" ")
        private val boxTop: Float
        private val boxBottom: Float
        private val boxPaint = Paint().apply { color = emphasized.box }
        val lineHeight = base.size * leading
        private val baseline = base.baseline(leading)
        var lines: List<List<List<Run>>> = emptyList(); private set
        val height get() = lines.size * lineHeight
        val firstBaseline get() = baseline
        /** The widest single word, which no line break can help. */
        val widest get() = words.maxOfOrNull { width(listOf(it)) } ?: 0f
        /** Top of a capital relative to the baseline (negative), for aligning other marks with the first line. */
        val capTop get() = base.bounds("H").top

        fun measure(text: String) = base.paint.measureText(text)

        val size get() = base.size
        /** Whether the emphasised phrase runs over more than one line. */
        val splitsEmphasis get() = lines.count { line -> line.any { word -> word.any { it.ink !== base } } } > 1
        /** How far the first line starts left of the column, so an opening quotation mark hangs outside it. */
        var hang = 0f

        init {
            val start = if (emphasis.isBlank()) -1 else text.indexOf(emphasis)
            var end = start + emphasis.length
            // A box padded away from its closing punctuation leaves the comma stranded, so the box takes it in.
            if (start >= 0 && emphasized.box != 0) while (end < text.length && text[end] in ".,;:!?…)”’»") end++
            val range = if (start < 0) IntRange.EMPTY else start until end
            val result = mutableListOf<List<Run>>()
            var word = mutableListOf<Run>()
            val run = StringBuilder()
            var inEmphasis = false
            fun endRun() { if (run.isNotEmpty()) { word += Run(run.toString(), if (inEmphasis) emphasized else base); run.clear() } }
            fun endWord() { endRun(); if (word.isNotEmpty()) { result += word; word = mutableListOf() } }
            text.forEachIndexed { index, c ->
                if (c.isWhitespace()) endWord() else {
                    val inside = index in range
                    if (inside != inEmphasis) { endRun(); inEmphasis = inside }
                    run.append(c)
                }
            }
            endWord()
            words = result
            marked = result.map { word -> word.any { it.ink !== base } }
            // The box covers ascenders and descenders, and grows only for marks that reach further (such as É).
            val ink = if (emphasized.box != 0 && !range.isEmpty()) emphasized.bounds(text.substring(range)) else Rect()
            boxTop = min(-.8f * base.size, ink.top - .05f * base.size)
            boxBottom = max(.22f * base.size, ink.bottom + .05f * base.size)
        }

        /**
         * Places runs on one line. A box gets [pad] of room inside the line but hangs into the margin at
         * either end, so its text stays aligned with the column. Returns the inked width.
         */
        private inline fun walk(line: List<List<Run>>, text: (Run, Float) -> Unit, box: (Float, Float) -> Unit): Float {
            var x = 0f; var end = 0f; var boxed = false; var boxStart = 0f; var first = true
            line.forEachIndexed { index, word ->
                if (index > 0) x = end + space
                word.forEach { run ->
                    val isBoxed = run.ink.box != 0
                    if (isBoxed != boxed) {
                        if (isBoxed) { if (!first) x += pad; boxStart = x - pad } else { box(boxStart, end + pad); x += pad }
                        boxed = isBoxed
                    }
                    text(run, x)
                    x += run.width; end = x; first = false
                }
            }
            if (boxed) box(boxStart, end + pad)
            return end
        }

        private fun width(line: List<List<Run>>) = walk(line, { _, _ -> }, { _, _ -> })

        private fun breakLines(width: Float): List<List<List<Run>>>? {
            if (words.any { width(listOf(it)) > width }) return null
            // An emphasised phrase that fits on one line is kept together; a split phrase loses its punch.
            val units = mutableListOf<List<List<Run>>>()
            var index = 0
            while (index < words.size) {
                var end = index + 1
                if (marked[index]) while (end < words.size && marked[end]) end++
                val group = words.subList(index, end)
                if (group.size > 1 && width(group) > width) group.forEach { units += listOf(it) } else units += group
                index = end
            }
            val result = mutableListOf<List<List<Run>>>()
            var line = mutableListOf<List<Run>>()
            for (unit in units) {
                if (line.isNotEmpty() && width(line + unit) > width) { result += line; line = mutableListOf() }
                line += unit
            }
            if (line.isNotEmpty()) result += line
            return result
        }

        /**
         * Breaks into lines no wider than [width]. [balance] evens out the lines, as CSS text-wrap: balance
         * does for headlines; otherwise only a lone last word is avoided. False when a word cannot fit.
         */
        fun wrap(width: Float, balance: Boolean): Boolean {
            val greedy = breakLines(width) ?: return false
            lines = greedy
            if (greedy.size < 2) return true
            if (balance) {
                var low = width * .5f; var high = width
                repeat(14) {
                    val middle = (low + high) / 2
                    val candidate = breakLines(middle)
                    if (candidate != null && candidate.size == greedy.size) { lines = candidate; high = middle } else low = middle
                }
            } else if (greedy.last().size == 1) {
                var candidateWidth = width
                while (candidateWidth > width * .55f) {
                    candidateWidth -= width * .01f
                    val candidate = breakLines(candidateWidth) ?: break
                    if (candidate.size != greedy.size) break
                    if (candidate.last().size > 1) { lines = candidate; break }
                }
            }
            return true
        }

        fun draw(canvas: Canvas, left: Float, top: Float) {
            lines.forEachIndexed { index, line ->
                val y = top + index * lineHeight + baseline
                val start = if (index == 0) left - hang else left
                walk(line, { _, _ -> }) { l, r -> canvas.drawRect(start + l, y + boxTop, start + r, y + boxBottom, boxPaint) }
                walk(line, { run, x -> canvas.drawText(run.text, start + x, y, run.ink.paint) }, { _, _ -> })
            }
        }
    }

    /** One element of a vertical stack. */
    private class Part(val height: Float, val draw: (Canvas, Float) -> Unit)

    private class Card(val f: Faces, val p: Palette, val card: CardContent, show: String, episode: String, val cover: Bitmap?,
        size: CardSize, val style: String) {
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val u = w / 100f
        val story = size == CardSize.Story
        /**
         * Story content type is about a fifth larger; the extra height takes it. The source header and
         * footer keep their feed size, since a Story is seen at the same width and their names need the room.
         */
        val k = if (story) 1.2f else 1f
        val padX = (if (story) 10f else 8.5f) * u
        val top = (if (story) 20f else 8.5f) * u
        val bottom = h - (if (story) 22f else 8.5f) * u
        val inner = w - 2 * padX
        val show = clean(show)
        val episode = clean(episode)

        fun px(units: Float, scale: Float = 1f) = max(units * u * k * scale, MIN_TEXT_PX)

        /** Header and footer sizes, which do not grow on a Story. */
        fun chrome(units: Float) = max(units * u, MIN_TEXT_PX)

        fun draw(canvas: Canvas) {
            p.paint(canvas, w, h)
            val sourceOnTop = style == STYLE_QUOTE && p.look == LOOK_COVER
            val footerTop = if (sourceOnTop) ruleFooter(canvas) else footer(canvas)
            val regionTop = if (sourceOnTop) header(canvas) + 7 * u * k else top
            val regionBottom = footerTop - 6 * u * k
            val available = regionBottom - regionTop
            // A short quote may grow so the card does not look empty; everything else starts at its nominal size.
            val first = if (style == STYLE_QUOTE) -GROW_STEPS else 0
            val parts = (first..STEPS).asSequence().map { 1f - it * (1f - FLOOR) / STEPS }.mapNotNull(::compose)
                .firstOrNull { parts -> parts.sumOf { it.height.toDouble() } <= available }
                ?: throw IllegalArgumentException("The card text needs shortening.")
            var y = regionTop + (available - parts.sumOf { it.height.toDouble() }.toFloat()) * PLACEMENT
            for (part in parts) { part.draw(canvas, y); y += part.height }
        }

        /** The main block at scale [s], or null when a word is too wide at that size. */
        fun compose(s: Float): List<Part>? {
            // Supporting text shrinks half as fast as the large type so the hierarchy holds.
            val t = 1f - (1f - s) / 2
            val floor = s <= FLOOR + 1e-4f
            val parts = mutableListOf<Part>()
            fun gap(units: Float, scale: Float) { parts += Part(units * u * k * scale) { _, _ -> } }
            when (style) {
                STYLE_QUOTE -> {
                    val (text, emphasis) = opening(clean(card.quote), clean(card.highlight))
                    fun quote(q: Float): Paragraph = when (p.look) {
                        LOOK_EMBER -> Paragraph(text, emphasis, Ink(f.serif, px(8.3f, q), p.text, -.01f), Ink(f.serifItalic, px(8.3f, q), AMBER, -.01f), 1.16f)
                        LOOK_COVER -> Paragraph(text, emphasis, Ink(f.bold, px(8f, q), p.text, -.03f), Ink(f.bold, px(8f, q), p.accent, -.03f), 1.1f)
                        else -> Paragraph(text, emphasis, Ink(f.bold, px(9.4f, q), p.text, -.035f),
                            Ink(f.bold, px(9.4f, q), AMBER, -.035f, box = p.text), 1.04f, pad = 1.6f * u * k * q)
                    }
                    var paragraph = wrapped(s, inner, balance = false, floor, ::quote) ?: return null
                    if (s > 1f && paragraph.lines.size > GROW_LINES) return null
                    // A highlight split over two lines loses its punch; a slightly smaller size that keeps it whole wins.
                    if (paragraph.splitsEmphasis) for (smaller in listOf(.93f, .86f).map { max(s * it, FLOOR) }.distinct()) {
                        val candidate = quote(smaller).takeIf { it.wrap(inner, balance = false) } ?: continue
                        if (!candidate.splitsEmphasis) { paragraph = candidate; break }
                    }
                    when (p.look) {
                        LOOK_EMBER -> { parts += glyph(px(30f, s)); gap(4f, s) }
                        LOOK_SIGNAL -> kicker(parts, 6f, s)
                    }
                    parts += text(paragraph, padX)
                }
                STYLE_IDEA -> {
                    kicker(parts, 5f, s)
                    val headline = display(card.headline, 11.5f, s, -.04f, 1f, floor) ?: return null
                    parts += text(headline, padX)
                    val pull = opening(clean(card.pull), "").first
                    if (pull.isNotEmpty()) {
                        gap(6f, s)
                        val quoted = if (pull.first() in "“\"'‘«") pull else "“$pull”"
                        val bar = .7f * u * k
                        val indent = bar + 4f * u * k
                        // A headline shrunk for one long word must still lead its pull quote.
                        val pullScale = min(t, headline.size * .6f / (4.5f * u * k))
                        val paragraph = wrapped(pullScale, inner - indent, balance = false, floor) { q ->
                            plain(quoted, Ink(f.serifItalic, px(4.5f, q), alpha(p.text, .9f)), 1.3f)
                        } ?: return null
                        if (quoted.first() == '“') paragraph.hang = paragraph.measure("“")
                        parts += Part(paragraph.height) { canvas, y ->
                            canvas.drawRect(padX, y, padX + bar, y + paragraph.height, Paint().apply { color = p.accent })
                            paragraph.draw(canvas, padX + indent, y)
                        }
                    }
                }
                STYLE_TAKEAWAYS -> {
                    kicker(parts, 3f, s)
                    val title = display(card.takeawaysTitle, 8.6f, s, -.035f, 1.04f, floor) ?: return null
                    parts += text(title, padX)
                    // Rows stay clearly below a title that had to shrink for one long word.
                    val rows = min(t, title.size * .8f / (5f * u * k))
                    card.takeaways.forEachIndexed { index, item ->
                        gap(if (index == 0) 7f else 5.2f, if (index == 0) s else rows)
                        parts += takeaway(index + 1, item.title, item.detail, rows, floor) ?: return null
                    }
                }
            }
            return parts
        }

        fun plain(text: String, ink: Ink, leading: Float) = Paragraph(clean(text), "", ink, ink, leading)

        /**
         * A balanced display line at [units] and scale [s]. It takes up to 15% less size when that saves a
         * line, so a word that only just misses does not sit alone ("More / feedback / ...").
         */
        fun display(text: String, units: Float, s: Float, tracking: Float, leading: Float, floor: Boolean): Paragraph? {
            fun build(q: Float) = plain(text, Ink(f.bold, px(units, q), p.text, tracking), leading)
            val full = build(s)
            if (!full.wrap(inner, balance = true)) return wrapped(s, inner, balance = true, floor, ::build)
            val smaller = max(s * .85f, FLOOR)
            if (smaller >= s) return full
            val tight = build(smaller)
            return if (tight.wrap(inner, balance = true) && tight.lines.size < full.lines.size) tight else full
        }

        /**
         * [build] at scale [q], wrapped into [column]. Once the card is at its floor, a single word wider
         * than the column shrinks only this block further, down to [WORD_FLOOR], so contract-valid content
         * always draws. Null when that is not enough.
         */
        fun wrapped(q: Float, column: Float, balance: Boolean, floor: Boolean, build: (Float) -> Paragraph): Paragraph? {
            val paragraph = build(q)
            if (paragraph.wrap(column, balance)) return paragraph
            if (!floor) return null
            // Width is close to linear in size; the loop absorbs rounding and the 28 px clamp.
            var smaller = min(q, q * column / paragraph.widest) * .995f
            while (smaller >= WORD_FLOOR / k) {
                val candidate = build(smaller)
                if (candidate.wrap(column, balance)) return candidate
                smaller *= .98f
            }
            return null
        }

        fun text(paragraph: Paragraph, left: Float) = Part(paragraph.height) { canvas, y -> paragraph.draw(canvas, left, y) }

        /** The Ember opening quotation mark, its ink aligned with the text edge. */
        fun glyph(size: Float): Part {
            val ink = Ink(f.serif, size, AMBER)
            val bounds = ink.bounds("“")
            return Part(bounds.height().toFloat()) { canvas, y -> canvas.drawText("“", padX - bounds.left, y - bounds.top, ink.paint) }
        }

        /**
         * The topic in capitals; accent on Ember and Cover, heavier ink on Signal where the accent is the
         * background. A blank topic draws nothing, and the block re-centres without it.
         */
        fun kicker(parts: MutableList<Part>, after: Float, s: Float) {
            val signal = p.look == LOOK_SIGNAL
            val ink = Ink(if (signal) f.bold else f.semibold, px(3f), if (signal) p.text else p.accent, .14f)
            val label = ellipsize(clean(card.topic).uppercase(Locale.ROOT), ink, inner)
            if (label.isEmpty()) return
            val leading = 1.25f
            parts += Part(ink.size * leading) { canvas, y -> canvas.drawText(label, padX - ink.tracking * ink.size / 2, y + ink.baseline(leading), ink.paint) }
            parts += Part(after * u * k * s) { _, _ -> }
        }

        fun takeaway(number: Int, title: String, detail: String, t: Float, floor: Boolean): Part? {
            val column = 11f * u * k * t
            val numeral = Ink(f.serif, px(11f, t), p.accent)
            val heading = wrapped(t, inner - column, balance = false, floor) { q -> plain(title, Ink(f.bold, px(5f, q), p.text, -.015f), 1.15f) }
                ?: return null
            val body = wrapped(t, inner - column, balance = false, floor) { q -> plain(detail, Ink(f.regular, px(3.6f, q), alpha(p.text, .75f)), 1.3f) }
                ?: return null
            val between = 1f * u * k * t
            val digit = numeral.bounds("$number")
            // The numeral's top lines up with the title's capitals.
            val numeralBaseline = heading.firstBaseline + heading.capTop - digit.top
            val height = max(heading.height + between + body.height, numeralBaseline + digit.bottom)
            return Part(height) { canvas, y ->
                canvas.drawText("$number", padX - digit.left, y + numeralBaseline, numeral.paint)
                heading.draw(canvas, padX + column, y)
                body.draw(canvas, padX + column, y + heading.height + between)
            }
        }

        /** Thumbnail, show and episode, and the wordmark, pinned to the bottom. Returns its top. */
        fun footer(canvas: Canvas): Float {
            val side = 13f * u
            val gap = 3.2f * u
            val mark = Ink(f.bold, chrome(2.6f), alpha(p.text, .55f), .14f)
            val markWidth = mark.paint.measureText(WORDMARK)
            val textLeft = padX + if (cover != null) side + gap else 0f
            val source = Source(textLeft, padX + inner - markWidth - gap - textLeft)
            val height = max(if (cover != null) side else 0f, source.height)
            val footerTop = bottom - height
            if (cover != null) drawCover(canvas, padX, footerTop + (height - side) / 2, side, shadow = false)
            source.draw(canvas, footerTop + (height - source.height) / 2)
            val markLine = mark.size * 1.3f
            canvas.drawText(WORDMARK, padX + inner - markWidth + mark.tracking * mark.size / 2,
                footerTop + (height - markLine) / 2 + mark.baseline(1.3f), mark.paint)
            return footerTop
        }

        /** Cover look, Quote style: the source is at the top, so the footer is a hairline and the wordmark. */
        fun ruleFooter(canvas: Canvas): Float {
            val mark = Ink(f.bold, chrome(2.6f), alpha(p.text, .55f), .14f)
            val markLine = mark.size * 1.3f
            val rule = 2f
            val above = 4f * u
            val footerTop = bottom - markLine - above - rule
            canvas.drawRect(padX, footerTop, padX + inner, footerTop + rule, Paint().apply { color = alpha(p.text, .25f) })
            canvas.drawText(WORDMARK, padX + inner - mark.paint.measureText(WORDMARK) + mark.tracking * mark.size / 2,
                footerTop + rule + above + mark.baseline(1.3f), mark.paint)
            return footerTop
        }

        /** Cover look, Quote style: large thumbnail with show and episode beside it. Returns its bottom. */
        fun header(canvas: Canvas): Float {
            val side = 17f * u
            val gap = 3.2f * u
            drawCover(canvas, padX, top, side, shadow = true)
            val source = Source(padX + side + gap, inner - side - gap)
            source.draw(canvas, top + (side - source.height) / 2)
            return top + side
        }

        /** Show over episode, each one ellipsized line. */
        inner class Source(val left: Float, width: Float) {
            val showInk = Ink(f.bold, chrome(3.5f), p.text)
            val episodeInk = Ink(f.regular, chrome(3.1f), alpha(p.text, .72f))
            val showText = ellipsize(show, showInk, width)
            val episodeText = ellipsize(episode, episodeInk, width)
            val showLine = if (showText.isEmpty()) 0f else showInk.size * 1.25f
            val episodeLine = if (episodeText.isEmpty()) 0f else episodeInk.size * 1.3f
            val height = showLine + episodeLine

            fun draw(canvas: Canvas, y: Float) {
                if (showText.isNotEmpty()) canvas.drawText(showText, left, y + showInk.baseline(1.25f), showInk.paint)
                if (episodeText.isNotEmpty()) canvas.drawText(episodeText, left, y + showLine + episodeInk.baseline(1.3f), episodeInk.paint)
            }
        }

        fun drawCover(canvas: Canvas, left: Float, top: Float, side: Float, shadow: Boolean) {
            val image = cover ?: return
            val radius = 2f * u
            val rect = RectF(left, top, left + side, top + side)
            if (shadow) {
                val blur = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(Color.BLACK, .35f); maskFilter = BlurMaskFilter(3f * u, BlurMaskFilter.Blur.NORMAL) }
                canvas.drawRoundRect(RectF(rect).apply { offset(0f, 2f * u) }, radius, radius, blur)
            }
            val crop = min(image.width, image.height)
            val square = Bitmap.createBitmap(image, (image.width - crop) / 2, (image.height - crop) / 2, crop, crop)
            val scaled = Bitmap.createScaledBitmap(square, side.toInt(), side.toInt(), true)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setScale(side / scaled.width, side / scaled.height); postTranslate(left, top) })
                }
                if (p.look == LOOK_SIGNAL) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                    setSaturation(0f)
                    postConcat(ColorMatrix(floatArrayOf(1.1f, 0f, 0f, 0f, -12.8f, 0f, 1.1f, 0f, 0f, -12.8f, 0f, 0f, 1.1f, 0f, -12.8f, 0f, 0f, 0f, 1f, 0f)))
                })
            }
            canvas.drawRoundRect(rect, radius, radius, paint)
            if (scaled !== square && scaled !== image) scaled.recycle()
            if (square !== image) square.recycle()
        }

        fun ellipsize(text: String, ink: Ink, width: Float): String {
            if (width <= 0f || text.isEmpty()) return ""
            if (ink.paint.measureText(text) <= width) return text
            var low = 0; var high = text.length
            while (low < high) {
                val middle = (low + high + 1) / 2
                if (ink.paint.measureText(text.take(middle).trimEnd() + "…") <= width) low = middle else high = middle - 1
            }
            var end = low
            if (end > 0 && text[end - 1].isHighSurrogate()) end--
            return if (end == 0) "" else text.take(end).trimEnd() + "…"
        }
    }
}
