package dev.distillpod.android.sharing

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import dev.distillpod.android.data.InfographicBrief
import dev.distillpod.android.ai.EvidenceSource
import java.io.ByteArrayOutputStream

/** AI supplies the explanatory art; deterministic typography keeps every claim and reference legible. */
object InfographicRenderer {
    fun render(brief: InfographicBrief, illustration: String, sources: List<EvidenceSource>, attribution: String): ByteArray {
        VectorIllustrations.validate(illustration)
        val bitmap = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val ink = Color.rgb(26, 48, 48)
            val teal = Color.rgb(14, 105, 101)
            val cream = Color.rgb(249, 247, 238)
            canvas.drawColor(cream)
            fun text(value: String, x: Float, y: Float, width: Int, size: Float, maxHeight: Int, bold: Boolean = false, color: Int = ink): Int {
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size; typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL) }
                fun layout() = StaticLayout.Builder.obtain(value, 0, value.length, paint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false).setLineSpacing(3f, 1f).build()
                var layout = layout()
                while (layout.height > maxHeight && paint.textSize > size * 0.88f) {
                    paint.textSize -= 1f
                    layout = layout()
                }
                require(layout.height <= maxHeight) { "The infographic text needs shortening. Try asking for a more concise version." }
                canvas.save(); canvas.translate(x, y); layout.draw(canvas); canvas.restore()
                return layout.height
            }
            text("DISTILLPOD", 60f, 44f, 960, 22f, 36, true, teal)
            val titleHeight = text(brief.title, 60f, 92f, 960, 52f, 140, true)
            text(brief.subtitle, 60f, 112f + titleHeight, 960, 28f, 110)
            // Wide text beside three bespoke scenes remains readable with evidence-rich captions.
            VectorIllustrations.draw(canvas, illustration, 60f, 350f)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = teal; strokeWidth = 4f; style = Paint.Style.STROKE }
            if (brief.relationship != "comparison") {
                for (y in listOf(586f,836f)) {
                    canvas.drawLine(180f,y,180f,y+20f,line)
                    canvas.drawLine(172f,y+12f,180f,y+20f,line); canvas.drawLine(188f,y+12f,180f,y+20f,line)
                }
            }
            if (brief.relationship == "cycle") {
                canvas.drawPath(Path().apply { moveTo(66f,1090f); lineTo(34f,1090f); lineTo(34f,365f); lineTo(56f,365f) },line)
                canvas.drawLine(48f,357f,56f,365f,line); canvas.drawLine(48f,373f,56f,365f,line)
            }
            brief.nodes.forEachIndexed { i, node ->
                val y = 365f + i * 250f
                val h = text(node.title, 350f, y, 670, 38f, 92, true)
                text(node.detail + " " + node.sources.joinToString("") { "[$it]" }, 350f, y + h + 14f, 670, 31f, 216 - h)
            }
            text(brief.caveat, 60f, 1132f, 960, 26f, 96)
            val used = brief.nodes.flatMap { it.sources }.toSet()
            val refs = sources.filter { it.id in used }.joinToString("  ·  ") { "[${it.id}] ${java.net.URI(it.url).host.removePrefix("www.").take(45)}" }
            text(refs,60f,1240f,960,21f,56)
            text(attribution.take(130),60f,1300f,960,19f,44)
            return ByteArrayOutputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray() }
        } finally { bitmap.recycle() }
    }
}
