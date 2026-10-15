package com.octavio.tinta.canvas

import android.graphics.Matrix
import android.graphics.RenderNode
import android.os.Process
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.octavio.tinta.model.InkStroke
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * El dibujante de Ink para la pantalla, compartido entre hilos con un candado (Ink lo permite si
 * se sincroniza). La primera vez que dibuja un trazo, Ink arma su malla de Android para la GPU:
 * en la P12 eso cuesta 0,2–0,6 ms por trazo, y con 28.000 trazos trababa el hilo de la pantalla
 * durante los primeros gestos. [warm] hace ese trabajo en segundo plano apenas se arman las mallas,
 * y cuando la pantalla graba una tanda ya está todo listo.
 */
object InkDraw {
    private val renderer = CanvasStrokeRenderer.create()

    /** Justo: la pantalla espera a lo sumo un trazo de [warm], nunca toda la cola. */
    private val lock = ReentrantLock(true)

    private val warmer = Executors.newSingleThreadExecutor {
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            it.run()
        }, "tinta-calentar")
    }

    /** Usa el dibujante con el candado tomado; para grabar una tanda entera de una vez. */
    inline fun <T> use(block: (CanvasStrokeRenderer) -> T): T = lock().let { r ->
        try {
            block(r)
        } finally {
            unlock()
        }
    }

    fun lock(): CanvasStrokeRenderer {
        lock.lock()
        return renderer
    }

    fun unlock() = lock.unlock()

    /**
     * Arma en segundo plano las mallas de Android de estos trazos, dibujándolos en una grabación
     * que se tira. Toma el candado de a un trazo para no hacer esperar a la pantalla.
     */
    fun warm(strokes: List<InkStroke>, cancelled: () -> Boolean) {
        warmer.execute { warmNow(strokes, cancelled) }
    }

    /** Lo mismo que [warm] pero en este hilo, que no puede ser el de la pantalla. */
    fun warmNow(strokes: List<InkStroke>, cancelled: () -> Boolean = { false }) {
        val scratch = RenderNode("tinta-calentar")
        val m = Matrix()
        var i = 0
        while (i < strokes.size && !cancelled()) {
            val canvas = scratch.beginRecording(1, 1)
            val end = minOf(i + BATCH, strokes.size)
            while (i < end) {
                val s = strokes[i++].stroke ?: continue
                lock.withLock { renderer.draw(canvas, s, m) }
            }
            scratch.endRecording()
            scratch.discardDisplayList()
        }
    }

    private const val BATCH = 32
}
