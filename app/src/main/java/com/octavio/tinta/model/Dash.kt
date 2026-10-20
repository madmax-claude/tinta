package com.octavio.tinta.model

import kotlin.math.hypot
import kotlin.math.max

/**
 * Cómo es la línea de un lápiz: continua, a rayas, de puntos o raya y punto. `id` es lo que se
 * guarda; no cambiarlo. El patrón va en múltiplos del grosor: tramo con tinta, hueco, tinta…
 */
enum class Dash(val id: Int, val label: String, private val pattern: FloatArray?) {
    SOLID(0, "Continua", null),
    DASHED(1, "Rayas", floatArrayOf(4f, 2.5f)),
    DOTTED(2, "Puntos", floatArrayOf(1f, 2.6f)),
    DASH_DOT(3, "Raya y punto", floatArrayOf(4f, 2.4f, 1f, 2.4f));

    /** El patrón en dp del mundo para un trazo de grosor `size`, o null si es continua. */
    fun lengths(size: Float): FloatArray? {
        val p = pattern ?: return null
        val unit = max(size, MIN_UNIT)
        return FloatArray(p.size) { p[it] * unit }
    }

    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: SOLID

        /** Con trazos finísimos, las rayas igual se ven separadas. */
        private const val MIN_UNIT = 1.2f

        /**
         * Los tramos con tinta del recorrido `points` según `lengths` (tinta, hueco, tinta…),
         * medidos a lo largo del trazo. Los extremos de cada tramo caen justo donde corresponde
         * (se agregan puntos intermedios); cada tramo tiene al menos dos puntos, así un punto del
         * patrón "Puntos" sale como un puntito redondo.
         */
        fun split(points: List<Cut.Point>, lengths: FloatArray): List<List<Cut.Point>> {
            if (points.size < 2 || lengths.isEmpty() || lengths.any { it <= 0f }) return listOf(points)
            val out = ArrayList<List<Cut.Point>>()
            var phase = 0
            var left = lengths[0]
            var inked = true
            var run = arrayListOf(points[0])
            for (k in 1 until points.size) {
                val a = points[k - 1]
                val b = points[k]
                val length = hypot(b.x - a.x, b.y - a.y)
                var used = 0f
                while (length - used > left) {
                    used += left
                    val p = lerp(a, b, used / length)
                    if (inked) {
                        run.add(p)
                        out.add(run)
                    } else {
                        run = arrayListOf(p)
                    }
                    inked = !inked
                    phase = (phase + 1) % lengths.size
                    left = lengths[phase]
                }
                left -= length - used
                if (inked) run.add(b)
            }
            if (inked && run.size >= 2) out.add(run)
            return out
        }

        private fun lerp(a: Cut.Point, b: Cut.Point, f: Float) = Cut.Point(
            a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.t + (b.t - a.t) * f,
            a.pressure + (b.pressure - a.pressure) * f, a.tilt + (b.tilt - a.tilt) * f,
            a.orientation + (b.orientation - a.orientation) * f,
        )
    }
}
