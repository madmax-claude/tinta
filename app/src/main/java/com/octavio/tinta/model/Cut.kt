package com.octavio.tinta.model

import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Lo que queda de un trazo cuando la goma pasa por encima: las partes del recorrido que caen fuera
 * de la "cápsula" que barre la goma (el segmento de a a b, engordado `r`). Sin Ink: se arma la
 * malla de cada parte afuera.
 */
object Cut {
    /** Un punto del lápiz con todo lo que guarda Ink (los que no tiene, con el valor "sin dato"). */
    data class Point(val x: Float, val y: Float, val t: Float, val pressure: Float, val tilt: Float, val orientation: Float)

    /**
     * Las partes de afuera, en orden, o null si la goma no toca ningún punto (el trazo queda como
     * estaba). Antes se agregan puntos intermedios cada `step` como mucho, para que una goma chica
     * corte bien un tramo largo entre dos puntos. Las partes de un solo punto se descartan.
     */
    fun outside(points: List<Point>, ax: Float, ay: Float, bx: Float, by: Float, r: Float, step: Float): List<List<Point>>? {
        if (points.isEmpty()) return null
        val dense = densify(points, step)
        val inside = BooleanArray(dense.size) { distance(dense[it].x, dense[it].y, ax, ay, bx, by) < r }
        if (inside.none { it }) return null
        val parts = ArrayList<List<Point>>()
        var run = ArrayList<Point>()
        for (k in dense.indices) {
            if (inside[k]) {
                if (run.size >= 2) parts.add(run)
                run = ArrayList()
            } else {
                run.add(dense[k])
            }
        }
        if (run.size >= 2) parts.add(run)
        return parts
    }

    /**
     * Las partes que caen dentro del rectángulo (l, t, r, b), en orden: lo que queda de un trazo en
     * una hoja si no se puede escribir fuera de ella. Null si está todo adentro (queda como estaba);
     * vacía si está todo afuera. Las partes de un solo punto se descartan.
     */
    fun inside(points: List<Point>, l: Float, t: Float, r: Float, b: Float, step: Float): List<List<Point>>? {
        if (points.isEmpty()) return null
        fun isIn(p: Point) = p.x >= l && p.x <= r && p.y >= t && p.y <= b
        if (points.all(::isIn)) return null
        val parts = ArrayList<List<Point>>()
        var run = ArrayList<Point>()
        for (p in densify(points, step)) {
            if (isIn(p)) {
                run.add(p)
            } else {
                if (run.size >= 2) parts.add(run)
                run = ArrayList()
            }
        }
        if (run.size >= 2) parts.add(run)
        return parts
    }

    fun densify(points: List<Point>, step: Float): List<Point> {
        val out = ArrayList<Point>(points.size * 2)
        out.add(points[0])
        for (k in 1 until points.size) {
            val a = points[k - 1]
            val b = points[k]
            val n = ceil(hypot(b.x - a.x, b.y - a.y) / step).toInt()
            for (j in 1 until n) {
                val f = j.toFloat() / n
                out.add(
                    Point(
                        a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.t + (b.t - a.t) * f,
                        a.pressure + (b.pressure - a.pressure) * f, a.tilt + (b.tilt - a.tilt) * f,
                        a.orientation + (b.orientation - a.orientation) * f,
                    ),
                )
            }
            out.add(b)
        }
        return out
    }

    /** Distancia de (px, py) al segmento de a a b (si a = b, al punto). */
    fun distance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
