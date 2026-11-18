package com.octavio.tinta.canvas

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RenderNode
import android.os.Process
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import com.octavio.tinta.model.InkStroke
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * El dibujante de Ink para la pantalla. Hay uno solo, a propósito: la primera vez que dibuja un
 * trazo, Ink arma su malla de Android y la guarda en ese dibujante. En la P12 armarla cuesta
 * 0,2–0,6 ms, y con 28.000 trazos trababa el hilo de la pantalla. [warm] hace ese trabajo en
 * segundo plano; otro dibujante no serviría, porque la malla quedaría en el suyo y la pantalla
 * volvería a armarla. Compartirlo entre hilos no es seguro (tampoco lo comparte [Overview]): el
 * candado es el que lo permite.
 *
 * El candado es justo. [warm] y [draw] lo toman de a un trazo; [use] lo retiene sólo mientras
 * corre su bloque. Mientras la pantalla dibuja, el calentador no hace cola: espera afuera, así
 * que no se mete entre los trazos de una tanda ni se adelanta a la tinta fresca. Si la pantalla
 * llega en medio de un trazo que el calentador ya empezó, espera sólo ese.
 */
object InkDraw {
    private val renderer = CanvasStrokeRenderer.create()

    /** Justo: si la pantalla ya espera, entra antes que el próximo trazo del calentador. */
    private val lock = ReentrantLock(true)
    private val screenIdle = lock.newCondition()

    /**
     * Tandas de la pantalla en curso, en cualquier hilo. El calentador no dibuja mientras sea
     * mayor que cero. [screenHere] es la parte de este hilo, para anidar y para que un hilo que
     * ya es la pantalla no se espere a sí mismo.
     */
    private val screen = AtomicInteger(0)
    private val screenHere = ThreadLocal.withInitial { 0 }

    private val warmer = Executors.newSingleThreadExecutor {
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            it.run()
        }, "tinta-calentar")
    }

    /**
     * Usa el dibujante con el candado tomado durante todo [block]. Para unas pocas llamadas
     * seguidas (la tinta fresca, una selección). Una tanda grande va por [onScreen] y [draw].
     */
    inline fun <T> use(block: (CanvasStrokeRenderer) -> T): T = lock().let { r ->
        try {
            block(r)
        } finally {
            unlock()
        }
    }

    fun lock(): CanvasStrokeRenderer {
        acquireScreen()
        lock.lock()
        return renderer
    }

    fun unlock() {
        try {
            releaseScreenLocked()
        } finally {
            lock.unlock()
        }
    }

    /**
     * La pantalla va a dibujar varios trazos. El calentador espera hasta que termina, pero el
     * candado se toma sólo en [draw]: no queda tomado los ~3 ms de grabar una tanda.
     */
    fun <T> onScreen(block: () -> T): T {
        acquireScreen()
        try {
            return block()
        } finally {
            lock.lock()
            try {
                releaseScreenLocked()
            } finally {
                lock.unlock()
            }
        }
    }

    /** Un trazo. El candado dura esta llamada, no la tanda. */
    fun draw(canvas: Canvas, stroke: Stroke, transform: Matrix) {
        lock.lock()
        try {
            renderer.draw(canvas, stroke, transform)
        } finally {
            lock.unlock()
        }
    }

    private fun depth(): Int = screenHere.get() ?: 0

    private fun acquireScreen() {
        val n = depth()
        screenHere.set(n + 1)
        if (n == 0) screen.incrementAndGet()
    }

    /** El candado ya está tomado. */
    private fun releaseScreenLocked() {
        val n = depth() - 1
        screenHere.set(n)
        if (n == 0 && screen.decrementAndGet() == 0) screenIdle.signalAll()
    }

    /**
     * Arma en segundo plano las mallas de Android de estos trazos, dibujándolos en una grabación
     * que se tira. Toma el candado de a un trazo y, si la pantalla lo quiere, espera sin quedarse
     * en la cola.
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
            try {
                val end = minOf(i + BATCH, strokes.size)
                while (i < end) {
                    val s = strokes[i++].stroke ?: continue
                    if (!drawOffScreen(canvas, s, m, cancelled)) return
                }
            } finally {
                scratch.endRecording()
                scratch.discardDisplayList()
            }
        }
    }

    /**
     * Dibuja un trazo del calentador. Si este hilo ya es la pantalla, dibuja nomás (el candado
     * se reentra). Si no, no entra mientras [screen] sea mayor que cero.
     */
    private fun drawOffScreen(
        canvas: Canvas,
        stroke: Stroke,
        transform: Matrix,
        cancelled: () -> Boolean,
    ): Boolean {
        if (depth() > 0) {
            lock.lock()
            try {
                renderer.draw(canvas, stroke, transform)
            } finally {
                lock.unlock()
            }
            return !cancelled()
        }
        while (!cancelled()) {
            if (screen.get() > 0) {
                if (!waitScreen(cancelled)) return false
                continue
            }
            lock.lock()
            try {
                if (cancelled()) return false
                // La pantalla pudo haber pedido el dibujante entre el chequeo y el candado.
                if (screen.get() > 0) continue
                renderer.draw(canvas, stroke, transform)
                return true
            } finally {
                lock.unlock()
            }
        }
        return false
    }

    /** Espera, soltando el candado, a que la pantalla termine. Falso si hay que cancelar. */
    private fun waitScreen(cancelled: () -> Boolean): Boolean {
        lock.lock()
        try {
            while (screen.get() > 0 && !cancelled()) screenIdle.awaitNanos(WAIT_SLICE_NS)
            return !cancelled()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } finally {
            lock.unlock()
        }
    }

    private const val BATCH = 32

    /** Por si nadie avisa: se vuelve a mirar la cancelación. */
    private const val WAIT_SLICE_NS = 2_000_000L
}
