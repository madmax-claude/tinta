package com.octavio.tinta.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reconoce la figura que se quiso dibujar a mano: una recta, una poligonal abierta (una raíz, un
 * ángulo, un zigzag), un triángulo, un rectángulo u otro polígono, un círculo o una elipse. Los
 * puntos van en pares (x, y) en el mundo; `unit` es cuánto mide un dp de pantalla en el mundo, para
 * que las tolerancias no dependan del zoom.
 */
object Shapes {

    sealed interface Shape {
        /** Puntos (x, y) a lo largo de la figura, separados a lo sumo `step`. */
        fun sample(step: Float): FloatArray

        /**
         * La figura siguiendo al lápiz, que estaba en (fromX, fromY) cuando se reconoció y ahora
         * está en (x, y): una figura abierta mueve su último vértice; una cerrada se agranda o
         * achica alrededor de su centro.
         */
        fun follow(fromX: Float, fromY: Float, x: Float, y: Float): Shape
    }

    /** Vértices en pares (x, y). Cerrada: el último lado vuelve al primero. */
    class Polyline(val xy: FloatArray, val closed: Boolean) : Shape {
        val vertices get() = xy.size / 2

        override fun sample(step: Float): FloatArray {
            val out = ArrayList<Float>()
            val n = vertices
            val sides = if (closed) n else n - 1
            for (i in 0 until sides) {
                val j = (i + 1) % n
                val ax = xy[2 * i]
                val ay = xy[2 * i + 1]
                val bx = xy[2 * j]
                val by = xy[2 * j + 1]
                val k = ceil(hypot(bx - ax, by - ay) / step).toInt().coerceAtLeast(1)
                for (t in 0 until k) {
                    out.add(ax + (bx - ax) * t / k)
                    out.add(ay + (by - ay) * t / k)
                }
            }
            val last = if (closed) 0 else n - 1
            out.add(xy[2 * last])
            out.add(xy[2 * last + 1])
            return out.toFloatArray()
        }

        override fun follow(fromX: Float, fromY: Float, x: Float, y: Float): Shape {
            if (closed) return scaled(this, fromX, fromY, x, y)
            val moved = xy.copyOf()
            moved[moved.size - 2] = x
            moved[moved.size - 1] = y
            snapSide(moved, vertices - 2)
            return Polyline(moved, false)
        }

        fun center(): Pair<Float, Float> {
            var sx = 0f
            var sy = 0f
            for (i in 0 until vertices) {
                sx += xy[2 * i]
                sy += xy[2 * i + 1]
            }
            return sx / vertices to sy / vertices
        }
    }

