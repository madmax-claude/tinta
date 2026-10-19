package com.octavio.tinta.canvas

import android.graphics.Canvas
import android.graphics.Paint
import com.octavio.tinta.model.Background
import com.octavio.tinta.model.PageLayout
import com.octavio.tinta.model.PageSize
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fondo del papel. Se usa igual en pantalla, en las baldosas, en las miniaturas y en el PDF; cada
 * hilo tiene su instancia. En pantalla, con mucho alejamiento se saltean líneas y textos que no se
 * verían; `vector` (el PDF, que se mira con zoom) los dibuja siempre.
 */
class Paper(vector: Boolean = false, color: Int? = null) {
    /** Color de la hoja (en modo claro): el de la nota, o el de siempre. */
    var color: Int = color ?: COLOR

    /** Cuánto de los mínimos en píxeles se exige: en el PDF, casi nada. */
    private val detail = if (vector) 0.2f else 1f

    private val linePaint = Paint()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val fill = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private var lines = FloatArray(256)
    private var count = 0

    /**
     * Pinta el fondo en el rectángulo de pantalla (0, 0, width, height) para una vista donde
     * pantalla = mundo · scale + (tx, ty). Con mucho alejamiento se saltean líneas para que la
     * cuadrícula no se vuelva una mancha. Con hojas, alrededor va el escritorio y el dibujo del
     * fondo empieza en la esquina de cada hoja; `pageCount` es cuántas hay.
     */
    fun draw(
        canvas: Canvas,
        background: Background,
        scale: Float,
        tx: Float,
        ty: Float,
        width: Float,
        height: Float,
        dark: Boolean = false,
        strong: Boolean = false,
        pages: PageLayout = PageLayout(),
        pageCount: Int = 1,
    ) {
        // `strong`: líneas más marcadas, para las muestras chicas del selector de fondo.
        contrast = if (strong) 2 else 1
        if (!pages.paged) {
            canvas.drawColor(if (dark) DARK else color)
            pattern(canvas, background, scale, tx, ty, width, height, dark, frameFor(background, pages))
            return
        }
        canvas.drawColor(if (dark) DARK_DESK else DESK)
        val top = -ty / scale
        val bottom = (height - ty) / scale
        val first = pages.pageAt(top).coerceAtLeast(0)
        val last = pages.pageAt(bottom).coerceAtMost(pageCount - 1)
        val l = pages.left * scale + tx
        val r = pages.right * scale + tx
        if (r < 0f || l > width) return
        val shadow = max(1f, scale * 1.5f)
        for (k in first..last) {
            val t = pages.pageTop(k) * scale + ty
            val b = t + pages.height * scale
            fill.color = if (dark) 0x66000000 else 0x22000000
            canvas.drawRect(l + shadow * 0.5f, t + shadow, r + shadow * 0.5f, b + shadow, fill)
            fill.color = if (dark) DARK else color
            canvas.drawRect(l, t, r, b, fill)
            val save = canvas.save()
            canvas.clipRect(max(l, 0f), max(t, 0f), min(r, width), min(b, height))
            // El dibujo empieza en la esquina de la hoja: el mismo en todas.
            pattern(canvas, background, scale, l, t, width, height, dark, pages.copy(left = 0f, top = 0f))
            canvas.restoreToCount(save)
        }
    }

    /** La hoja de errores necesita hojas: en una nota infinita se repite una A4 hacia abajo. */
    private fun frameFor(background: Background, pages: PageLayout) =
        if (background == Background.ERRORS && !pages.paged) VIRTUAL_PAGE else pages

