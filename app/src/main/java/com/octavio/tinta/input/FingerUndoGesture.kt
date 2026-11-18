package com.octavio.tinta.input

import kotlin.math.hypot

/**
 * Toque corto con dos dedos: deshacer. Con tres: rehacer.
 * Si algún dedo se mueve más que [slopPx], o el gesto dura más que [tapMs], no es un toque:
 * queda libre el zoom, el paneo y el pase de hoja.
 */
class FingerUndoGesture(
    private val slopPx: Float,
    private val tapMs: Long,
) {
    enum class Action { UNDO, REDO }

    private class Pt(val x: Float, val y: Float)
    private val origin = HashMap<Int, Pt>()
    private var peak = 0
    private var startedAt = 0L
    private var moved = false

    /** La primera vez que se ve un dedo. Si ya estaba, no reinicia el gesto. */
    fun down(id: Int, x: Float, y: Float, timeMs: Long) {
        if (origin.containsKey(id)) return
        if (origin.isEmpty()) {
            peak = 0
            startedAt = timeMs
            moved = false
        }
        origin[id] = Pt(x, y)
        if (origin.size > peak) peak = origin.size
    }

    fun move(id: Int, x: Float, y: Float) {
        val o = origin[id] ?: return
        if (hypot(x - o.x, y - o.y) >= slopPx) moved = true
    }

    fun cancel() {
        origin.clear()
        peak = 0
        moved = false
    }

    /**
     * Un dedo se levantó. Devuelve la acción sólo cuando ya no queda ninguno y el gesto
     * fue un toque quieto de dos o tres dedos.
     */
    fun up(id: Int, timeMs: Long): Action? {
        if (!origin.containsKey(id)) return null
        origin.remove(id)
        if (origin.isNotEmpty()) return null
        val n = peak
        val dt = timeMs - startedAt
        val dirty = moved
        peak = 0
        moved = false
        if (dirty || dt > tapMs) return null
        return when (n) {
            2 -> Action.UNDO
            3 -> Action.REDO
            else -> null
        }
    }
}
