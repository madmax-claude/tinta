package com.octavio.tinta.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vista de un objeto 3D: primero gira `yaw` grados alrededor del eje vertical y después `pitch`
 * grados hacia adelante (con pitch positivo se ve desde arriba). Proyección ortogonal, como en los
 * dibujos de geometría. En la pantalla x va a la derecha e y hacia abajo; z sale hacia quien mira.
 */
class View3D(yaw: Float, pitch: Float) {
    private val cy = cos(Math.toRadians(yaw.toDouble()))
    private val sy = sin(Math.toRadians(yaw.toDouble()))
    private val cp = cos(Math.toRadians(pitch.toDouble()))
    private val sp = sin(Math.toRadians(pitch.toDouble()))

    /** (x, y, z) del mundo 3D a [x de pantalla, y de pantalla, profundidad hacia quien mira]. */
    fun project(x: Double, y: Double, z: Double, out: DoubleArray) {
        val x1 = x * cy + z * sy
        val z1 = -x * sy + z * cy
        val y2 = y * cp - z1 * sp
        val z2 = y * sp + z1 * cp
        out[0] = x1
        out[1] = -y2
        out[2] = z2
    }

    /** Sólo la componente hacia quien mira de una dirección (para saber si una cara se ve). */
    fun towardViewer(x: Double, y: Double, z: Double): Double {
        val z1 = -x * sy + z * cy
        return y * sp + z1 * cp
    }
}

/** Lo que hay que dibujar de un objeto 3D, ya proyectado (unidades del objeto). */
class Drawing3D {
    /** Segmentos que se ven: x0, y0, x1, y1 seguidos. */
    val visible = ArrayList<Float>()

    /** Segmentos tapados (se dibujan punteados). */
    val hidden = ArrayList<Float>()

    /** Caras que se ven, con su luz de 0 a 1: polígonos en pares (x, y). */
    val faces = ArrayList<Pair<FloatArray, Float>>()
}

/**
 * Un cuerpo convexo hecho de caras planas, centrado en el origen. Las aristas entre caras casi
 * paralelas (las de un cilindro o una esfera) no se dibujan, salvo cuando son el contorno.
 */
class Mesh(private val vertices: List<DoubleArray>, faces: List<IntArray>) {
    private val faces: List<IntArray>
    private val normals: List<DoubleArray>

    private class Edge(val a: Int, val b: Int, val f1: Int, var f2: Int = -1, var feature: Boolean = true)

    private val edges: List<Edge>

