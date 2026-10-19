package com.octavio.tinta.canvas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.octavio.tinta.model.NoteImage
import com.octavio.tinta.model.NoteText
import com.octavio.tinta.model.Transform
import java.io.File
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.max

/** Dibujo de imágenes y textos de una nota, en coordenadas del mundo. */
object Elements {
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** La imagen del archivo, reducida a la potencia de 2 que la deja en `maxSide` px o menos. */
    fun decode(file: File, maxSide: Int, hardware: Boolean): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            if (hardware) inPreferredConfig = Bitmap.Config.HARDWARE
        }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    /** `bitmap`: en pantalla, el de la GPU; al exportar, uno decodificado aparte (sin GPU). */
    fun drawImage(canvas: Canvas, image: NoteImage, bitmap: Bitmap) {
        val save = canvas.save()
        if (image.rotation != 0f) {
            canvas.rotate(image.rotation, image.left + image.width / 2, image.top + image.height / 2)
        }
        canvas.drawBitmap(bitmap, null, RectF(image.left, image.top, image.left + image.width, image.top + image.height), imagePaint)
        canvas.restoreToCount(save)
    }

    /**
     * Un cuadro de texto (de Jnotes): una línea cada 1,25 veces el tamaño de letra, como en
     * jnotes2pdf, y los renglones que no entran en el ancho del cuadro siguen abajo (si no, se salían
     * y pisaban lo de al lado, y se veían cortados donde terminaba la caja).
     */
    fun drawText(canvas: Canvas, text: NoteText, paint: TextPaint, color: Int = text.color) {
        paint.color = color
        paint.textSize = text.size
        if (!needsWrap(text, paint)) {
            text.text.split('\n').forEachIndexed { n, line ->
                canvas.drawText(line, text.left, text.top + text.size * (1 + LINE * n), paint)
            }
            return
        }
        val layout = layout(text, paint)
        val save = canvas.save()
        // La primera línea con la base donde estaba: a un tamaño de letra del borde de arriba.
        canvas.translate(text.left, text.top + text.size + paint.ascent())
        layout.draw(canvas)
        canvas.restoreToCount(save)
    }

    fun newTextPaint() = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    private const val LINE = 1.25f

    /** Para medir sin tocar el pincel de quien dibuja (se mide desde varios hilos). */
    private val measurePaint = newTextPaint()
    private val sizes = WeakHashMap<NoteText, FloatArray>()

    private fun needsWrap(text: NoteText, paint: TextPaint): Boolean {
        if (text.width < text.size * 2) return false // cuadro sin ancho útil: como venía
        return text.text.split('\n').any { paint.measureText(it) > text.width * 1.02f }
    }

    private fun layout(text: NoteText, paint: TextPaint): StaticLayout =
        StaticLayout.Builder.obtain(text.text, 0, text.text.length, paint, ceil(text.width).toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(text.size * LINE - paint.fontSpacing, 1f)
            .build()

    /** Ancho y alto de lo que ocupa el texto dibujado (con los renglones que pasan abajo). */
    private fun drawnSize(t: NoteText): FloatArray = synchronized(sizes) {
        sizes[t] ?: run {
            measurePaint.textSize = t.size
            val wrap = needsWrap(t, measurePaint)
            val lines = if (wrap) layout(t, measurePaint).lineCount else t.text.split('\n').size
            val width = if (wrap) t.width else t.text.split('\n').maxOf { measurePaint.measureText(it) }
            floatArrayOf(width, t.size * (1 + LINE * (lines - 1)) + t.size * 0.3f).also { sizes[t] = it }
        }
    }

    /** La caja de la imagen en el mundo, girada si está girada. */
    fun imageBounds(i: NoteImage): RectF {
        if (i.rotation % 360f == 0f) return RectF(i.left, i.top, i.left + i.width, i.top + i.height)
        val t = Transform.rotation(Math.toRadians(i.rotation.toDouble()).toFloat(), i.centerX, i.centerY)
        val b = t.box(i.left, i.top, i.left + i.width, i.top + i.height)
        return RectF(b[0], b[1], b[2], b[3])
    }

    fun textBounds(t: NoteText): RectF {
        val d = drawnSize(t)
        return RectF(t.left, t.top, t.left + max(t.width, d[0]), t.top + max(max(t.height, t.size * LINE), d[1]))
    }
}