    /** Elipse de centro (cx, cy), semiejes rx y ry, girada `angle` radianes; empieza en `start`. */
    class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val angle: Float, val start: Float) : Shape {
        val isCircle get() = rx == ry

        override fun sample(step: Float): FloatArray {
            // Perímetro de Ramanujan.
            val h = ((rx - ry) / (rx + ry)).let { it * it }
            val perimeter = PI.toFloat() * (rx + ry) * (1 + 3 * h / (10 + sqrt(4 - 3 * h)))
            val n = ceil(perimeter / step).toInt().coerceAtLeast(24)
            val out = FloatArray(2 * (n + 1))
            val c = cos(angle)
            val s = sin(angle)
            for (i in 0..n) {
                val t = start + 2 * PI.toFloat() * i / n
                val u = rx * cos(t)
                val v = ry * sin(t)
                out[2 * i] = cx + u * c - v * s
                out[2 * i + 1] = cy + u * s + v * c
            }
            return out
        }

        override fun follow(fromX: Float, fromY: Float, x: Float, y: Float): Shape {
            val f = factor(cx, cy, fromX, fromY, x, y)
            return Ellipse(cx, cy, rx * f, ry * f, angle, start)
        }
    }

    /**
     * La figura más parecida, o null si el trazo no se parece a ninguna (una curva cualquiera,
     * letras): en ese caso queda como se dibujó.
     */
    fun recognize(points: FloatArray, unit: Float): Shape? {
        if (points.size < 4) return null
        val length = length(points)
        if (length < MIN_LENGTH * unit) return null
        val r = resample(points, SAMPLES)
        val diag = diagonal(r)
        val closeGap = max(CLOSE_GAP * diag, 8 * unit)
        val closedEnd = closingIndex(r, closeGap)
        if (closedEnd > 0 && length > CLOSED_LENGTH * diag) {
            closed(resample(r.copyOf(2 * (closedEnd + 1)), SAMPLES), unit)?.let { return it }
        }
        return open(r, unit)
    }

    // --- Abiertas ---

    private fun open(r: FloatArray, unit: Float): Shape? {
        val n = r.size / 2
        val idx = corners(r, 0, n - 1, unit)
        // Ganchitos al apoyar o levantar el lápiz.
        while (idx.size > 2 && dist(r, idx[0], idx[1]) < HOOK * unit) idx.removeAt(0)
        while (idx.size > 2 && dist(r, idx[idx.size - 2], idx.last()) < HOOK * unit) idx.removeAt(idx.lastIndex)
        mergeFlat(r, idx, unit)
        for (j in 1 until idx.size - 1) {
            if (turn(r, idx[j - 1], idx[j], idx[j + 1]) < CORNER_DEGREES) return null // es una curva
        }
        if (idx.size - 1 > MAX_OPEN_SIDES) return null
        val xy = FloatArray(2 * idx.size)
        idx.forEachIndexed { k, i ->
            xy[2 * k] = r[2 * i]
            xy[2 * k + 1] = r[2 * i + 1]
        }
        for (side in 0 until idx.size - 1) snapSide(xy, side)
        return Polyline(xy, closed = false)
    }

    // --- Cerradas ---

    private fun closed(c: FloatArray, unit: Float): Shape? {
        val n = c.size / 2 - 1 // el último punto repite casi el primero
        var cx = 0f
        var cy = 0f
        for (i in 0 until n) {
            cx += c[2 * i]
            cy += c[2 * i + 1]
        }
        cx /= n
        cy /= n

        val ellipse = fitEllipse(c, n, cx, cy)
        val polygon = fitPolygon(c, n, cx, cy, unit)
        val sides = polygon?.vertices ?: 0
        return when {
            polygon != null && sides <= 4 -> polygon
            polygon != null && ellipse != null && ellipse.second < ROUND_ENOUGH -> ellipse.first
            polygon != null -> polygon
            ellipse != null && ellipse.second < ELLIPSE_ERROR -> ellipse.first
            else -> null
        }
    }

    /** La elipse por los momentos de segundo orden, y su error relativo medio. */
    private fun fitEllipse(c: FloatArray, n: Int, cx: Float, cy: Float): Pair<Ellipse, Float>? {
        var sxx = 0f
        var syy = 0f
        var sxy = 0f
        for (i in 0 until n) {
            val dx = c[2 * i] - cx
            val dy = c[2 * i + 1] - cy
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        sxx /= n
        syy /= n
        sxy /= n
        val mean = (sxx + syy) / 2
        val spread = sqrt(((sxx - syy) / 2) * ((sxx - syy) / 2) + sxy * sxy)
        var a = sqrt(2 * (mean + spread))
        var b = sqrt(2 * max(mean - spread, 0f))
        if (a <= 0f || b <= 0f) return null
        var angle = 0.5f * atan2(2 * sxy, sxx - syy)
        val cosA = cos(angle)
        val sinA = sin(angle)
        // En los ejes de la elipse, mínimos cuadrados de A·u² + B·v² = 1 (con A = 1/a², B = 1/b²):
        // los momentos solos achican el eje mayor cuando los puntos están repartidos a lo largo.
        var suu = 0.0
        var suv = 0.0
        var svv = 0.0
        var su = 0.0
        var sv = 0.0
        for (i in 0 until n) {
            val dx = c[2 * i] - cx
            val dy = c[2 * i + 1] - cy
            val u2 = ((dx * cosA + dy * sinA) * (dx * cosA + dy * sinA)).toDouble()
            val v2 = ((-dx * sinA + dy * cosA) * (-dx * sinA + dy * cosA)).toDouble()
            suu += u2 * u2
            suv += u2 * v2
            svv += v2 * v2
            su += u2
            sv += v2
        }
        val det = suu * svv - suv * suv
        if (det > 0) {
            val inverseA = (su * svv - sv * suv) / det
            val inverseB = (sv * suu - su * suv) / det
            if (inverseA > 0 && inverseB > 0) {
                a = (1 / sqrt(inverseA)).toFloat()
                b = (1 / sqrt(inverseB)).toFloat()
            }
        }
        val rho = FloatArray(n)
        var avg = 0f
        for (i in 0 until n) {
            val dx = c[2 * i] - cx
            val dy = c[2 * i + 1] - cy
            val u = dx * cosA + dy * sinA
            val v = -dx * sinA + dy * cosA
            rho[i] = sqrt((u / a) * (u / a) + (v / b) * (v / b))
            avg += rho[i]
        }
        avg /= n
        var error = 0f
        for (x in rho) error += abs(x / avg - 1)
        error /= n
        a *= avg
        b *= avg
        if (b > a) {
            // El ajuste puede dar vuelta cuál eje es el mayor.
            val t = a
            a = b
            b = t
            angle += (PI / 2).toFloat()
        }
        // Dónde empezó el trazo, para que la figura empiece ahí.
        val sx = c[0] - cx
        val sy = c[1] - cy
        if (a / b < CIRCLE_RATIO) {
            val radius = (a + b) / 2
            return Ellipse(cx, cy, radius, radius, 0f, atan2(sy, sx)) to error
        }
        angle = snapAngle(angle, ELLIPSE_SNAP_DEGREES)
        val u = sx * cos(angle) + sy * sin(angle)
        val v = -sx * sin(angle) + sy * cos(angle)
        return Ellipse(cx, cy, a, b, angle, atan2(v / b, u / a)) to error
    }

    private fun fitPolygon(c: FloatArray, n: Int, cx: Float, cy: Float, unit: Float): Polyline? {
        // Se empieza por el punto más lejano del centro, que seguro es una esquina.
        var far = 0
        var best = -1f
        for (i in 0 until n) {
            val d = hypot(c[2 * i] - cx, c[2 * i + 1] - cy)
            if (d > best) {
                best = d
                far = i
            }
        }
        val p = FloatArray(2 * (n + 1))
        for (k in 0..n) {
            val i = (far + k) % n
            p[2 * k] = c[2 * i]
            p[2 * k + 1] = c[2 * i + 1]
        }
        val idx = corners(p, 0, n, unit)
        mergeFlat(p, idx, unit)
        val sides = idx.size - 1
        if (sides !in 3..MAX_POLYGON_SIDES) return null
        val turns = FloatArray(sides) { j ->
            val prev = if (j == 0) idx[sides - 1] else idx[j - 1]
            turn(p, prev, idx[j], idx[j + 1])
        }
        if (turns.any { it < CORNER_DEGREES }) return null
        val xy = FloatArray(2 * sides)
        for (j in 0 until sides) {
            xy[2 * j] = p[2 * idx[j]]
            xy[2 * j + 1] = p[2 * idx[j] + 1]
        }
        if (sides == 4 && turns.all { abs(it - 90f) < RIGHT_ANGLE_TOLERANCE }) return rectangle(xy)
        for (side in 0 until sides - 1) snapSide(xy, side)
        return Polyline(xy, closed = true)
    }

    /** Un cuadrilátero casi recto pasa a rectángulo, con los lados del dibujado promediados. */
    private fun rectangle(q: FloatArray): Polyline {
        var longest = 0
        var longestLength = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val l = hypot(q[2 * j] - q[2 * i], q[2 * j + 1] - q[2 * i + 1])
            if (l > longestLength) {
                longestLength = l
                longest = i
            }
        }
        val j = (longest + 1) % 4
        var angle = atan2(q[2 * j + 1] - q[2 * longest + 1], q[2 * j] - q[2 * longest])
        angle = snapAngle(angle, RECT_SNAP_DEGREES)
        val c = cos(angle)
        val s = sin(angle)
        val us = FloatArray(4) { q[2 * it] * c + q[2 * it + 1] * s }
        val vs = FloatArray(4) { -q[2 * it] * s + q[2 * it + 1] * c }
        val su = us.sortedArray()
        val sv = vs.sortedArray()
        val u0 = (su[0] + su[1]) / 2
        val u1 = (su[2] + su[3]) / 2
        val v0 = (sv[0] + sv[1]) / 2
        val v1 = (sv[2] + sv[3]) / 2
        val corners = listOf(u0 to v0, u1 to v0, u1 to v1, u0 to v1)
        val xy = FloatArray(8)
        corners.forEachIndexed { k, (u, v) ->
            xy[2 * k] = u * c - v * s
            xy[2 * k + 1] = u * s + v * c
        }
        return Polyline(xy, closed = true)
    }

    // --- Herramientas ---

    /**
     * Índices de las esquinas entre `from` y `to` (incluidos): se parte el tramo por el punto más
     * alejado de la cuerda mientras ese punto se aleje más que la tolerancia del tramo.
     */
    private fun corners(p: FloatArray, from: Int, to: Int, unit: Float): MutableList<Int> {
        val out = mutableListOf(from)
        split(p, from, to, unit, out)
        out.add(to)
        return out
    }

    private fun split(p: FloatArray, from: Int, to: Int, unit: Float, out: MutableList<Int>) {
        if (to - from < 2) return
        val (far, d) = farthest(p, from, to)
        if (d <= tolerance(p, from, to, unit)) return
        split(p, from, far, unit, out)
        out.add(far)
        split(p, far, to, unit, out)
    }

    private fun farthest(p: FloatArray, from: Int, to: Int): Pair<Int, Float> {
        var best = from
        var max = -1f
        for (i in from + 1 until to) {
            val d = segmentDistance(p[2 * i], p[2 * i + 1], p[2 * from], p[2 * from + 1], p[2 * to], p[2 * to + 1])
            if (d > max) {
                max = d
                best = i
            }
        }
        return best to max
    }

    private fun tolerance(p: FloatArray, from: Int, to: Int, unit: Float) =
        max(STRAIGHT * dist(p, from, to), STRAIGHT_MIN * unit)

    /** Saca las esquinas casi planas si el tramo que queda sigue siendo recto. */
    private fun mergeFlat(p: FloatArray, idx: MutableList<Int>, unit: Float) {
        var changed = true
        while (changed) {
            changed = false
            for (j in 1 until idx.size - 1) {
                if (turn(p, idx[j - 1], idx[j], idx[j + 1]) >= FLAT_DEGREES) continue
                if (farthest(p, idx[j - 1], idx[j + 1]).second > 1.3f * tolerance(p, idx[j - 1], idx[j + 1], unit)) continue
                idx.removeAt(j)
                changed = true
                break
            }
        }
    }

    /** Cuánto gira (grados) el recorrido en b, de la dirección a→b a la b→c. */
    private fun turn(p: FloatArray, a: Int, b: Int, c: Int): Float {
        val ux = p[2 * b] - p[2 * a]
        val uy = p[2 * b + 1] - p[2 * a + 1]
        val vx = p[2 * c] - p[2 * b]
        val vy = p[2 * c + 1] - p[2 * b + 1]
        val nu = hypot(ux, uy)
        val nv = hypot(vx, vy)
        if (nu == 0f || nv == 0f) return 0f
        val cos = ((ux * vx + uy * vy) / (nu * nv)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }

    /** Un lado casi horizontal o vertical se endereza moviendo su segundo vértice. */
    private fun snapSide(xy: FloatArray, side: Int) {
        if (side < 0) return
        val i = 2 * side
        val dx = xy[i + 2] - xy[i]
        val dy = xy[i + 3] - xy[i + 1]
        if (hypot(dx, dy) == 0f) return
        val deg = Math.toDegrees(atan2(abs(dy), abs(dx)).toDouble())
        if (deg < SNAP_DEGREES) xy[i + 3] = xy[i + 1]
        if (deg > 90 - SNAP_DEGREES) xy[i + 2] = xy[i]
    }

    private fun snapAngle(angle: Float, degrees: Float): Float {
        val quarter = (PI / 2).toFloat()
        val nearest = Math.round(angle / quarter) * quarter
        return if (abs(angle - nearest) < Math.toRadians(degrees.toDouble())) nearest else angle
    }

    private fun scaled(p: Polyline, fromX: Float, fromY: Float, x: Float, y: Float): Polyline {
        val (cx, cy) = p.center()
        val f = factor(cx, cy, fromX, fromY, x, y)
        val xy = FloatArray(p.xy.size) { i -> if (i % 2 == 0) cx + (p.xy[i] - cx) * f else cy + (p.xy[i] - cy) * f }
        return Polyline(xy, p.closed)
    }

    private fun factor(cx: Float, cy: Float, fromX: Float, fromY: Float, x: Float, y: Float): Float {
        val d0 = hypot(fromX - cx, fromY - cy)
        if (d0 < 1f) return 1f
        return (hypot(x - cx, y - cy) / d0).coerceIn(0.1f, 10f)
    }

    /**
     * Si el trazo vuelve a su comienzo: el índice donde termina la vuelta (antes de pasarse), o
     * -1 si queda abierto.
     */
    private fun closingIndex(r: FloatArray, gap: Float): Int {
        val n = r.size / 2
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (i in (n * 6 / 10) until n) {
            val d = hypot(r[2 * i] - r[0], r[2 * i + 1] - r[1])
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return if (bestDistance <= gap) best else -1
    }

    fun length(p: FloatArray): Float {
        var l = 0f
        for (i in 1 until p.size / 2) l += hypot(p[2 * i] - p[2 * i - 2], p[2 * i + 1] - p[2 * i - 1])
        return l
    }

    /** `count` puntos repartidos a distancias iguales a lo largo del recorrido. */
    fun resample(p: FloatArray, count: Int): FloatArray {
        val total = length(p)
        val out = FloatArray(2 * count)
        out[0] = p[0]
        out[1] = p[1]
        if (total == 0f) {
            for (k in 1 until count) {
                out[2 * k] = p[0]
                out[2 * k + 1] = p[1]
            }
            return out
        }
        val step = total / (count - 1)
        var k = 1
        var walked = 0f
        var i = 1
        var ax = p[0]
        var ay = p[1]
        while (k < count - 1 && i < p.size / 2) {
            val bx = p[2 * i]
            val by = p[2 * i + 1]
            val d = hypot(bx - ax, by - ay)
            if (walked + d >= step) {
                val f = (step - walked) / d
                ax += (bx - ax) * f
                ay += (by - ay) * f
                out[2 * k] = ax
                out[2 * k + 1] = ay
                k++
                walked = 0f
            } else {
                walked += d
                ax = bx
                ay = by
                i++
            }
        }
        while (k < count) {
            out[2 * k] = p[p.size - 2]
            out[2 * k + 1] = p[p.size - 1]
            k++
        }
        return out
    }

    private fun diagonal(p: FloatArray): Float {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in 0 until p.size / 2) {
            l = min(l, p[2 * i])
            r = max(r, p[2 * i])
            t = min(t, p[2 * i + 1])
            b = max(b, p[2 * i + 1])
        }
        return hypot(r - l, b - t)
    }

    private fun dist(p: FloatArray, a: Int, b: Int) = hypot(p[2 * b] - p[2 * a], p[2 * b + 1] - p[2 * a + 1])

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 == 0f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** Puntos con que se analiza el trazo. */
    private const val SAMPLES = 128

    /** Un trazo más corto que esto (dp de pantalla) no se reconoce. */
    private const val MIN_LENGTH = 16f

    /** Un tramo es recto si nada se aparta de la cuerda más que esta fracción de su largo... */
    private const val STRAIGHT = 0.07f

    /** ...o que esto en dp de pantalla. */
    private const val STRAIGHT_MIN = 2.5f
    private const val HOOK = 4f
    private const val FLAT_DEGREES = 20f
    private const val CORNER_DEGREES = 35f
    private const val MAX_OPEN_SIDES = 6
    private const val MAX_POLYGON_SIDES = 8

    /** Se cierra si el final vuelve al principio a menos de esta fracción de la diagonal. */
    private const val CLOSE_GAP = 0.2f

    /** Y si el recorrido es bastante más largo que la diagonal (no es un ida y vuelta). */
    private const val CLOSED_LENGTH = 1.6f
    private const val ELLIPSE_ERROR = 0.09f

    /** Con un error así de chico, aunque parezca un polígono de 5 a 8 lados, es redonda. */
    private const val ROUND_ENOUGH = 0.045f
    private const val CIRCLE_RATIO = 1.2f
    private const val RIGHT_ANGLE_TOLERANCE = 25f
    private const val SNAP_DEGREES = 7.0
    private const val ELLIPSE_SNAP_DEGREES = 12f
    private const val RECT_SNAP_DEGREES = 10f
}