    /** Radio de la esfera que lo contiene: con eso se escala igual en cualquier vista. */
    val radius: Double = vertices.maxOf { sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]) }

    init {
        // Caras hacia afuera: la normal tiene que apuntar lejos del centro.
        val oriented = ArrayList<IntArray>()
        val ns = ArrayList<DoubleArray>()
        for (f in faces) {
            var n = normal(f)
            val c = centroid(f)
            val face = if (n[0] * c[0] + n[1] * c[1] + n[2] * c[2] < 0) f.reversedArray().also { n = normal(it) } else f
            oriented.add(face)
            ns.add(n)
        }
        this.faces = oriented
        normals = ns
        val map = HashMap<Long, Edge>()
        val list = ArrayList<Edge>()
        oriented.forEachIndexed { fi, f ->
            for (k in f.indices) {
                val a = f[k]
                val b = f[(k + 1) % f.size]
                val key = min(a, b).toLong() shl 32 or max(a, b).toLong()
                val e = map[key]
                if (e == null) {
                    Edge(a, b, fi).also { map[key] = it; list.add(it) }
                } else {
                    e.f2 = fi
                }
            }
        }
        for (e in list) {
            if (e.f2 < 0) continue
            val n1 = normals[e.f1]
            val n2 = normals[e.f2]
            e.feature = n1[0] * n2[0] + n1[1] * n2[1] + n1[2] * n2[2] < FEATURE_COS
        }
        edges = list
    }

    private fun normal(f: IntArray): DoubleArray {
        // Newell: sirve para cualquier polígono plano.
        var nx = 0.0
        var ny = 0.0
        var nz = 0.0
        for (k in f.indices) {
            val p = vertices[f[k]]
            val q = vertices[f[(k + 1) % f.size]]
            nx += (p[1] - q[1]) * (p[2] + q[2])
            ny += (p[2] - q[2]) * (p[0] + q[0])
            nz += (p[0] - q[0]) * (p[1] + q[1])
        }
        val l = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 0 } ?: 1.0
        return doubleArrayOf(nx / l, ny / l, nz / l)
    }

    private fun centroid(f: IntArray): DoubleArray {
        val c = DoubleArray(3)
        for (i in f) for (k in 0..2) c[k] += vertices[i][k] / f.size
        return c
    }

    /** Proyecta con `view`; con `hiddenEdges`, también las aristas de atrás. */
    fun draw(view: View3D, hiddenEdges: Boolean): Drawing3D {
        val out = Drawing3D()
        val p = vertices.map { v -> DoubleArray(3).also { view.project(v[0], v[1], v[2], it) } }
        val front = BooleanArray(faces.size) { view.towardViewer(normals[it][0], normals[it][1], normals[it][2]) > 1e-9 }
        faces.forEachIndexed { i, f ->
            if (!front[i]) return@forEachIndexed
            val poly = FloatArray(2 * f.size)
            f.forEachIndexed { k, v ->
                poly[2 * k] = p[v][0].toFloat()
                poly[2 * k + 1] = p[v][1].toFloat()
            }
            out.faces.add(poly to light(view, normals[i]))
        }
        for (e in edges) {
            val f1 = front[e.f1]
            val f2 = if (e.f2 >= 0) front[e.f2] else f1
            val contour = f1 != f2
            val target = when {
                (f1 || f2) && (e.feature || contour) -> out.visible
                !f1 && !f2 && e.feature && hiddenEdges -> out.hidden
                else -> continue
            }
            target.add(p[e.a][0].toFloat())
            target.add(p[e.a][1].toFloat())
            target.add(p[e.b][0].toFloat())
            target.add(p[e.b][1].toFloat())
        }
        return out
    }

    /** Luz desde arriba a la izquierda, fija respecto de quien mira. */
    private fun light(view: View3D, n: DoubleArray): Float {
        val q = DoubleArray(3)
        view.project(n[0], n[1], n[2], q)
        // q está en pantalla (y hacia abajo): la luz viene de (-0,4, -0,6, 0,7).
        val d = (-0.4 * q[0] - 0.6 * q[1] + 0.7 * q[2]) / sqrt(0.16 + 0.36 + 0.49)
        return (0.35 + 0.65 * max(0.0, d)).toFloat()
    }

    companion object {
        /** Aristas entre caras que se doblan menos que esto (25°) son parte de una superficie curva. */
        private val FEATURE_COS = cos(Math.toRadians(25.0))
    }
}

/** Las mallas de cada cuerpo, centradas en el origen, con el eje vertical en y. */
object Solids {
    fun mesh(kind: SolidKind, a: Float, b: Float, c: Float): Mesh {
        val x = a.toDouble().coerceAtLeast(0.01)
        val y = b.toDouble().coerceAtLeast(0.01)
        val z = c.toDouble().coerceAtLeast(0.01)
        return when (kind) {
            SolidKind.CUBE -> box(x, x, x)
            SolidKind.BOX -> box(x, y, z)
            SolidKind.TRIANGULAR -> prism(3, x / (2 * sin(PI / 3)), y)
            SolidKind.HEXAGONAL -> prism(6, x, y)
            SolidKind.PYRAMID -> pyramid(4, x / sqrt(2.0), y)
            SolidKind.TETRAHEDRON -> pyramid(3, x / sqrt(3.0), x * sqrt(2.0 / 3.0))
            SolidKind.CYLINDER -> prism(ROUND, x, y)
            SolidKind.CONE -> pyramid(ROUND, x, y)
            SolidKind.SPHERE -> sphere(x)
        }
    }

