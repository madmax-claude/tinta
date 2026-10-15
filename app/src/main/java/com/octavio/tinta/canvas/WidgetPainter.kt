package com.octavio.tinta.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.octavio.tinta.model.Appearance
import com.octavio.tinta.model.Expr
import com.octavio.tinta.model.Graph2D
import com.octavio.tinta.model.Graph3D
import com.octavio.tinta.model.Media
import com.octavio.tinta.model.MediaKind
import com.octavio.tinta.model.Solid
import com.octavio.tinta.model.Solids
import com.octavio.tinta.model.Surface
import com.octavio.tinta.model.Tool
import com.octavio.tinta.model.View3D
import com.octavio.tinta.model.Widget
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Dibuja los widgets en coordenadas del mundo: gráficos, figuras 3D y las tarjetas de los audios y
 * videos. Igual en pantalla, en las vistas previas y en el PDF; cada hilo tiene su instancia.
 */
class WidgetPainter {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val parsed = HashMap<String, Expr?>()

    /** `thumb`: el cuadro de un video, si ya se decodificó. */
    fun draw(canvas: Canvas, w: Widget, dark: Boolean, thumb: (File) -> Bitmap? = { null }) {
        when (w) {
            is Graph2D -> graph2d(canvas, w, dark)
            is Graph3D -> graph3d(canvas, w, dark)
            is Solid -> solid(canvas, w, dark)
            is Media -> media(canvas, w, dark, w.thumb?.let(thumb))
        }
    }

    private fun expr(text: String): Expr? = parsed.getOrPut(text) { runCatching { Expr.parse(text) }.getOrNull() }

    private fun ink(color: Int, dark: Boolean) = Appearance.ink(color, Tool.PEN, dark)

    private fun axisColor(dark: Boolean) = if (dark) 0xFFC8CDD6.toInt() else 0xFF3A3F47.toInt()

    // --- Gráfico 2D ---

