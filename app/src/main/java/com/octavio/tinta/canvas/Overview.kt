package com.octavio.tinta.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.octavio.tinta.model.InkStroke
import com.octavio.tinta.model.paintInk
import com.octavio.tinta.model.Work
import java.io.ByteArrayOutputStream
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

    /** PNG de una franja, para guardarla al lado de la nota. La caja está en el mundo. */
    class SavedBand(val left: Float, val top: Float, val right: Float, val bottom: Float, val png: ByteArray)

    /** Lo último que se armó (con los trazos de los que salió); se reemplaza entero al terminar otra versión. */
    private class Image(val bands: List<Band>, val scale: Float, val version: Int, val source: List<InkStroke>)

    private class Job(val strokes: List<InkStroke>, val version: Int, val dark: Boolean, val modified: Long, val count: Int)

    @Volatile private var image: Image? = null

    /**
     * Al terminar una imagen, en el hilo de la vista: escala, versión, modo, `modified` y cantidad
     * de trazos del pedido, y los PNG. Quien guarda la foto decide si ese pedido sigue vigente.
     */
    var onSnapshot: ((scale: Float, version: Int, dark: Boolean, modified: Long, count: Int, bands: List<SavedBand>) -> Unit)? = null

    /** Último pedido; si llegan varios mientras se arma una imagen, sólo se arma el más nuevo. */
    private var wanted: Job? = null
    private var running = false
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "tinta-vista").apply { priority = Thread.MIN_PRIORITY } }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Cambia con [clear]: lo que se estaba armando para antes ya no sirve. */
    @Volatile private var generation = 0

    /** Px de la imagen por dp del mundo, o 0 si no hay imagen. */
    val scale get() = image?.scale ?: 0f

    val hasImage get() = image != null

    fun isCurrent(version: Int) = image?.version == version || synchronized(lock) { wanted?.version == version }

    /**
     * Arma la imagen de `strokes` (una copia) si no está ya hecha o pedida para `version`.
     * `modified` y `count` viajan con el pedido para nombrar la foto si se guarda.
     */
    fun request(strokes: List<InkStroke>, version: Int, dark: Boolean, modified: Long = 0L, count: Int = strokes.size) {
        if (isCurrent(version)) return
        synchronized(lock) {
            wanted = Job(strokes, version, dark, modified, count)
            if (running) return
            running = true
        }
        worker.execute {
            while (true) {
                val gen = generation
                val job = synchronized(lock) {
                    val w = wanted
                    wanted = null
                    if (w == null) running = false
                    w
                } ?: break
                val built = runCatching { build(job.strokes, job.version, job.dark) }.getOrNull()
                if (built == null) continue
                if (gen != generation) {
                    built.image.bands.forEach { it.bitmap.recycle() }
                    continue
                }
                image = built.image
                onSnapshot?.invoke(built.image.scale, job.version, job.dark, job.modified, job.count, built.saved)
                onReady()
            }
        }
    }

    /**
     * Usa franjas que ya están (la foto guardada de la nota). `source` son los trazos vivos, para
     * que [shows] los reconozca. Lo que se estaba armando deja de valer.
     */
    fun adopt(parts: List<Pair<Bitmap, RectF>>, scale: Float, source: List<InkStroke>, version: Int) {
        val bands = parts.map { Band(it.first, it.second) }
        synchronized(lock) {
            generation++
            wanted = null
            image = Image(bands, scale, version, source)
        }
        onReady()
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

    /**
     * Si todo lo que muestra la imagen sigue en la nota (identidad de los trazos). Con algo
     * borrado o movido después de armarla, debajo se vería lo viejo junto a lo nuevo: entonces no
     * se usa hasta que se rehaga. Que falten trazos nuevos no molesta: llegan con las baldosas.
     */
    fun shows(current: List<InkStroke>): Boolean {
        val img = image ?: return false
        if (img.source.size > current.size) return false
        val now = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<InkStroke, Boolean>(current.size * 2))
        now.addAll(current)
        return img.source.all { it in now }
    }

    /** Cambia con cada imagen nueva (para no volver a revisar [shows] en cada cuadro). */
    val stamp: Any? get() = image

    private class Built(val image: Image, val saved: List<SavedBand>)

    private fun build(strokes: List<InkStroke>, version: Int, dark: Boolean): Built? {
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
            paintInk(strokes) { s ->
                if (!s.intersects(l, top, r, bottom)) return@paintInk
                val mesh = s.build() ?: return@paintInk
                renderer.draw(canvas, s.display(dark) ?: mesh, m)
            }
            val rect = RectF(l, top, l + width / scale, bottom)
            // El PNG sale del bitmap de software: el de la GPU no se comprime.
            val png = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)
            // Copia en la GPU desde este hilo: si no, la primera vez que se dibuja se sube en
            // medio de un cuadro y lo traba un cuarto de segundo.
            val gpu = bitmap.copy(Bitmap.Config.HARDWARE, false)
            if (gpu != null) bitmap.recycle()
            Pair(Band(gpu ?: bitmap, rect), SavedBand(rect.left, rect.top, rect.right, rect.bottom, png.toByteArray()))
        }
        return Built(Image(bands.map { it.first }, scale, version, strokes), bands.map { it.second })
    }

    private companion object {
        /** Lado mayor de la imagen: 3072 px son unos 25 MB. */
        const val MAX_PX = 3072f
        const val BAND_PX = 512
    }
}
