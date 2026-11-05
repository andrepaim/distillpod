package dev.distillpod.android.sharing

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import dev.distillpod.android.data.PostContent

/** One fixed-size layout powers preview and export. Text never silently clips. */
object PostCardRenderer {
    fun render(post: PostContent): Bitmap {
        post.validated()
        val height = if (post.square) 1080 else 1350
        val background = when (post.theme) { "Mint" -> 0xFF91DCC8; "Lavender" -> 0xFFB7B9FF; else -> 0xFF0C0F16 }.toInt()
        val foreground = if (post.theme == "Ink") 0xFFF1F2F6.toInt() else 0xFF17182E.toInt()
        val blocks = mutableListOf<Pair<StaticLayout, Float>>()
        var y = 82f
        fun block(text: String, size: Float, typeface: Typeface, gap: Float = 32f) {
            if (text.isBlank()) return
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = foreground; textSize = size; this.typeface = typeface }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 916).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false).setLineSpacing(6f, 1f).build()
            blocks += layout to y
            y += layout.height + gap
        }
        block(if (post.language == "pt-BR") "UMA IDEIA PARA COMPARTILHAR" else "A MOMENT WORTH SHARING", 23f, Typeface.create("sans-serif-medium", Typeface.NORMAL), 44f)
        block(post.headline, 58f, Typeface.create("sans-serif", Typeface.BOLD))
        val primary = if (post.layout == "Quote") { require(post.quote.isNotBlank()) { "Select a source quote or choose Idea layout" }; "“${post.quote}”" } else post.takeaway
        require(primary.isNotBlank()) { "Add a takeaway or a source quote to preview the card" }
        block(primary, 46f, Typeface.create(if (post.layout == "Quote") "serif" else "sans-serif", Typeface.NORMAL))
        val contentBottom = y
        val sourceStart = maxOf(y + 28f, height - 245f)
        val contentOffset = maxOf(0f, (sourceStart - 80f - contentBottom) / 2f)
        for (index in 1 until blocks.size) blocks[index] = blocks[index].let { it.first to it.second + contentOffset }
        y = sourceStart
        block(post.show, 28f, Typeface.create("sans-serif", Typeface.BOLD), 10f)
        block("${post.episode}\n${dev.distillpod.core.postTimestamp(post.startMs)}–${dev.distillpod.core.postTimestamp(post.endMs)}", 25f, Typeface.DEFAULT, 16f)
        if (post.branding) block("Made with DistillPod", 21f, Typeface.DEFAULT, 0f)
        require(y <= height - 60 && contentBottom < sourceStart) { "Text does not fit this card. Shorten the headline or content, or use portrait size." }
        return Bitmap.createBitmap(1080, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap); canvas.drawColor(background)
            val line = Paint().apply { color = foreground; alpha = 75; strokeWidth = 2f }
            canvas.drawLine(82f, sourceStart - 22f, 998f, sourceStart - 22f, line)
            for ((layout, top) in blocks) { canvas.save(); canvas.translate(82f, top); layout.draw(canvas); canvas.restore() }
        }
    }
}
