package com.octavio.tinta.jnotes

import com.octavio.tinta.model.Background
import com.octavio.tinta.model.InkStroke
import com.octavio.tinta.model.Note
import com.octavio.tinta.model.NoteImage
import com.octavio.tinta.model.NoteText
import com.octavio.tinta.model.PageLayout
import com.octavio.tinta.model.PageSize
import com.octavio.tinta.model.Tool
import com.octavio.tinta.model.Viewport
import com.octavio.tinta.storage.NoteStore
import com.octavio.tinta.storage.StrokeCodec
import java.io.File
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Convierte una nota de Jnotes en una de Tinta. Los cuadernos y los PDF pasan a una nota con hojas
 * del mismo tamaño; el lienzo infinito, a una nota infinita con las páginas una debajo de la otra.
 */
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

    /**
     * `pdfPages`: dónde va cada página del PDF de la nota (vacío si no es de PDF); quien llama las
     * dibuja con PdfRenderer y las pone debajo de todo. `folder`: carpeta en Jnotes ("" si ninguna).
     */
    class Result(val note: Note, val failedStrokes: Int, val pdfPages: List<PdfPage> = emptyList(), val folder: String = "")

    /** La página `index` del PDF ocupa esta caja del mundo, en dp. */
    data class PdfPage(val index: Int, val left: Float, val top: Float, val width: Float, val height: Float)

    /** mm por unidad de las hojas de Jnotes, que están a 150 ppp. */
    private const val MM_PER_UNIT = 25.4f / 150f

    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val height get() = bottom - top
    }

    /**
     * `density`: px por dp de esta pantalla, para traducir el zoom de Jnotes (que está en px). Las
     * imágenes se guardan en `mediaDir`, la carpeta de medios de la nota nueva. En una nota de PDF,
     * el PDF se copia a `pdfOut` (si no es null). `time`: fecha de creación y de modificación.
     */
    fun import(
        input: InputStream,
        id: String,
        fallbackTitle: String,
        density: Float,
        mediaDir: File,
        pdfOut: File? = null,
        time: Long = System.currentTimeMillis(),
    ): Result {
        val j = JnotesFile.parse(input, pdfOut)
        val title = j.title.ifBlank { fallbackTitle }
        return if (j.kind == JnotesFile.NOTEBOOK || j.kind == JnotesFile.PDF) {
            paged(j, id, title, mediaDir, time)
        } else {
            infinite(j, id, title, density, mediaDir, time)
        }
    }

    /**
     * Cuaderno o PDF: hojas del tamaño más común de la nota (las de otro tamaño entran achicadas,
     * centradas arriba, como en "Importar PDF"). Las tapas quedan como imagen fija de la hoja.
     */
    private fun paged(j: JnotesFile.JNote, id: String, title: String, mediaDir: File, time: Long): Result {
        val pdf = j.kind == JnotesFile.PDF
        val common = j.pages.groupingBy { it.width to it.height }.eachCount().maxByOrNull { it.value }?.key
        val layout = (if (common == null) PageLayout(PageSize.A4) else layoutFor(common.first, common.second))
            .copy(minPages = max(1, j.pages.size))
        val note = Note(
            id, title, if (pdf) Background.BLANK else background(j.paper), created = time, modified = time, pages = layout,
            paperColor = if (pdf) null else paperColor(j.paperColor),
        )
        var failed = 0
        val pdfPages = ArrayList<PdfPage>()
        val covers = ArrayList<NoteImage>()
        j.pages.forEachIndexed { k, page ->
            val s = min(layout.width / page.width, layout.height / page.height)
            val w = page.width * s
            val h = page.height * s
            val left = layout.left + (layout.width - w) / 2
            val top = layout.pageTop(k)
            if (pdf && j.hasPdf) pdfPages.add(PdfPage(k, left, top, w, h))
            page.cover?.let { covers.add(NoteImage(left, top, w, h, 0f, NoteStore.keep(mediaDir, it), locked = true)) }
            failed += addContent(note, page, s, left, top, mediaDir)
        }
        note.images.addAll(0, covers)
        return Result(note, failed, pdfPages, j.folder)
    }

    /** Hojas de `w` × `h` unidades de Jnotes: un tamaño conocido (A4, A3, Carta…) o uno a medida. */
    fun layoutFor(w: Float, h: Float): PageLayout {
        val wMm = w * MM_PER_UNIT
        val hMm = h * MM_PER_UNIT
        fun near(a: Float, b: Float) = abs(a - b) <= 1.5f
        for (size in PageSize.entries) {
            if (size.widthMm <= 0f) continue
            if (near(wMm, size.widthMm) && near(hMm, size.heightMm)) return PageLayout(size, false)
            if (near(wMm, size.heightMm) && near(hMm, size.widthMm)) return PageLayout(size, true)
        }
        return PageLayout(PageSize.CUSTOM, false, 0f, 0f, wMm, hMm)
    }

    /** Fondo de Tinta para el de Jnotes ("PageBg/White_Graph_Paper", "PageBg/Beige_Dotted_Paper"...). */
    fun background(paper: String): Background {
        val p = paper.lowercase()
        return when {
            "wide_grid" in p -> Background.GRID_LARGE
            "graph" in p || "grid" in p -> Background.GRID
            "dot" in p -> Background.DOTS
            "line" in p || "law" in p -> Background.LINES
            else -> Background.BLANK // liso o una imagen propia
        }
    }

    /** Color de hoja: null para el blanco de siempre. */
    fun paperColor(color: Int): Int? {
        if (color == 0 || color == -1) return null
        return color or 0xFF000000.toInt()
    }

    /** Trazos, imágenes y textos de la página con p' = (left, top) + p · s. Devuelve los trazos omitidos. */
    private fun addContent(note: Note, page: JnotesFile.JPage, s: Float, left: Float, top: Float, mediaDir: File): Int {
        var failed = 0
        for (st in page.strokes) {
            val stroke = toStroke(st, s, left, top)
            if (stroke != null) note.strokes.add(stroke) else failed++
        }
        for (i in page.images) {
            note.images.add(
                NoteImage(left + i.x * s, top + i.y * s, i.width * s, i.height * s, i.rotation, NoteStore.keep(mediaDir, i.bytes)),
            )
        }
        for (t in page.texts) {
            note.texts.add(NoteText(left + t.x * s, top + t.y * s, t.width * s, t.height * s, t.text, t.size * s, t.color))
        }
        return failed
    }

    private fun infinite(j: JnotesFile.JNote, id: String, title: String, density: Float, mediaDir: File, time: Long): Result {
        val bg = if (j.paper.isEmpty()) Background.GRID else background(j.paper)
        val note = Note(id, title, bg, created = time, modified = time, paperColor = paperColor(j.paperColor))
        var y = MARGIN
        var failed = 0
        var first = true
        for (page in j.pages) {
            val rect = pageRect(page) ?: continue
            val dx = MARGIN - rect.left
            val dy = y - rect.top
            failed += addContent(note, page, SCALE, dx * SCALE, dy * SCALE, mediaDir)
            if (first) {
                note.viewport = initialView(page, rect, dx, dy, density)
                first = false
            }
            y += rect.height + GAP
        }
        return Result(note, failed, folder = j.folder)
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

    /** El trazo con p' = (ox, oy) + p · scale; el grosor también se escala. */
    private fun toStroke(s: JnotesFile.JStroke, scale: Float, ox: Float, oy: Float): InkStroke? = runCatching {
        if (s.xs.isEmpty()) return null
        val tool = when (s.tool) {
            3 -> Tool.HIGHLIGHTER
            6 -> Tool.MARKER // formas: ancho constante
            else -> Tool.PEN // incluye la herramienta 7, que sigue sin identificar
        }
        // El script dibuja el resaltador con el doble del ancho guardado.
        val size = (if (tool == Tool.HIGHLIGHTER) s.width * 2 else s.width) * scale
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
            val x = ox + s.xs[k] * scale
            val y = oy + s.ys[k] * scale
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
