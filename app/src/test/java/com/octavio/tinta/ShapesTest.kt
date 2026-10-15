package com.octavio.tinta

import com.octavio.tinta.model.Shapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Trazos dibujados "a mano": puntos sobre la figura con un temblor de ±1 dp. */
class ShapesTest {
    private val random = Random(7)

    private fun shaky(vararg vertices: Float, step: Float = 3f): FloatArray {
        val out = ArrayList<Float>()
        for (i in 0 until vertices.size / 2 - 1) {
            val ax = vertices[2 * i]
            val ay = vertices[2 * i + 1]
            val bx = vertices[2 * i + 2]
            val by = vertices[2 * i + 3]
            val n = (kotlin.math.hypot(bx - ax, by - ay) / step).toInt().coerceAtLeast(1)
            for (k in 0 until n) {
                out.add(ax + (bx - ax) * k / n + jitter())
                out.add(ay + (by - ay) * k / n + jitter())
            }
        }
        out.add(vertices[vertices.size - 2])
        out.add(vertices[vertices.size - 1])
        return out.toFloatArray()
    }

    private fun jitter() = (random.nextFloat() - 0.5f) * 2f

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, turns: Float = 1f, lumpy: Float = 0.03f): FloatArray {
        val n = 160
        val out = FloatArray(2 * (n + 1))
        for (i in 0..n) {
            val t = 2 * PI * turns * i / n - PI / 2
            val wobble = 1 + lumpy * sin(5 * t).toFloat()
            out[2 * i] = cx + rx * wobble * cos(t).toFloat() + jitter()
            out[2 * i + 1] = cy + ry * wobble * sin(t).toFloat() + jitter()
        }
        return out
    }

    @Test
    fun almostHorizontalLineBecomesStraight() {
        val s = Shapes.recognize(shaky(0f, 0f, 300f, 12f), 1f) as Shapes.Polyline
        assertEquals(2, s.vertices)
        assertEquals(s.xy[1], s.xy[3], 0f) // enderezada
        assertEquals(300f, s.xy[2], 2f)
    }

    @Test
    fun squareRootKeepsItsCornersAndLength() {
        // Rabito, bajada, subida larga y la barra hasta donde se levantó el lápiz.
        val root = shaky(0f, 60f, 15f, 50f, 40f, 110f, 80f, 0f, 400f, 3f)
        val s = Shapes.recognize(root, 1f) as Shapes.Polyline
        assertTrue("vértices: ${s.vertices}", s.vertices in 4..5)
        assertEquals(400f, s.xy[s.xy.size - 2], 3f)
        // La barra queda horizontal.
        assertEquals(s.xy[s.xy.size - 3], s.xy[s.xy.size - 1], 0f)
    }

    @Test
    fun followMovesTheEndOfAnOpenShape() {
        val s = Shapes.recognize(shaky(0f, 0f, 100f, 100f, 300f, 100f), 1f) as Shapes.Polyline
        val longer = s.follow(300f, 100f, 520f, 104f) as Shapes.Polyline
        assertEquals(520f, longer.xy[longer.xy.size - 2], 0f)
        assertEquals(longer.xy[longer.xy.size - 3], longer.xy[longer.xy.size - 1], 0f)
    }

    @Test
    fun rectangle() {
        val s = Shapes.recognize(shaky(0f, 0f, 300f, 6f, 296f, 200f, -4f, 196f, 2f, 4f), 1f) as Shapes.Polyline
        assertTrue(s.closed)
        assertEquals(4, s.vertices)
        // Lados derechos: los cuatro ángulos rectos.
        for (i in 0 until 4) {
            val a = i * 2
            val b = ((i + 1) % 4) * 2
            val c = ((i + 2) % 4) * 2
            val dot = (s.xy[b] - s.xy[a]) * (s.xy[c] - s.xy[b]) + (s.xy[b + 1] - s.xy[a + 1]) * (s.xy[c + 1] - s.xy[b + 1])
            assertEquals(0f, dot, 1f)
        }
    }

    @Test
    fun triangle() {
        val s = Shapes.recognize(shaky(0f, 200f, 120f, 0f, 240f, 200f, 4f, 196f), 1f) as Shapes.Polyline
        assertTrue(s.closed)
        assertEquals(3, s.vertices)
    }

    @Test
    fun circleEvenWithOvershoot() {
        val s = Shapes.recognize(ellipse(0f, 0f, 100f, 100f, turns = 1.08f), 1f)
        assertTrue("$s", s is Shapes.Ellipse && s.isCircle)
        s as Shapes.Ellipse
        assertEquals(100f, s.rx, 6f)
        assertEquals(0f, s.cx, 4f)
    }

    @Test
    fun ellipse() {
        val s = Shapes.recognize(ellipse(50f, 20f, 200f, 90f), 1f) as Shapes.Ellipse
        assertEquals(200f, s.rx, 12f)
        assertEquals(90f, s.ry, 8f)
        assertEquals(0f, s.angle, 0.01f)
    }

    @Test
    fun handwritingIsLeftAlone() {
        // Una "S" y una espiral: curvas que no son ninguna figura.
        val sCurve = FloatArray(2 * 100).also { a ->
            for (i in 0 until 100) {
                val t = i / 99f * 2 * PI
                a[2 * i] = (60 * sin(t)).toFloat()
                a[2 * i + 1] = (i * 2f)
            }
        }
        assertNull(Shapes.recognize(sCurve, 1f))
        val spiral = FloatArray(2 * 200).also { a ->
            for (i in 0 until 200) {
                val t = i / 199f * 4 * PI
                val r = 10 + i * 0.8f
                a[2 * i] = (r * cos(t)).toFloat()
                a[2 * i + 1] = (r * sin(t)).toFloat()
            }
        }
        assertNull(Shapes.recognize(spiral, 1f))
    }

    @Test
    fun arcIsNotAPolyline() {
        val arc = FloatArray(2 * 80).also { a ->
            for (i in 0 until 80) {
                val t = PI * i / 79
                a[2 * i] = (150 * cos(t)).toFloat()
                a[2 * i + 1] = (-150 * sin(t)).toFloat()
            }
        }
        assertNull(Shapes.recognize(arc, 1f))
    }

    @Test
    fun closedShapesGrowFromTheCenter() {
        val s = Shapes.recognize(ellipse(0f, 0f, 100f, 100f), 1f) as Shapes.Ellipse
        val big = s.follow(0f, -100f, 0f, -200f) as Shapes.Ellipse
        assertEquals(s.rx * 2, big.rx, 0.5f)
    }

    @Test
    fun samplesAreDenseEnough() {
        val s = Shapes.Polyline(floatArrayOf(0f, 0f, 100f, 0f), closed = false)
        val pts = s.sample(4f)
        assertEquals(26 * 2, pts.size)
        assertEquals(100f, pts[pts.size - 2], 0f)
    }
}