    private fun graph2d(canvas: Canvas, g: Graph2D, dark: Boolean) {
        val unit = min(g.width, g.height)
        val label = (unit * 0.04f).coerceIn(6f, 22f)
        val pad = label * 1.4f
        val plot = RectF(g.left + pad * 1.6f, g.top + pad, g.right - pad, g.bottom - pad * 1.4f)
        if (plot.width() <= 0 || plot.height() <= 0) return
        val exprs = g.functions.map { expr(it) }
        val xMin = min(g.xMin, g.xMax - 1e-3f).toDouble()
        val xMax = g.xMax.toDouble().coerceAtLeast(xMin + 1e-3)
        val samples = 480
        val values = exprs.map { e ->
            DoubleArray(samples + 1) { k -> e?.eval(xMin + (xMax - xMin) * k / samples) ?: Double.NaN }
        }
        var yMin = g.yMin.toDouble()
        var yMax = g.yMax.toDouble()
        if (g.autoY) autoRange(values)?.let { (lo, hi) -> yMin = lo; yMax = hi }
        if (yMax - yMin < 1e-9) yMax = yMin + 1
        fun sx(x: Double) = (plot.left + (x - xMin) / (xMax - xMin) * plot.width()).toFloat()
        fun sy(y: Double) = (plot.bottom - (y - yMin) / (yMax - yMin) * plot.height()).toFloat()

        val axis = axisColor(dark)
        val stepX = niceStep(xMax - xMin)
        val stepY = niceStep(yMax - yMin)
        line.pathEffect = null
        if (g.grid) {
            line.color = axis and 0x00FFFFFF or 0x22000000
            line.strokeWidth = unit * 0.0025f
            var x = ceil(xMin / stepX) * stepX
            while (x <= xMax + 1e-9) {
                canvas.drawLine(sx(x), plot.top, sx(x), plot.bottom, line)
                x += stepX
            }
            var y = ceil(yMin / stepY) * stepY
            while (y <= yMax + 1e-9) {
                canvas.drawLine(plot.left, sy(y), plot.right, sy(y), line)
                y += stepY
            }
        }
        // Ejes por el cero si está a la vista; si no, en el borde.
        val ax = if (0.0 in xMin..xMax) sx(0.0) else plot.left
        val ay = if (0.0 in yMin..yMax) sy(0.0) else plot.bottom
        line.color = axis
        line.strokeWidth = unit * 0.005f
        canvas.drawLine(plot.left, ay, plot.right, ay, line)
        canvas.drawLine(ax, plot.bottom, ax, plot.top, line)
        arrow(canvas, plot.right, ay, 1f, 0f, label * 0.6f)
        arrow(canvas, ax, plot.top, 0f, -1f, label * 0.6f)
        text.color = axis
        text.textSize = label
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("x", plot.right + label * 0.3f, ay + label * 0.35f, text)
        canvas.drawText("y", ax + label * 0.4f, plot.top - label * 0.2f, text)
        // Números de las marcas.
        text.textSize = label * 0.8f
        text.textAlign = Paint.Align.CENTER
        var x = ceil(xMin / stepX) * stepX
        while (x <= xMax + 1e-9) {
            if (abs(x) > stepX / 2) {
                canvas.drawLine(sx(x), ay - label * 0.2f, sx(x), ay + label * 0.2f, line)
                canvas.drawText(format(x, stepX), sx(x), min(ay + label * 1.15f, plot.bottom + label * 1.2f), text)
            }
            x += stepX
        }
        text.textAlign = Paint.Align.RIGHT
        var y = ceil(yMin / stepY) * stepY
        while (y <= yMax + 1e-9) {
            if (abs(y) > stepY / 2) {
                canvas.drawLine(ax - label * 0.2f, sy(y), ax + label * 0.2f, sy(y), line)
                canvas.drawText(format(y, stepY), max(ax - label * 0.35f, plot.left - label * 0.2f), sy(y) + label * 0.3f, text)
            }
            y += stepY
        }
        // Curvas, cortadas donde no están definidas o saltan (asíntotas).
        val save = canvas.save()
        canvas.clipRect(plot)
        line.strokeWidth = unit * 0.008f
        values.forEachIndexed { i, v ->
            line.color = ink(if (i == 0) g.color else PALETTE[(i - 1) % PALETTE.size], dark)
            path.rewind()
            var pen = false
            for (k in 0..samples) {
                val yv = v[k]
                if (!yv.isFinite() || (pen && abs(yv - v[k - 1]) > (yMax - yMin) * 2)) {
                    pen = false
                    continue
                }
                val px = sx(xMin + (xMax - xMin) * k / samples)
                val py = sy(yv.coerceIn(yMin - (yMax - yMin) * 3, yMax + (yMax - yMin) * 3))
                if (pen) path.lineTo(px, py) else path.moveTo(px, py)
                pen = true
            }
            canvas.drawPath(path, line)
        }
        canvas.restoreToCount(save)
        // Qué es cada curva (o el error si no se entiende).
        text.textAlign = Paint.Align.LEFT
        text.textSize = label * 0.85f
        var ly = plot.top + label
        g.functions.forEachIndexed { i, f ->
            val ok = exprs[i] != null
            text.color = if (ok) ink(if (i == 0) g.color else PALETTE[(i - 1) % PALETTE.size], dark) else 0xFFD93025.toInt()
            canvas.drawText(if (ok) "y = ${Expr.clean(f)}" else "No entiendo: $f", plot.left + label * 0.5f, ly, text)
            ly += label * 1.1f
        }
    }

    private fun arrow(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float, size: Float) {
        path.rewind()
        path.moveTo(x + dx * size, y + dy * size)
        path.lineTo(x - dy * size * 0.6f, y + dx * size * 0.6f)
        path.lineTo(x + dy * size * 0.6f, y - dx * size * 0.6f)
        path.close()
        fill.color = line.color
        canvas.drawPath(path, fill)
    }

    /** Rango de y que muestra las curvas sin que un valor enorme (una asíntota) aplaste todo. */
    private fun autoRange(values: List<DoubleArray>): Pair<Double, Double>? {
        val all = values.flatMap { v -> v.filter { it.isFinite() } }.sorted()
        if (all.isEmpty()) return null
        var lo = all[(all.size * 0.03).toInt()]
        var hi = all[((all.size - 1) * 0.97).toInt()]
        // Si los extremos de verdad no están muy lejos, entran.
        val span = max(hi - lo, 1e-9)
        if (all.first() > lo - span) lo = all.first()
        if (all.last() < hi + span) hi = all.last()
        if (hi - lo < 1e-9) {
            lo -= 1
            hi += 1
        }
        val margin = (hi - lo) * 0.08
        return (lo - margin) to (hi + margin)
    }

    private fun niceStep(span: Double): Double {
        val raw = span / 8
        val p = 10.0.pow(floor(log10(raw)))
        val m = raw / p
        return p * when {
            m < 1.5 -> 1.0
            m < 3.5 -> 2.0
            m < 7.5 -> 5.0
            else -> 10.0
        }
    }