    private fun pattern(
        canvas: Canvas,
        background: Background,
        scale: Float,
        tx: Float,
        ty: Float,
        width: Float,
        height: Float,
        dark: Boolean,
        frame: PageLayout,
    ) {
        val ink = if (dark) DARK_LINE else LINE
        val lineWidth = (scale / 2f).coerceIn(1f, 2f)
        linePaint.strokeWidth = lineWidth
        linePaint.color = ink or alpha(0x40)
        val v = Area(scale, tx, ty, width, height)
        when (background) {
            Background.BLANK -> Unit
            Background.GRID_SMALL -> grid(canvas, v, STEP / 2, both = true)
            Background.GRID -> grid(canvas, v, STEP, both = true)
            Background.GRID_LARGE -> grid(canvas, v, STEP * 2, both = true)
            Background.GRAPH -> {
                val minor = STEP / 5 // 1 mm
                if (minor * scale >= 6f * detail) {
                    linePaint.color = ink or alpha(0x1C)
                    grid(canvas, v, minor, both = true)
                }
                linePaint.color = ink or alpha(0x50)
                grid(canvas, v, STEP, both = true)
            }
            Background.LINES -> grid(canvas, v, STEP, both = false)
            Background.LINES_WIDE -> grid(canvas, v, STEP * 1.5f, both = false)
            Background.DOTS_SMALL -> dots(canvas, v, STEP / 2, lineWidth * 2f, ink)
            Background.DOTS -> dots(canvas, v, STEP, lineWidth * 2.5f, ink)
            Background.ISOMETRIC -> {
                var d = STEP * SQRT3 / 2
                while (d * scale < 10f * detail) d *= 2
                for (angle in floatArrayOf(0f, 60f, 120f)) family(v, angle, d)
                flush(canvas)
            }
            Background.HEX -> hexagons(canvas, v)
            Background.MUSIC -> staves(canvas, v)
            Background.ERRORS -> errors(canvas, v, frame, ink, lineWidth)
        }
    }

    private var contrast = 1

    private fun alpha(a: Int) = (a * contrast).coerceAtMost(0xFF) shl 24

    private class Area(val scale: Float, val tx: Float, val ty: Float, val width: Float, val height: Float) {
        val left get() = -tx / scale
        val top get() = -ty / scale
        val right get() = (width - tx) / scale
        val bottom get() = (height - ty) / scale
    }

    /** Líneas cada `worldStep` dp: horizontales y, con `both`, también verticales. */
    private fun grid(canvas: Canvas, v: Area, worldStep: Float, both: Boolean) {
        var step = worldStep
        while (step * v.scale < 12f * detail) step *= 2
        val px = step * v.scale
        if (both) {
            var x = ceil(-v.tx / px) * px + v.tx
            while (x <= v.width) {
                add(x, 0f, x, v.height)
                x += px
            }
        }
        var y = ceil(-v.ty / px) * px + v.ty
        while (y <= v.height) {
            add(0f, y, v.width, y)
            y += px
        }
        flush(canvas)
    }

    private fun dots(canvas: Canvas, v: Area, worldStep: Float, size: Float, ink: Int) {
        var step = worldStep
        while (step * v.scale < 12f * detail) step *= 2
        val px = step * v.scale
        dotPaint.strokeWidth = size
        dotPaint.color = ink or alpha(0x80)
        val x0 = floor(-v.tx / px) * px + v.tx
        var y = ceil(-v.ty / px) * px + v.ty
        while (y <= v.height) {
            var x = x0
            while (x <= v.width) {
                add(x, y, 0f, 0f)
                x += px
            }
            y += px
        }
        // drawPoints toma pares (x, y): se reacomoda lo acumulado como puntos.
        var n = 0
        for (i in 0 until count step 4) {
            lines[n++] = lines[i]
            lines[n++] = lines[i + 1]
        }
        canvas.drawPoints(lines, 0, n, dotPaint)
        count = 0
    }

    /**
     * Una familia de rectas paralelas con dirección `angle` (grados) separadas `spacing` dp, que
     * pasa por el origen del mundo. Se calcula en pantalla: cada recta es n·p = k·D + n·t.
     */
    private fun family(v: Area, angle: Float, spacing: Float) {
        val rad = Math.toRadians(angle.toDouble())
        val nx = -sin(rad).toFloat()
        val ny = cos(rad).toFloat()
        val d = spacing * v.scale
        val o = nx * v.tx + ny * v.ty
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (cx in floatArrayOf(0f, v.width)) for (cy in floatArrayOf(0f, v.height)) {
            val p = nx * cx + ny * cy
            lo = min(lo, p)
            hi = max(hi, p)
        }
        val half = hypot(v.width, v.height)
        val mx = v.width / 2
        val my = v.height / 2
        val ux = -ny
        val uy = nx
        var k = ceil((lo - o) / d)
        while (k * d + o <= hi) {
            val c = k * d + o
            val off = c - (nx * mx + ny * my)
            val px = mx + nx * off
            val py = my + ny * off
            add(px - ux * half, py - uy * half, px + ux * half, py + uy * half)
            k++
        }
    }

