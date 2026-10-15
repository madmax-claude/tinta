package com.octavio.tinta.model

import com.octavio.tinta.canvas.InkDraw
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Arma las mallas de una nota en segundo plano, primero las más cercanas al punto que se está
 * mirando y en tandas, con los hilos de [Work]. Después de cada tanda avisa para redibujar. (Las
 * imágenes se decodifican cuando aparecen en la pantalla, en [com.octavio.tinta.canvas.ImageCache].)
 */
class MeshBuilder(
    strokes: List<InkStroke>,
    centerX: Float,
    centerY: Float,
    private val onChunk: () -> Unit,
) {
    private val pending = strokes.filter { !it.isBuilt }.sortedBy {
        val dx = (it.left + it.right) / 2 - centerX
        val dy = (it.top + it.bottom) / 2 - centerY
        if (dx.isNaN()) Float.MAX_VALUE else dx * dx + dy * dy
    }
    private val cancelled = AtomicBoolean(false)

    fun start() {
        if (pending.isEmpty()) return
        Thread({
            val t0 = System.nanoTime()
            var from = 0
            var chunk = FIRST_CHUNK
            while (from < pending.size && !cancelled.get()) {
                val to = minOf(from + chunk, pending.size)
                val batch = pending.subList(from, to)
                Work.each(batch) { it.build() }
                InkDraw.warm(batch) { cancelled.get() }
                onChunk()
                from = to
                chunk = CHUNK
            }
            android.util.Log.i("Tinta", "mallas: $from trazos en ${(System.nanoTime() - t0) / 1_000_000} ms")
        }, "tinta-mallas").start()
    }

    fun cancel() = cancelled.set(true)

    private companion object {
        /** Lo que está a la vista aparece enseguida; el resto, en tandas más grandes. */
        const val FIRST_CHUNK = 300
        const val CHUNK = 1500
    }
}
