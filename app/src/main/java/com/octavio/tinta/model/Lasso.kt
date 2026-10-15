package com.octavio.tinta.model

/** Qué puede agarrar el lazo. */
data class LassoFilter(
    val pens: Boolean = true,
    val highlighters: Boolean = true,
    val images: Boolean = true,
    val texts: Boolean = true,
    /** Gráficos, figuras 3D, audios y videos. */
    val widgets: Boolean = true,
) {
    fun accepts(tool: Tool) = if (tool == Tool.HIGHLIGHTER) highlighters else pens
}

/** Un lazo cerrado en el mundo, con los vértices en pares (x, y). */
class Lasso(private val xy: FloatArray) {
    private val n = xy.size / 2
    val left: Float
    val top: Float
    val right: Float
    val bottom: Float

    init {
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        for (i in 0 until n) {
            l = minOf(l, xy[2 * i])
            r = maxOf(r, xy[2 * i])
            t = minOf(t, xy[2 * i + 1])
            b = maxOf(b, xy[2 * i + 1])
        }
        left = l
        top = t
        right = r
        bottom = b
    }

    fun intersects(l: Float, t: Float, r: Float, b: Float) = !(r < left || l > right || b < top || t > bottom)

    /** Par-impar: cuántas veces cruza los lados una semirrecta hacia la derecha. */
    fun contains(x: Float, y: Float): Boolean {
        if (n < 3 || x < left || x > right || y < top || y > bottom) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = xy[2 * i]
            val yi = xy[2 * i + 1]
            val xj = xy[2 * j]
            val yj = xy[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Agarra un trazo si quedó adentro al menos la mitad de sus puntos. */
    fun containsMostOf(points: FloatArray): Boolean {
        val count = points.size / 2
        if (count == 0) return false
        var inside = 0
        for (i in 0 until count) if (contains(points[2 * i], points[2 * i + 1])) inside++
        return 2 * inside >= count
    }
}