    private fun format(v: Double, step: Double): String {
        val decimals = max(0, -floor(log10(step)).toInt())
        return "%.${decimals}f".format(v).replace('.', ',').let { if (it == "-0") "0" else it }
    }

    // --- Gráfico 3D ---

    private fun graph3d(canvas: Canvas, g: Graph3D, dark: Boolean) {
        val unit = min(g.width, g.height)
        val label = (unit * 0.045f).coerceIn(6f, 22f)
        val e = expr(g.function)
        text.textAlign = Paint.Align.LEFT
        text.textSize = label * 0.9f
        if (e == null) {
            text.color = 0xFFD93025.toInt()
            canvas.drawText("No entiendo: ${g.function}", g.left + label, g.top + label * 1.5f, text)
            return
        }
        val surface = Surface(e, g.xMin, g.xMax, g.yMin, g.yMax)
        val view = View3D(g.yaw, g.pitch)
        val scale = (unit * 0.95f / (2 * Surface.RADIUS)).toFloat()
        val cx = g.centerX
        val cy = g.centerY + label * 0.5f
        fun px(v: Float) = cx + v * scale
        fun py(v: Float) = cy + v * scale
        val axis = axisColor(dark)
        // Caja del dominio, atrás de todo.
        line.pathEffect = null
        line.color = axis and 0x00FFFFFF or 0x40000000
        line.strokeWidth = unit * 0.003f
        val box = surface.box(view)
        for (k in box.indices step 4) canvas.drawLine(px(box[k]), py(box[k + 1]), px(box[k + 2]), py(box[k + 3]), line)
        val low = ink(g.color, dark)
        val high = 0xFFF2A93B.toInt()
        line.strokeWidth = unit * 0.002f
        line.color = if (dark) 0x66000000 else 0x55FFFFFF
        for (q in surface.quads(view)) {
            path.rewind()
            path.moveTo(px(q.poly[0]), py(q.poly[1]))
            for (k in 1 until 4) path.lineTo(px(q.poly[2 * k]), py(q.poly[2 * k + 1]))
            path.close()
            fill.color = blend(low, high, q.level) and 0x00FFFFFF or 0xE6000000.toInt()
            canvas.drawPath(path, fill)
            canvas.drawPath(path, line)
        }
        val ends = surface.axisEnds(view)
        text.color = axis
        text.textSize = label
        text.textAlign = Paint.Align.CENTER
        canvas.drawText("x", px(ends[0]), py(ends[1]) + label * 0.35f, text)
        canvas.drawText("y", px(ends[2]), py(ends[3]) + label * 0.35f, text)
        canvas.drawText("z", px(ends[4]), py(ends[5]), text)
        text.textAlign = Paint.Align.LEFT
        text.textSize = label * 0.85f
        text.color = ink(g.color, dark)
        canvas.drawText("z = ${Expr.clean(g.function)}", g.left + label * 0.5f, g.top + label * 1.2f, text)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - f) + ((b shr shift) and 0xFF) * f).toInt() shl shift
        return 0xFF000000.toInt() or ch(16) or ch(8) or ch(0)
    }

    // --- Figuras 3D ---

    private fun solid(canvas: Canvas, s: Solid, dark: Boolean) {
        val mesh = Solids.mesh(s.kind, s.a, s.b, s.c)
        val drawing = mesh.draw(View3D(s.yaw, s.pitch), s.hiddenEdges)
        val unit = min(s.width, s.height)
        val scale = (unit * 0.92f / (2 * mesh.radius)).toFloat()
        val cx = s.centerX
        val cy = s.centerY
        val color = ink(s.color, dark)
        if (s.shaded) {
            for ((poly, light) in drawing.faces) {
                path.rewind()
                path.moveTo(cx + poly[0] * scale, cy + poly[1] * scale)
                for (k in 1 until poly.size / 2) path.lineTo(cx + poly[2 * k] * scale, cy + poly[2 * k + 1] * scale)
                path.close()
                fill.color = color and 0x00FFFFFF or ((0x18 + 0x48 * light).toInt() shl 24)
                canvas.drawPath(path, fill)
            }
        }
        val width = max(unit * 0.008f, 1f)
        line.color = color
        line.strokeWidth = width
        line.pathEffect = null
        segments(canvas, drawing.visible, cx, cy, scale)
        if (drawing.hidden.isNotEmpty()) {
            line.color = color and 0x00FFFFFF or 0x99000000.toInt()
            line.strokeWidth = width * 0.8f
            line.pathEffect = DashPathEffect(floatArrayOf(width * 4, width * 3), 0f)
            segments(canvas, drawing.hidden, cx, cy, scale)
            line.pathEffect = null
        }
    }

    private fun segments(canvas: Canvas, s: List<Float>, cx: Float, cy: Float, scale: Float) {
        path.rewind()
        for (k in s.indices step 4) {
            path.moveTo(cx + s[k] * scale, cy + s[k + 1] * scale)
            path.lineTo(cx + s[k + 2] * scale, cy + s[k + 3] * scale)
        }
        canvas.drawPath(path, line)
    }

    // --- Audio y video ---

    private fun media(canvas: Canvas, m: Media, dark: Boolean, thumb: Bitmap?) {
        val r = RectF(m.left, m.top, m.right, m.bottom)
        val radius = min(m.width, m.height) * 0.12f
        val accent = if (dark) 0xFF7AA7FF.toInt() else 0xFF2F6FD6.toInt()
        if (m.kind == MediaKind.VIDEO) {
            fill.color = 0xFF202226.toInt()
            canvas.drawRoundRect(r, radius, radius, fill)
            if (thumb != null) {
                val save = canvas.save()
                path.rewind()
                path.addRoundRect(r, radius, radius, Path.Direction.CW)
                canvas.clipPath(path)
                // Llena el rectángulo sin deformar el cuadro.
                val s = max(m.width / thumb.width, m.height / thumb.height)
                val w = thumb.width * s
                val h = thumb.height * s
                canvas.drawBitmap(thumb, null, RectF(m.centerX - w / 2, m.centerY - h / 2, m.centerX + w / 2, m.centerY + h / 2), bitmapPaint)
                canvas.restoreToCount(save)
            }
            val c = min(m.width, m.height) * 0.16f
            fill.color = 0xCC000000.toInt()
            canvas.drawCircle(m.centerX, m.centerY, c, fill)
            play(canvas, m.centerX, m.centerY, c * 0.5f, 0xFFFFFFFF.toInt())
            text.textSize = min(m.width, m.height) * 0.09f
            text.textAlign = Paint.Align.RIGHT
            text.color = 0xFFFFFFFF.toInt()
            canvas.drawText(duration(m.durationMs), m.right - radius * 0.6f, m.bottom - radius * 0.6f, text)
        } else {
            fill.color = if (dark) 0xFF2A2F3A.toInt() else 0xFFEFF3FB.toInt()
            canvas.drawRoundRect(r, radius, radius, fill)
            line.pathEffect = null
            line.color = accent and 0x00FFFFFF or 0x66000000
            line.strokeWidth = m.height * 0.03f
            canvas.drawRoundRect(r, radius, radius, line)
            val c = m.height * 0.32f
            fill.color = accent
            canvas.drawCircle(m.left + m.height / 2, m.centerY, c, fill)
            play(canvas, m.left + m.height / 2, m.centerY, c * 0.5f, 0xFFFFFFFF.toInt())
            text.textAlign = Paint.Align.LEFT
            text.color = if (dark) 0xFFE6E6E6.toInt() else 0xFF1F1F1F.toInt()
            text.textSize = m.height * 0.3f
            canvas.drawText("Nota de voz", m.left + m.height * 1.05f, m.centerY - m.height * 0.02f, text)
            text.textSize = m.height * 0.24f
            text.color = if (dark) 0xFF9A9A9A.toInt() else 0xFF6B6B6B.toInt()
            canvas.drawText(duration(m.durationMs), m.left + m.height * 1.05f, m.centerY + m.height * 0.3f, text)
        }
    }

    private fun play(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        path.rewind()
        path.moveTo(cx - size * 0.6f, cy - size)
        path.lineTo(cx + size, cy)
        path.lineTo(cx - size * 0.6f, cy + size)
        path.close()
        fill.color = color
        canvas.drawPath(path, fill)
    }

    companion object {
        /** Colores de la segunda curva en adelante. */
        private val PALETTE = intArrayOf(0xFFD93025.toInt(), 0xFF188038.toInt(), 0xFFE37400.toInt(), 0xFF8E44AD.toInt(), 0xFF00838F.toInt())

        fun duration(ms: Long): String {
            val s = (ms + 500) / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