    private const val ROUND = 48

    private fun box(w: Double, h: Double, d: Double): Mesh {
        val v = ArrayList<DoubleArray>()
        for (i in 0..7) v.add(doubleArrayOf(if (i and 1 == 0) -w / 2 else w / 2, if (i and 2 == 0) -h / 2 else h / 2, if (i and 4 == 0) -d / 2 else d / 2))
        val f = listOf(
            intArrayOf(0, 1, 3, 2), intArrayOf(4, 5, 7, 6), intArrayOf(0, 1, 5, 4),
            intArrayOf(2, 3, 7, 6), intArrayOf(0, 2, 6, 4), intArrayOf(1, 3, 7, 5),
        )
        return Mesh(v, f)
    }

    /** Prisma recto de base regular de `n` lados con radio `r` (n grande: cilindro). */
    private fun prism(n: Int, r: Double, h: Double): Mesh {
        val v = ArrayList<DoubleArray>()
        for (k in 0 until n) {
            val t = 2 * PI * k / n + PI / 2
            v.add(doubleArrayOf(r * cos(t), -h / 2, r * sin(t)))
            v.add(doubleArrayOf(r * cos(t), h / 2, r * sin(t)))
        }
        val f = ArrayList<IntArray>()
        for (k in 0 until n) {
            val j = (k + 1) % n
            f.add(intArrayOf(2 * k, 2 * j, 2 * j + 1, 2 * k + 1))
        }
        f.add(IntArray(n) { 2 * it })
        f.add(IntArray(n) { 2 * it + 1 })
        return Mesh(v, f)
    }

    /** Pirámide recta de base regular (n grande: cono), con el centro en la mitad de la altura. */
    private fun pyramid(n: Int, r: Double, h: Double): Mesh {
        val v = ArrayList<DoubleArray>()
        for (k in 0 until n) {
            val t = 2 * PI * k / n + PI / 4
            v.add(doubleArrayOf(r * cos(t), -h / 2, r * sin(t)))
        }
        v.add(doubleArrayOf(0.0, h / 2, 0.0))
        val f = ArrayList<IntArray>()
        for (k in 0 until n) f.add(intArrayOf(k, (k + 1) % n, n))
        f.add(IntArray(n) { it })
        return Mesh(v, f)
    }

    private fun sphere(r: Double): Mesh {
        val stacks = 16
        val slices = 32
        val v = ArrayList<DoubleArray>()
        v.add(doubleArrayOf(0.0, r, 0.0))
        for (i in 1 until stacks) {
            val phi = PI * i / stacks
            for (j in 0 until slices) {
                val th = 2 * PI * j / slices
                v.add(doubleArrayOf(r * sin(phi) * cos(th), r * cos(phi), r * sin(phi) * sin(th)))
            }
        }
        v.add(doubleArrayOf(0.0, -r, 0.0))
        val bottom = v.size - 1
        fun ring(i: Int, j: Int) = 1 + (i - 1) * slices + (j % slices)
        val f = ArrayList<IntArray>()
        for (j in 0 until slices) f.add(intArrayOf(0, ring(1, j + 1), ring(1, j)))
        for (i in 1 until stacks - 1) {
            for (j in 0 until slices) f.add(intArrayOf(ring(i, j), ring(i, j + 1), ring(i + 1, j + 1), ring(i + 1, j)))
        }
        for (j in 0 until slices) f.add(intArrayOf(bottom, ring(stacks - 1, j), ring(stacks - 1, j + 1)))
        return Mesh(v, f)
    }
}

/**
 * Superficie z = f(x, y) en una grilla, metida en un cubo de lado 2 centrado en el origen (x e y
 * del dominio a [-1, 1], z del mínimo al máximo a [-0,8, 0,8]). En el 3D de [View3D] el eje
 * vertical es y, así que z de la función va en y.
 */
class Surface(expr: Expr, xMin: Float, xMax: Float, yMin: Float, yMax: Float, val steps: Int = 28) {
    /** Valores en la grilla (steps+1)², NaN donde no está definida. */
    private val z = Array(steps + 1) { DoubleArray(steps + 1) }
    val zMin: Double
    val zMax: Double

