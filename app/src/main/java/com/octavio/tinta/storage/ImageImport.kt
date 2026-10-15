package com.octavio.tinta.storage

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Imágenes de la galería para pegar en una nota. */
object ImageImport {
    class Loaded(val bytes: ByteArray, val width: Int, val height: Int)

    /**
     * Lee la imagen girada como se ve (ImageDecoder aplica la orientación EXIF de las fotos),
     * reducida a `maxSide` px de lado mayor, y la vuelve a comprimir: JPG, o PNG si tiene
     * transparencia. Así una foto de 12 MP no pesa 5 MB dentro de la nota.
     */
    fun load(resolver: ContentResolver, uri: Uri, maxSide: Int = MAX_SIDE): Loaded {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val f = maxSide / max(w, h).toFloat()
            if (f < 1f) decoder.setTargetSize((w * f).roundToInt().coerceAtLeast(1), (h * f).roundToInt().coerceAtLeast(1))
        }
        try {
            val out = ByteArrayOutputStream()
            if (bitmap.hasAlpha()) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } else {
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            return Loaded(out.toByteArray(), bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    const val MAX_SIDE = 2048
    private const val JPEG_QUALITY = 90
}
