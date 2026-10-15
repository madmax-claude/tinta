package com.octavio.tinta.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.octavio.tinta.export.Render
import com.octavio.tinta.model.Background
import com.octavio.tinta.model.InkStroke
import com.octavio.tinta.model.NoteImage
import com.octavio.tinta.model.NoteText
import com.octavio.tinta.model.PageLayout
import com.octavio.tinta.model.Widget
import com.octavio.tinta.canvas.Elements
import java.util.concurrent.Executors

/**
 * Las miniaturas de las hojas de la nota abierta. Se dibujan en segundo plano y sólo cuando cambió
 * lo que hay en esa hoja (una "firma" con lo que cae en cada hoja). Lo que se dibuja es una copia
 * de las listas de la nota tomada en el hilo de la pantalla ([update]).
 */
class PageThumbs(private val widthPx: Int) {
    class Snapshot(
        val strokes: List<InkStroke>,
        val images: List<NoteImage>,
        val texts: List<NoteText>,
        val widgets: List<Widget>,
        val background: Background,
        val pages: PageLayout,
    )

    /** Avisa (en el hilo de la pantalla) que está lista la miniatura de la hoja `k`. */
    var onReady: ((Int) -> Unit)? = null

    private class Thumb(val signature: Int, val bitmap: Bitmap)

    private val lock = Any()
    private val cache = object : LinkedHashMap<Int, Thumb>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Thumb>?) = size > MAX_THUMBS
    }
    private var snapshot: Snapshot? = null
    private var signatures = IntArray(0)

    /** La firma de una hoja vacía (las de más allá de lo escrito). */
    private var blank: Int? = null

    private fun signatureOf(k: Int) = signatures.getOrNull(k) ?: blank
    private val queued = HashSet<Int>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor {
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            it.run()
        }, "tinta-miniaturas")
    }

    /** Nuevas listas de la nota (después de un cambio): se recalculan las firmas. */
    fun update(s: Snapshot) {
        worker.execute {
            val sig = signatures(s)
            synchronized(lock) {
                snapshot = s
                signatures = sig
                blank = base(s)
            }
            // Las que ya se pidieron y quedaron viejas, se vuelven a dibujar. Sin cache[k] en el
            // recorrido: el caché está ordenado por uso y leerlo lo reordena (y el recorrido falla).
            val stale = synchronized(lock) { cache.entries.filter { (k, t) -> t.signature != signatureOf(k) }.map { it.key } }
            main.post { stale.forEach { onReady?.invoke(it) } }
        }
    }

    /** La miniatura si ya está (aunque esté vieja); si falta o está vieja, se pide. */
    fun get(k: Int): Bitmap? {
        val (thumb, current) = synchronized(lock) { cache[k] to signatureOf(k) }
        if (thumb == null || thumb.signature != current) request(k)
        return thumb?.bitmap
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
            snapshot = null
            signatures = IntArray(0)
            blank = null
        }
    }

    private fun request(k: Int) {
        synchronized(lock) { if (!queued.add(k)) return }
        worker.execute {
            val (s, sig) = synchronized(lock) {
                queued.remove(k)
                snapshot to signatureOf(k)
            }
            if (s == null || sig == null) return@execute
            if (synchronized(lock) { cache[k]?.signature } == sig) return@execute
            val bitmap = runCatching { Render.page(s.strokes, s.images, s.texts, s.widgets, s.background, s.pages, k, widthPx) }.getOrNull()
                ?: return@execute
            synchronized(lock) { cache[k] = Thumb(sig, bitmap) }
            main.post { onReady?.invoke(k) }
        }
    }

    /** Por cada hoja, un número que cambia si cambia lo que hay en ella. */
    private fun signatures(s: Snapshot): IntArray {
        val p = s.pages
        var last = 0
        val pageOf = { cy: Float -> p.pageAt(cy) }
        val all = ArrayList<Pair<Int, Int>>(s.strokes.size + s.images.size + s.texts.size + s.widgets.size)
        for (st in s.strokes) if (!st.top.isNaN()) all.add(pageOf((st.top + st.bottom) / 2) to System.identityHashCode(st))
        for (i in s.images) all.add(pageOf(i.centerY) to System.identityHashCode(i))
        for (t in s.texts) all.add(pageOf(Elements.textBounds(t).centerY()) to System.identityHashCode(t))
        for (w in s.widgets) all.add(pageOf(w.centerY) to System.identityHashCode(w))
        for ((k, _) in all) if (k > last) last = k
        val base = base(s)
        val sig = IntArray(last + 2) { base }
        for ((k, h) in all) {
            if (k < 0) continue
            sig[k] = sig[k] * 31 + h
        }
        return sig
    }

    private fun base(s: Snapshot) = s.background.id * 7919 + s.pages.hashCode()

    private companion object {
        const val MAX_THUMBS = 60
    }
}
