package com.octavio.tinta.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Agrupa trazos escritos a mano en renglones (para reconocer la letra de a un renglón): trazos
 * cuyo centro cae a la altura de un renglón y que están cerca a lo ancho. Después parte cada
 * renglón donde hay un hueco grande (dos columnas, o algo escrito aparte al costado).
 */
object Lines {
    class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val cy get() = (top + bottom) / 2
        val height get() = bottom - top
    }

    private class Line(first: Int, box: Box) {
        val members = arrayListOf(first)
        var left = box.left
        var right = box.right
        var top = box.top
        var bottom = box.bottom
        var cy = box.cy
    }

    /**
     * Índices de `boxes` por renglón; cada renglón, de izquierda a derecha. Los trazos mucho más
     * grandes que una letra (dibujos, recuadros, flechas largas) no entran en ninguno.
     */
    fun group(boxes: List<Box>): List<List<Int>> {
        if (boxes.isEmpty()) return emptyList()
        val heights = boxes.map { it.height }.sorted()
        // Alto de una letra típica: la mediana, para que una raya larga no lo agrande.
        val letter = max(heights[heights.size / 2], MIN_LETTER)
        val order = boxes.indices.sortedBy { boxes[it].cy }
        val active = ArrayList<Line>()
        val done = ArrayList<Line>()
        for (i in order) {
            val b = boxes[i]
            if (b.height > letter * TALL || b.right - b.left > letter * WIDE) continue
            // Los renglones que ya quedaron muy arriba no reciben más trazos.
            active.removeAll { line -> (b.cy - line.cy > letter * 2).also { if (it) done.add(line) } }
            var best: Line? = null
            var bestDistance = Float.MAX_VALUE
            for (line in active) {
                val dy = abs(b.cy - line.cy)
                if (dy > letter * 0.7f) continue
                if (b.left > line.right + letter * GAP || b.right < line.left - letter * GAP) continue
                if (dy < bestDistance) {
                    bestDistance = dy
                    best = line
                }
            }
            val line = best
            if (line == null) {
                active.add(Line(i, b))
            } else {
                line.members.add(i)
                line.left = min(line.left, b.left)
                line.right = max(line.right, b.right)
                line.top = min(line.top, b.top)
                line.bottom = max(line.bottom, b.bottom)
                // El centro sigue a los trazos chicos (las letras), no a las rayas largas.
                val weight = if (b.height > letter * 2) 0.1f else 1f
                line.cy += (b.cy - line.cy) * weight / line.members.size
            }
        }
        done.addAll(active)
        val out = ArrayList<List<Int>>()
        for (line in done.sortedWith(compareBy({ it.top }, { it.left }))) {
            val sorted = line.members.sortedBy { boxes[it].left }
            var part = ArrayList<Int>()
            var right = Float.NEGATIVE_INFINITY
            for (i in sorted) {
                if (part.isNotEmpty() && boxes[i].left - right > letter * SPLIT) {
                    out.add(part)
                    part = ArrayList()
                }
                part.add(i)
                right = max(right, boxes[i].right)
            }
            if (part.isNotEmpty()) out.add(part)
        }
        return out
    }

    /** Un trazo a más de estas letras de distancia ya no es del mismo renglón. */
    private const val GAP = 6f

    /** Un hueco de más de estas letras parte el renglón. */
    private const val SPLIT = 5f
    private const val MIN_LETTER = 4f

    /** Más alto o más ancho que estas letras ya no es letra. */
    private const val TALL = 6f
    private const val WIDE = 30f
}
