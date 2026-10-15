package com.octavio.tinta.canvas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import com.octavio.tinta.model.NoteImage
import com.octavio.tinta.model.NoteText
import com.octavio.tinta.model.Transform
import java.io.File
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

    fun drawText(canvas: Canvas, text: NoteText, paint: TextPaint, color: Int = text.color) {
        paint.color = color
        paint.textSize = text.size
        // Igual que jnotes2pdf: una línea cada 1,25 veces el tamaño de letra.
        text.text.split('\n').forEachIndexed { n, line ->
            canvas.drawText(line, text.left, text.top + text.size * (1 + 1.25f * n), paint)
        }
    }

    fun newTextPaint() = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    /** La caja de la imagen en el mundo, girada si está girada. */
    fun imageBounds(i: NoteImage): RectF {
        if (i.rotation % 360f == 0f) return RectF(i.left, i.top, i.left + i.width, i.top + i.height)
        val t = Transform.rotation(Math.toRadians(i.rotation.toDouble()).toFloat(), i.centerX, i.centerY)
        val b = t.box(i.left, i.top, i.left + i.width, i.top + i.height)
        return RectF(b[0], b[1], b[2], b[3])
    }

    fun textBounds(t: NoteText) = RectF(t.left, t.top, t.left + t.width, t.top + max(t.height, t.size * 1.25f))
}
