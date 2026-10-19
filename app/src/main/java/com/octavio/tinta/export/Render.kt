package com.octavio.tinta.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.octavio.tinta.canvas.Elements
import com.octavio.tinta.canvas.Paper
import com.octavio.tinta.canvas.WidgetPainter
import com.octavio.tinta.model.Background
import com.octavio.tinta.model.InkStroke
import com.octavio.tinta.model.NoteImage
import com.octavio.tinta.model.NoteText
import com.octavio.tinta.model.PageLayout
import com.octavio.tinta.model.Widget
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Dibujo fuera de pantalla: miniaturas y PDF. En un Canvas por software Ink dibuja los trazos como
 * paths, así que el PDF queda vectorial.
 */
object Render {
    /** Margen alrededor del contenido, en dp (como el de jnotes2pdf). */
    private const val MARGIN = 40f

    /** Límite práctico del tamaño de página en los visores de PDF. */
    private const val PDF_MAX_PT = 14400f

    fun bounds(
        strokes: List<InkStroke>,
        images: List<NoteImage> = emptyList(),
        texts: List<NoteText> = emptyList(),
        widgets: List<Widget> = emptyList(),
    ): RectF? {
        var r: RectF? = null
        fun add(box: RectF) {
            r = r?.apply { union(box) } ?: box
        }
        for (s in strokes) if (!s.left.isNaN()) add(RectF(s.left, s.top, s.right, s.bottom))
        for (i in images) add(Elements.imageBounds(i))
        for (t in texts) add(Elements.textBounds(t))
        for (w in widgets) add(RectF(w.left, w.top, w.right, w.bottom))
        return r
    }

    /** Miniatura con el comienzo de la nota (o la primera hoja), ajustada al ancho. */
    fun thumbnail(
        strokes: List<InkStroke>,
        images: List<NoteImage>,
        texts: List<NoteText>,
        widgets: List<Widget>,
        background: Background,
        pages: PageLayout,
        width: Int,
        height: Int,
        paperColor: Int? = null,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val b = bounds(strokes, images, texts, widgets)
        val left: Float
        val top: Float
        val scale: Float
        if (pages.paged) {
            left = pages.left - MARGIN / 2
            top = pages.top - MARGIN / 2
            scale = width / (pages.width + MARGIN)
        } else {
            left = (b?.left ?: 0f) - MARGIN / 2
            top = (b?.top ?: 0f) - MARGIN / 2
            scale = min(width / max((b?.width() ?: 0f) + MARGIN, 1f), 1.5f)
        }
        val count = pages.count(b?.bottom ?: Float.NaN)
        draw(canvas, strokes, images, texts, widgets, 512, background, pages, count, scale, -left * scale, -top * scale, width.toFloat(), height.toFloat(), Paper(color = paperColor))
        return bitmap
    }

