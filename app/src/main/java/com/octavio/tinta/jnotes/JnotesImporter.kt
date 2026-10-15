package com.octavio.tinta.jnotes

import com.octavio.tinta.model.InkStroke
import com.octavio.tinta.model.Note
import com.octavio.tinta.model.NoteImage
import com.octavio.tinta.model.NoteText
import com.octavio.tinta.model.Tool
import com.octavio.tinta.model.Viewport
import com.octavio.tinta.storage.NoteStore
import com.octavio.tinta.storage.StrokeCodec
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/** Convierte una nota de Jnotes en una de Tinta, con las páginas una debajo de la otra. */
object JnotesImporter {
    /**
     * dp del mundo por unidad de Jnotes. Las hojas de Jnotes miden 1240 de ancho (A4 a 150 ppp);
     * con 0,75 una hoja ocupa unos 930 dp, el ancho de la P12 en vertical.
     */
    const val SCALE = 0.75f

    /** Separación entre páginas y margen, en unidades de Jnotes (el mismo margen que jnotes2pdf). */
    private const val GAP = 80f
    private const val MARGIN = 40f

    private const val EPSILON = 0.1f

    class Result(val note: Note, val failedStrokes: Int)

    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val height get() = bottom - top
    }

    /** `density`: px por dp de esta pantalla, para traducir el zoom de Jnotes (que está en px). */
    /** Las imágenes se guardan en `mediaDir`, la carpeta de medios de la nota nueva. */
    fun import(input: InputStream, id: String, fallbackTitle: String, density: Float, mediaDir: File): Result {
        val j = JnotesFile.parse(input)
        val note = Note(id, j.title.ifBlank { fallbackTitle })
        var y = MARGIN
        var failed = 0
        var first = true
        for (page in j.pages) {
            val rect = pageRect(page) ?: continue
            val dx = MARGIN - rect.left
            val dy = y - rect.top
            for (s in page.strokes) {
                val stroke = toStroke(s, dx, dy)
                if (stroke != null) note.strokes.add(stroke) else failed++
            }
            for (i in page.images) {
                note.images.add(
                    NoteImage(
                        (i.x + dx) * SCALE, (i.y + dy) * SCALE, i.width * SCALE, i.height * SCALE, i.rotation,
                        NoteStore.keep(mediaDir, i.bytes),
                    ),
                )
            }
            for (t in page.texts) {
                note.texts.add(
                    NoteText((t.x + dx) * SCALE, (t.y + dy) * SCALE, t.width * SCALE, t.height * SCALE, t.text, t.size * SCALE, t.color),
                )
            }
            if (first) {
                note.viewport = initialView(page, rect, dx, dy, density)
                first = false
            }
            y += rect.height + GAP
        }
        return Result(note, failed)
    }

    /**
     * Zona que ocupa la página, como en jnotes2pdf: la hoja entera si el contenido entra en ella;
     * si no (lienzo infinito), lo que ocupan los trazos, las imágenes y los textos.
     */
    fun pageRect(page: JnotesFile.JPage): Rect? {
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        for (s in page.strokes) {
            for (k in s.xs.indices) {
                l = min(l, s.xs[k] - s.width)
                r = max(r, s.xs[k] + s.width)
                t = min(t, s.ys[k] - s.width)
                b = max(b, s.ys[k] + s.width)
            }
        }
        for (i in page.images) {
            l = min(l, i.x)
            t = min(t, i.y)
            r = max(r, i.x + i.width)
            b = max(b, i.y + i.height)
        }
        for (x in page.texts) {
            l = min(l, x.x)
            t = min(t, x.y)
            r = max(r, x.x + x.width)
            b = max(b, x.y + x.height)
        }
        if (l > r) return null
        val fits = l >= 0 && t >= 0 && r <= page.width && b <= page.height
        return if (fits) Rect(0f, 0f, page.width, page.height) else Rect(l, t, r, b)
    }

    /**
     * Abre donde se estaba mirando en Jnotes si esa vista cae sobre la página; si no, en el
     * comienzo de la página.
     */
    private fun initialView(page: JnotesFile.JPage, rect: Rect, dx: Float, dy: Float, density: Float): Viewport {
        val inside = page.viewX in rect.left..rect.right && page.viewY in rect.top..rect.bottom
        if (!inside) return Viewport(0f, 0f, 1f)
        val zoom = if (page.viewZoom > 0f) page.viewZoom / (SCALE * density) else 1f
        return Viewport((page.viewX + dx) * SCALE, (page.viewY + dy) * SCALE, zoom.coerceIn(0.3f, 2f))
    }

    private fun toStroke(s: JnotesFile.JStroke, dx: Float, dy: Float): InkStroke? = runCatching {
        if (s.xs.isEmpty()) return null
        val tool = when (s.tool) {
            3 -> Tool.HIGHLIGHTER
            6 -> Tool.MARKER // formas: ancho constante
            else -> Tool.PEN // incluye la herramienta 7, que sigue sin identificar
        }
        // El script dibuja el resaltador con el doble del ancho guardado.
        val size = (if (tool == Tool.HIGHLIGHTER) s.width * 2 else s.width) * SCALE
        val color = if (tool == Tool.HIGHLIGHTER) {
            val alpha = (s.color ushr 24).takeIf { it in 1..0xFE } ?: 0x66
            (s.color and 0x00FFFFFF) or (alpha shl 24)
        } else {
            s.color
        }
        val pressure = tool == Tool.PEN && s.pressures.any { it > 0f }
        val n = s.xs.size
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val ps = if (pressure) FloatArray(n) else null
        var count = 0
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        for (k in 0 until n) {
            val x = (s.xs[k] + dx) * SCALE
            val y = (s.ys[k] + dy) * SCALE
            if (x.isNaN() || y.isNaN()) continue
            if (count > 0 && x == xs[count - 1] && y == ys[count - 1]) continue
            xs[count] = x
            ys[count] = y
            ps?.set(count, s.pressures[k].coerceIn(0f, 1f))
            count++
            l = min(l, x)
            t = min(t, y)
            r = max(r, x)
            b = max(b, y)
        }
        if (count == 0) return null
        // Caja conservadora sin armar la malla: el trazo no se aleja más que su ancho de los puntos.
        // Jnotes no guarda tiempos: un punto cada 8 ms, como un lápiz a 120 Hz.
        InkStroke.unbuilt(
            tool, color, size, EPSILON, l - size, t - size, r + size, b + size,
            StrokeCodec.encodePoints(count, xs, ys, ps, stepMs = 8),
        )
    }.getOrNull()
}
