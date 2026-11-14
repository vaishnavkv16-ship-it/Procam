package com.procam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Simple line icons drawn in code (Google-Camera look, no image assets). */
class IconView(c: Context) : View(c) {
    companion object { const val GEAR = 0; const val BOLT = 1; const val BOLT_OFF = 2; const val TIMER = 3; const val HDR = 4; const val FLIP = 5 }

    var type: Int = GEAR
        set(v) { field = v; invalidate() }
    var on: Boolean = false
        set(v) { field = v; invalidate() }
    var label: String? = null
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE; textAlign = Paint.Align.CENTER }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = min(w, h)
        val cx = w / 2
        val cy = h / 2
        p.strokeWidth = s * 0.07f
        when (type) {
            GEAR -> {
                c.drawCircle(cx, cy, s * 0.24f, p)
                c.drawCircle(cx, cy, s * 0.09f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c1 = cos(a).toFloat()
                    val s1 = sin(a).toFloat()
                    c.drawLine(cx + c1 * s * 0.24f, cy + s1 * s * 0.24f, cx + c1 * s * 0.35f, cy + s1 * s * 0.35f, p)
                }
            }
            BOLT, BOLT_OFF -> {
                val path = Path()
                path.moveTo(w * 0.58f, h * 0.14f)
                path.lineTo(w * 0.32f, h * 0.54f)
                path.lineTo(w * 0.49f, h * 0.54f)
                path.lineTo(w * 0.42f, h * 0.86f)
                path.lineTo(w * 0.68f, h * 0.44f)
                path.lineTo(w * 0.51f, h * 0.44f)
                path.close()
                if (on) c.drawPath(path, f) else c.drawPath(path, p)
                if (type == BOLT_OFF) c.drawLine(w * 0.22f, h * 0.18f, w * 0.78f, h * 0.82f, p)
                label?.let { f.textSize = s * 0.26f; c.drawText(it, w * 0.78f, h * 0.88f, f) }
            }
            TIMER -> {
                c.drawCircle(cx, cy + s * 0.05f, s * 0.28f, p)
                c.drawLine(cx - s * 0.08f, cy - s * 0.34f, cx + s * 0.08f, cy - s * 0.34f, p)
                val lb = label
                if (lb != null) {
                    f.textSize = s * 0.26f
                    c.drawText(lb, cx, cy + s * 0.05f + s * 0.09f, f)
                } else {
                    c.drawLine(cx, cy + s * 0.05f, cx, cy - s * 0.12f, p)
                }
            }
            HDR -> {
                val r = RectF(w * 0.12f, h * 0.30f, w * 0.88f, h * 0.70f)
                if (on) { f.style = Paint.Style.FILL; c.drawRoundRect(r, s * 0.1f, s * 0.1f, f) } else c.drawRoundRect(r, s * 0.1f, s * 0.1f, p)
                f.textSize = s * 0.2f
                val old = f.color
                f.color = if (on) Color.BLACK else Color.WHITE
                c.drawText("HDR", cx, cy + s * 0.07f, f)
                f.color = old
            }
            FLIP -> {
                val r = s * 0.27f
                val rect = RectF(cx - r, cy - r, cx + r, cy + r)
                for (start in listOf(200f, 20f)) {
                    c.drawArc(rect, start, 130f, false, p)
                    val a = Math.toRadians((start + 130f).toDouble())
                    val ex = (cx + r * cos(a)).toFloat()
                    val ey = (cy + r * sin(a)).toFloat()
                    val base = atan2(cos(a), -sin(a)) + Math.PI
                    for (sg in listOf(-1, 1)) {
                        val ang = base + sg * 0.6
                        c.drawLine(ex, ey, ex + (cos(ang) * s * 0.15).toFloat(), ey + (sin(ang) * s * 0.15).toFloat(), p)
                    }
                }
            }
        }
    }
}
