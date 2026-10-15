package com.octavio.tinta.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.octavio.tinta.model.InkStroke
import com.octavio.tinta.model.Work
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Imagen de toda la nota a baja resolución. Con miles de trazos a la vista, pintar las baldosas
 * lleva varios cuadros; mientras tanto se ve esta imagen debajo. Se arma en segundo plano, en
 * franjas, con los hilos de [Work].
 */
class Overview(private val onReady: () -> Unit) {

    private class Band(val bitmap: Bitmap, val rect: RectF)

    /** Lo último que se armó; se reemplaza entero al terminar otra versión. */
    private class Image(val bands: List<Band>, val scale: Float, val version: Int)

    @Volatile private var image: Image? = null

    /** Último pedido; si llegan varios mientras se arma una imagen, sólo se arma el más nuevo. */
    private var wanted: Triple<List<InkStroke>, Int, Boolean>? = null
    private var running = false
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "tinta-vista").apply { priority = Thread.MIN_PRIORITY } }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Cambia con [clear]: lo que se estaba armando para antes ya no sirve. */
    @Volatile private var generation = 0

    /** Px de la imagen por dp del mundo, o 0 si no hay imagen. */
    val scale get() = image?.scale ?: 0f

    val hasImage get() = image != null

    fun isCurrent(version: Int) = image?.version == version || synchronized(lock) { wanted?.second == version }

    /** Arma la imagen de `strokes` (una copia) si no está ya hecha o pedida para `version`. */
    fun request(strokes: List<InkStroke>, version: Int, dark: Boolean) {
        if (isCurrent(version)) return
        synchronized(lock) {
            wanted = Triple(strokes, version, dark)
            if (running) return
            running = true
        }
        worker.execute {
            while (true) {
                val gen = generation
                val (list, v, dark) = synchronized(lock) {
                    val w = wanted
                    wanted = null
                    if (w == null) running = false
                    w
                } ?: break
                val built = runCatching { build(list, v, dark) }.getOrNull() ?: continue
                if (gen != generation) continue
                image = built
                onReady()
            }
        }
    }

    /** Otra nota, u otro modo (oscuro): la imagen de antes no se tiene que ver más. */
    fun clear() {
        synchronized(lock) {
            generation++
            wanted = null
            image = null
        }
    }

    /** Dibuja la imagen en `canvas`, que ya tiene aplicada la transformación del mundo a la pantalla. */
    fun draw(canvas: Canvas): Boolean {
        val img = image ?: return false
        for (b in img.bands) canvas.drawBitmap(b.bitmap, null, b.rect, paint)
        return true
    }

    fun shutdown() = worker.shutdownNow()

    private fun build(strokes: List<InkStroke>, version: Int, dark: Boolean): Image? {
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        for (s in strokes) {
            if (s.left.isNaN()) continue
            l = min(l, s.left)
            t = min(t, s.top)
            r = max(r, s.right)
            b = max(b, s.bottom)
        }
        if (l > r) return null
        val scale = min(MAX_PX / (r - l), MAX_PX / (b - t))
        val width = ceil((r - l) * scale).toInt().coerceAtLeast(1)
        val height = ceil((b - t) * scale).toInt().coerceAtLeast(1)
        val bandCount = ceil(height / BAND_PX.toFloat()).toInt()
        val bands = Work.map((0 until bandCount).toList()) { i ->
            val y0 = i * BAND_PX
            val h = min(BAND_PX, height - y0)
            val bitmap = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
            val top = t + y0 / scale
            val bottom = t + (y0 + h) / scale
            val m = Matrix().apply {
                setScale(scale, scale)
                postTranslate(-l * scale, -top * scale)
            }
            val canvas = Canvas(bitmap)
            canvas.concat(m)
            val renderer = CanvasStrokeRenderer.create() // uno por hilo: no es seguro compartirlo
            for (s in strokes) {
                if (!s.intersects(l, top, r, bottom)) continue
                val mesh = s.build() ?: continue
                renderer.draw(canvas, s.display(dark) ?: mesh, m)
            }
            // Copia en la GPU desde este hilo: si no, la primera vez que se dibuja se sube en
            // medio de un cuadro y lo traba un cuarto de segundo.
            val gpu = bitmap.copy(Bitmap.Config.HARDWARE, false)
            if (gpu != null) bitmap.recycle()
            Band(gpu ?: bitmap, RectF(l, top, l + width / scale, bottom))
        }
        return Image(bands, scale, version)
    }

    private companion object {
        /** Lado mayor de la imagen: 3072 px son unos 25 MB. */
        const val MAX_PX = 3072f
        const val BAND_PX = 512
    }
}