    init {
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (i in 0..steps) for (j in 0..steps) {
            val x = xMin + (xMax - xMin) * i.toDouble() / steps
            val y = yMin + (yMax - yMin) * j.toDouble() / steps
            val v = expr.eval(x, y)
            z[i][j] = if (v.isFinite()) v else Double.NaN
            if (v.isFinite()) {
                lo = min(lo, v)
                hi = max(hi, v)
            }
        }
        zMin = if (lo.isFinite()) lo else -1.0
        zMax = if (hi.isFinite()) hi else 1.0
    }

    private fun height(i: Int, j: Int): Double {
        val span = zMax - zMin
        return if (span < 1e-12) 0.0 else -0.8 + 1.6 * (z[i][j] - zMin) / span
    }

    /** Un cuadrito proyectado, con su altura media de 0 a 1 (para el color). */
    class Quad(val poly: FloatArray, val depth: Double, val level: Float)

    /** Los cuadritos de atrás para adelante (así los de adelante tapan a los de atrás). */
    fun quads(view: View3D): List<Quad> {
        val out = ArrayList<Quad>(steps * steps)
        val p = DoubleArray(3)
        for (i in 0 until steps) for (j in 0 until steps) {
            val corners = arrayOf(intArrayOf(i, j), intArrayOf(i + 1, j), intArrayOf(i + 1, j + 1), intArrayOf(i, j + 1))
            if (corners.any { z[it[0]][it[1]].isNaN() }) continue
            val poly = FloatArray(8)
            var depth = 0.0
            var level = 0.0
            corners.forEachIndexed { k, c ->
                val h = height(c[0], c[1])
                view.project(-1 + 2.0 * c[0] / steps, h, 1 - 2.0 * c[1] / steps, p)
                poly[2 * k] = p[0].toFloat()
                poly[2 * k + 1] = p[1].toFloat()
                depth += p[2] / 4
                level += (h + 0.8) / 1.6 / 4
            }
            out.add(Quad(poly, depth, level.toFloat()))
        }
        out.sortBy { it.depth }
        return out
    }

    /** Las aristas del cubo que contiene a la superficie, proyectadas: 12 segmentos. */
    fun box(view: View3D): FloatArray {
        val out = FloatArray(48)
        val a = DoubleArray(3)
        val b = DoubleArray(3)
        var n = 0
        for (m in 0..7) for (bit in intArrayOf(1, 2, 4)) {
            if (m and bit != 0) continue
            val o = m or bit
            fun corner(k: Int, out3: DoubleArray) =
                view.project(if (k and 1 == 0) -1.0 else 1.0, if (k and 2 == 0) -0.8 else 0.8, if (k and 4 == 0) 1.0 else -1.0, out3)
            corner(m, a)
            corner(o, b)
            out[n++] = a[0].toFloat()
            out[n++] = a[1].toFloat()
            out[n++] = b[0].toFloat()
            out[n++] = b[1].toFloat()
        }
        return out
    }

    /** Puntos de pantalla de las puntas de los ejes x, y (de la función) y z, para rotularlos. */
    fun axisEnds(view: View3D): FloatArray {
        val q = DoubleArray(3)
        val out = FloatArray(6)
        view.project(1.25, -0.8, 1.0, q); out[0] = q[0].toFloat(); out[1] = q[1].toFloat()
        view.project(-1.0, -0.8, -1.25, q); out[2] = q[0].toFloat(); out[3] = q[1].toFloat()
        view.project(-1.0, 1.05, 1.0, q); out[4] = q[0].toFloat(); out[5] = q[1].toFloat()
        return out
    }

    companion object {
        /** El cubo de la superficie mide 2 de lado: entra en una esfera de este radio. */
        val RADIUS = sqrt(1.0 + 1.0 + 0.64)

        fun nearlyFlat(s: Surface) = abs(s.zMax - s.zMin) < 1e-12
    }
}