    /**
     * El PDF en `cache/exports/<título>.pdf`. Con hojas, una página del tamaño real por cada hoja
     * hasta la última escrita; en una nota infinita, una sola página ajustada al contenido con
     * 1 dp = 1 pt.
     */
    fun pdf(
        context: Context,
        title: String,
        strokes: List<InkStroke>,
        images: List<NoteImage>,
        texts: List<NoteText>,
        widgets: List<Widget>,
        background: Background,
        pages: PageLayout,
        paperColor: Int? = null,
    ): File {
        if (pages.paged) return pagedPdf(context, title, strokes, images, texts, widgets, background, pages, paperColor)
        val b = bounds(strokes, images, texts, widgets) ?: RectF(0f, 0f, 595f - 2 * MARGIN, 842f - 2 * MARGIN)
        val w = b.width() + 2 * MARGIN
        val h = b.height() + 2 * MARGIN
        val scale = min(1f, PDF_MAX_PT / max(w, h))
        val pageWidth = ceil(w * scale).toInt()
        val pageHeight = ceil(h * scale).toInt()
        val paper = Paper(vector = true, color = paperColor)
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
            val tx = (MARGIN - b.left) * scale
            val ty = (MARGIN - b.top) * scale
            draw(page.canvas, strokes, images, texts, widgets, 3000, background, pages, 1, scale, tx, ty, pageWidth.toFloat(), pageHeight.toFloat(), paper)
            doc.finishPage(page)
            return write(context, title, doc)
        } finally {
            doc.close()
        }
    }

    private fun pagedPdf(
        context: Context,
        title: String,
        strokes: List<InkStroke>,
        images: List<NoteImage>,
        texts: List<NoteText>,
        widgets: List<Widget>,
        background: Background,
        pages: PageLayout,
        paperColor: Int?,
    ): File {
        val used = pages.used(bounds(strokes, images, texts, widgets)?.bottom ?: Float.NaN)
        // 160 dp por pulgada, 72 pt por pulgada: la hoja sale del tamaño real.
        val scale = 72f / 160f
        val pageWidth = (pages.width * scale).toInt()
        val pageHeight = (pages.height * scale).toInt()
        val paper = Paper(vector = true, color = paperColor)
        val doc = PdfDocument()
        try {
            for (k in 0 until used) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, k + 1).create())
                val tx = -pages.left * scale
                val ty = -pages.pageTop(k) * scale
                draw(page.canvas, strokes, images, texts, widgets, 3000, background, pages, used, scale, tx, ty, pageWidth.toFloat(), pageHeight.toFloat(), paper)
                doc.finishPage(page)
            }
            return write(context, title, doc)
        } finally {
            doc.close()
        }
    }

    private fun write(context: Context, title: String, doc: PdfDocument): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName(title) + ".pdf")
        file.outputStream().use { doc.writeTo(it) }
        return file
    }

    /** `imageSide`: lado mayor con que se decodifican las imágenes (miniatura chica, PDF grande). */
    private fun draw(
        canvas: Canvas,
        strokes: List<InkStroke>,
        images: List<NoteImage>,
        texts: List<NoteText>,
        widgets: List<Widget>,
        imageSide: Int,
        background: Background,
        pages: PageLayout,
        pageCount: Int,
        scale: Float,
        tx: Float,
        ty: Float,
        width: Float,
        height: Float,
        paper: Paper,
    ) {
        paper.draw(canvas, background, scale, tx, ty, width, height, pages = pages, pageCount = pageCount)
        val renderer = CanvasStrokeRenderer.create()
        val m = Matrix().apply {
            setScale(scale, scale)
            postTranslate(tx, ty)
        }
        // Ink no aplica la matriz al canvas: sólo la usa para el antialiasing.
        val save = canvas.save()
        canvas.concat(m)
        val left = -tx / scale
        val top = -ty / scale
        val right = (width - tx) / scale
        val bottom = (height - ty) / scale
        val visible = RectF(left, top, right, bottom)
        // Como en Jnotes: imágenes debajo, después la tinta y los textos encima.
        for (i in images) {
            if (!RectF.intersects(visible, Elements.imageBounds(i))) continue
            val bitmap = Elements.decode(i.file, imageSide, hardware = false) ?: continue
            Elements.drawImage(canvas, i, bitmap)
            bitmap.recycle()
        }
        // Los gráficos y las figuras van debajo de la tinta, para poder anotarlos.
        val painter = WidgetPainter()
        for (w in widgets) {
            if (!RectF.intersects(visible, RectF(w.left, w.top, w.right, w.bottom))) continue
            painter.draw(canvas, w, dark = false) { f -> Elements.decode(f, 1024, hardware = false) }
        }
        for (s in strokes) {
            if (!s.intersects(left, top, right, bottom)) continue
            renderer.draw(canvas, s.build() ?: continue, m)
        }
        val textPaint = Elements.newTextPaint()
        for (t in texts) if (RectF.intersects(visible, Elements.textBounds(t))) Elements.drawText(canvas, t, textPaint)
        canvas.restoreToCount(save)
    }

    /** El rectángulo `r` del mundo como imagen (con el papel), a `scale` px por dp: las capturas. */
    fun area(
        strokes: List<InkStroke>,
        images: List<NoteImage>,
        texts: List<NoteText>,
        widgets: List<Widget>,
        background: Background,
        pages: PageLayout,
        pageCount: Int,
        r: RectF,
        scale: Float,
        paperColor: Int? = null,
    ): Bitmap {
        val width = ceil(r.width() * scale).toInt().coerceAtLeast(1)
        val height = ceil(r.height() * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(
            Canvas(bitmap), strokes, images, texts, widgets, 2048, background, pages, pageCount, scale,
            -r.left * scale, -r.top * scale, width.toFloat(), height.toFloat(), Paper(color = paperColor),
        )
        return bitmap
    }

    /** La hoja `k` entera, de `width` px de ancho, para la tira de miniaturas. */
    fun page(
        strokes: List<InkStroke>,
        images: List<NoteImage>,
        texts: List<NoteText>,
        widgets: List<Widget>,
        background: Background,
        pages: PageLayout,
        k: Int,
        width: Int,
        paperColor: Int? = null,
    ): Bitmap {
        val scale = width / pages.width
        val height = (pages.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(
            Canvas(bitmap), strokes, images, texts, widgets, 256, background, pages, k + 1, scale,
            -pages.left * scale, -pages.pageTop(k) * scale, width.toFloat(), height.toFloat(), Paper(color = paperColor),
        )
        return bitmap
    }

    /**
     * Lo que hay en (sin el papel) dibujado entero dentro de un cuadrado de `size` px, con fondo
     * transparente: las miniaturas de los stickers.
     */
    fun fit(strokes: List<InkStroke>, images: List<NoteImage>, texts: List<NoteText>, widgets: List<Widget>, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val b = bounds(strokes, images, texts, widgets) ?: return bitmap
        val pad = size * 0.06f
        val scale = min((size - 2 * pad) / max(b.width(), 1f), (size - 2 * pad) / max(b.height(), 1f))
        val canvas = Canvas(bitmap)
        canvas.translate((size - b.width() * scale) / 2 - b.left * scale, (size - b.height() * scale) / 2 - b.top * scale)
        canvas.scale(scale, scale)
        val m = Matrix().apply { setScale(scale, scale) }
        for (i in images) Elements.decode(i.file, 512, hardware = false)?.let { Elements.drawImage(canvas, i, it); it.recycle() }
        val painter = WidgetPainter()
        for (w in widgets) painter.draw(canvas, w, dark = false) { f -> Elements.decode(f, 256, hardware = false) }
        val renderer = CanvasStrokeRenderer.create()
        for (s in strokes) renderer.draw(canvas, s.build() ?: continue, m)
        val textPaint = Elements.newTextPaint()
        for (t in texts) Elements.drawText(canvas, t, textPaint)
        return bitmap
    }

    fun fileName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "nota" }.take(80)
}