    /** Hexágonos con un lado plano arriba; de cada celda se dibujan tres lados, los otros son de las vecinas. */
    private fun hexagons(canvas: Canvas, v: Area) {
        var a = STEP * 0.6f
        while (a * v.scale < 24f * detail) a *= 2
        val h = SQRT3 * a
        val colW = 1.5f * a
        val i0 = floor(v.left / colW).toInt() - 1
        val i1 = ceil(v.right / colW).toInt() + 1
        val j0 = floor(v.top / h).toInt() - 1
        val j1 = ceil(v.bottom / h).toInt() + 1
        val s = v.scale
        for (i in i0..i1) {
            val cx = i * colW
            val shift = if (i % 2 != 0) h / 2 else 0f
            for (j in j0..j1) {
                val cy = j * h + shift
                // Vértices: arriba a la derecha, arriba a la izquierda, izquierda, abajo a la izquierda.
                val x1 = (cx + a / 2) * s + v.tx
                val x2 = (cx - a / 2) * s + v.tx
                val x3 = (cx - a) * s + v.tx
                val yTop = (cy - h / 2) * s + v.ty
                val yMid = cy * s + v.ty
                val yBottom = (cy + h / 2) * s + v.ty
                add(x1, yTop, x2, yTop)
                add(x2, yTop, x3, yMid)
                add(x3, yMid, x2, yBottom)
            }
        }
        flush(canvas)
    }

    /** Pentagramas: cinco líneas cada [STAFF_GAP] dp y un pentagrama cada [STAFF_PERIOD] dp. */
    private fun staves(canvas: Canvas, v: Area) {
        if (STAFF_GAP * v.scale < 3f * detail) return
        val first = floor(v.top / STAFF_PERIOD).toInt()
        val last = ceil(v.bottom / STAFF_PERIOD).toInt()
        for (n in first..last) {
            for (line in 0 until 5) {
                val y = (n * STAFF_PERIOD + STAFF_MARGIN + line * STAFF_GAP) * v.scale + v.ty
                if (y in 0f..v.height) add(0f, y, v.width, y)
            }
        }
        flush(canvas)
    }

