package dev.distillpod.android.sharing

import android.graphics.*
import org.json.*

/** Small drawing vocabulary: no scripts, remote resources, fonts, embedded text or unbounded paths. */
object VectorIllustrations {
    private val palette = setOf("#F9F7EE", "#1A3030", "#0E6965", "#8DCEC2", "#E8886D", "#F3C96B", "#FFFFFF")
    fun validate(raw: String): JSONObject {
        require(raw.length <= 60000)
        val j = JSONObject(raw); val panels = j.getJSONArray("panels"); require(panels.length() == 3)
        for (i in 0..2) {
            val panel = panels.getJSONObject(i)
            require(panel.getString("meaning").length in 1..300)
            val shapes = panel.getJSONArray("shapes"); require(shapes.length() in 6..60)
            for (s in 0 until shapes.length()) {
                val shape = shapes.getJSONObject(s)
                fun number(name: String, low: Double = 0.0, high: Double = 300.0): Double = shape.getDouble(name).also { require(it.isFinite() && it in low..high) }
                when(shape.getString("kind")) {
                    "circle" -> { val x=number("x"); val y=number("y"); val r=number("r",1.0,140.0); require(x-r >= 0 && x+r <= 300 && y-r >= 0 && y+r <= 300); require(shape.getString("fill").uppercase() in palette) }
                    "rect" -> { require(number("x") + number("w",1.0) <= 300); require(number("y") + number("h",1.0) <= 300); number("radius",0.0,60.0); require(shape.getString("fill").uppercase() in palette) }
                    "polygon", "line" -> {
                        val points=shape.getJSONArray("points"); require(points.length() in (if(shape.getString("kind")=="line") 2 else 3)..24)
                        for(p in 0 until points.length()) { val point=points.getJSONArray(p); require(point.length()==2); for(n in 0..1) require(point.getDouble(n).isFinite() && point.getDouble(n) in 0.0..300.0) }
                        require(shape.getString(if(shape.getString("kind")=="line") "color" else "fill").uppercase() in palette)
                        if(shape.getString("kind")=="line") number("width",1.0,18.0)
                    }
                    else -> error("Unknown illustration shape")
                }
            }
        }
        return j
    }
    fun draw(canvas: Canvas, raw: String, x: Float, y: Float) {
        val panels=validate(raw).getJSONArray("panels")
        for(i in 0..2) {
            canvas.save(); canvas.translate(x,y+i*250f); canvas.scale(0.8f,0.8f)
            canvas.clipRect(0f,0f,300f,300f)
            val shapes=panels.getJSONObject(i).getJSONArray("shapes")
            for(s in 0 until shapes.length()) {
                val shape=shapes.getJSONObject(s)
                fun n(key:String)=shape.getDouble(key).toFloat()
                val line=shape.getString("kind")=="line"
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color=Color.parseColor(shape.getString(if(line) "color" else "fill"))
                    style=if(line) Paint.Style.STROKE else Paint.Style.FILL
                    if(line) { strokeWidth=n("width"); strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
                }
                when(shape.getString("kind")) {
                    "circle" -> canvas.drawCircle(n("x"),n("y"),n("r"),paint)
                    "rect" -> canvas.drawRoundRect(n("x"),n("y"),n("x")+n("w"),n("y")+n("h"),n("radius"),n("radius"),paint)
                    else -> {
                        val points=shape.getJSONArray("points"); val path=Path()
                        for(p in 0 until points.length()) { val point=points.getJSONArray(p); if(p==0) path.moveTo(point.getDouble(0).toFloat(),point.getDouble(1).toFloat()) else path.lineTo(point.getDouble(0).toFloat(),point.getDouble(1).toFloat()) }
                        if(!line) path.close(); canvas.drawPath(path,paint)
                    }
                }
            }
            canvas.restore()
        }
    }
}