    /**
     * Hoja de errores: título, y una tabla con columnas para el ejercicio, el error, la corrección y
     * qué repasar. Cada fila tiene renglones finos y entre filas va una línea más marcada. Se dibuja
     * en cada hoja de `frame` (con el origen en su esquina).
     */
    private fun errors(canvas: Canvas, v: Area, frame: PageLayout, ink: Int, lineWidth: Float) {
        val s = v.scale
        val w = frame.width
        val h = frame.height
        val m = ERR_MARGIN
        val tableTop = m + ERR_TITLE
        val headerBottom = tableTop + ERR_HEADER
        val rows = ((h - m - headerBottom) / ERR_RULE).toInt()
        val tableBottom = headerBottom + rows * ERR_RULE
        // Con hojas de verdad se llama una vez por hoja; en una nota infinita, se repite.
        val repeat = frame === VIRTUAL_PAGE
        val first = if (repeat) frame.pageAt(v.top) else 0
        val last = if (repeat) frame.pageAt(v.bottom) else 0
        val showText = 9f * s >= 5f * detail
        val strong = ink or alpha(0x70)
        val faint = ink or alpha(0x30)
        for (k in first..last) {
            val oy = frame.pageTop(k)
            fun x(wx: Float) = wx * s + v.tx
            fun y(wy: Float) = (oy + wy) * s + v.ty
            // Encabezado de la tabla, apenas sombreado.
            fill.color = ink or alpha(0x14)
            canvas.drawRect(x(m), y(tableTop), x(w - m), y(headerBottom), fill)
            // Renglones.
            if (ERR_RULE * s >= 4f) {
                for (r in 1 until rows) {
                    if (r % ERR_RULES_PER_ROW == 0) continue
                    add(x(m), y(headerBottom + r * ERR_RULE), x(w - m), y(headerBottom + r * ERR_RULE))
                }
                linePaint.color = faint
                linePaint.strokeWidth = lineWidth
                flush(canvas)
            }
            // Separaciones entre filas, columnas y borde.
            for (r in 0..rows step ERR_RULES_PER_ROW) {
                add(x(m), y(headerBottom + r * ERR_RULE), x(w - m), y(headerBottom + r * ERR_RULE))
            }
            add(x(m), y(tableTop), x(w - m), y(tableTop))
            add(x(m), y(tableBottom), x(w - m), y(tableBottom))
            var cx = m
            for (c in 0..ERR_COLUMNS.size) {
                add(x(cx), y(tableTop), x(cx), y(tableBottom))
                if (c < ERR_COLUMNS.size) cx += (w - 2 * m) * ERR_COLUMNS[c].second
            }
            linePaint.color = strong
            linePaint.strokeWidth = lineWidth * 1.5f
            flush(canvas)
            linePaint.strokeWidth = lineWidth
            if (!showText) continue
            textPaint.color = ink or alpha(0xA0)
            textPaint.textSize = 22f * s
            textPaint.isFakeBoldText = true
            canvas.drawText("Hoja de errores", x(m), y(m + 26f), textPaint)
            textPaint.isFakeBoldText = false
            textPaint.textSize = 13f * s
            val dateX = w - m - 190f
            canvas.drawText("Tema:", x(dateX - 330f), y(m + 26f), textPaint)
            canvas.drawText("Fecha:", x(dateX), y(m + 26f), textPaint)
            add(x(dateX - 330f + 42f), y(m + 30f), x(dateX - 16f), y(m + 30f))
            add(x(dateX + 46f), y(m + 30f), x(w - m), y(m + 30f))
            linePaint.color = faint
            flush(canvas)
            textPaint.isFakeBoldText = true
            cx = m
            for ((label, f) in ERR_COLUMNS) {
                canvas.drawText(label, x(cx + 8f), y(tableTop + ERR_HEADER * 0.66f), textPaint)
                cx += (w - 2 * m) * f
            }
            textPaint.isFakeBoldText = false
        }
    }

    private fun add(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (count + 4 > lines.size) lines = lines.copyOf(lines.size * 2)
        lines[count++] = x0
        lines[count++] = y0
        lines[count++] = x1
        lines[count++] = y1
    }

    private fun flush(canvas: Canvas) {
        if (count > 0) canvas.drawLines(lines, 0, count, linePaint)
        count = 0
    }

    companion object {
        const val COLOR = 0xFFFDFBF7.toInt()
        const val DARK = 0xFF1E1F22.toInt()

        /** Alrededor de las hojas. */
        const val DESK = 0xFFD9D6CF.toInt()
        const val DARK_DESK = 0xFF111214.toInt()
        private const val LINE = 0x607590
        private const val DARK_LINE = 0xA8B8D0

        /** Separación de la cuadrícula en dp del mundo (unos 5 mm en la P12). */
        const val STEP = 32f

        private const val STAFF_GAP = 10f
        private const val STAFF_MARGIN = 30f
        private const val STAFF_PERIOD = 110f
        private val SQRT3 = sqrt(3f)

        private val VIRTUAL_PAGE = PageLayout(PageSize.A4)

        // Hoja de errores, en dp del mundo dentro de la hoja.
        private const val ERR_MARGIN = 48f
        private const val ERR_TITLE = 52f
        private const val ERR_HEADER = 34f
        private const val ERR_RULE = 36f
        private const val ERR_RULES_PER_ROW = 4
        private val ERR_COLUMNS = listOf(
            "Ejercicio" to 0.13f,
            "Mi error" to 0.29f,
            "Corrección" to 0.29f,
            "Por qué / qué repasar" to 0.29f,
        )
    }
}
