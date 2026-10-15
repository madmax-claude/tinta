package com.octavio.tinta

import com.octavio.tinta.model.Expr
import com.octavio.tinta.model.SolidKind
import com.octavio.tinta.model.Solids
import com.octavio.tinta.model.Surface
import com.octavio.tinta.model.Transform
import com.octavio.tinta.model.View3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeDTest {

    @Test
    fun cubeSeenFromAboveAndTheSide() {
        // Girado y desde arriba se ven tres caras: 9 aristas a la vista y 3 tapadas.
        val d = Solids.mesh(SolidKind.CUBE, 1f, 1f, 1f).draw(View3D(30f, 20f), hiddenEdges = true)
        assertEquals(3, d.faces.size)
        assertEquals(9, d.visible.size / 4)
        assertEquals(3, d.hidden.size / 4)
        // Sin las de atrás.
        assertEquals(0, Solids.mesh(SolidKind.CUBE, 1f, 1f, 1f).draw(View3D(30f, 20f), hiddenEdges = false).hidden.size)
    }

    @Test
    fun cylinderShowsOnlyRimsAndContour() {
        val d = Solids.mesh(SolidKind.CYLINDER, 1f, 2f, 0f).draw(View3D(0f, 25f), hiddenEdges = true)
        // Las 48 caras del costado no se dibujan una por una: dos bordes de contorno, el aro de
        // arriba entero y la mitad de adelante del de abajo; la de atrás va punteada.
        val visible = d.visible.size / 4
        assertTrue("visibles: $visible", visible in 48 + 24..48 + 26 + 2)
        assertTrue("ocultas: ${d.hidden.size / 4}", d.hidden.size / 4 in 22..26)
    }

    @Test
    fun sphereIsJustItsOutline() {
        val d = Solids.mesh(SolidKind.SPHERE, 1f, 0f, 0f).draw(View3D(10f, 30f), hiddenEdges = true)
        assertEquals(0, d.hidden.size)
        assertTrue(d.visible.size / 4 in 20..80)
    }

    @Test
    fun surfaceQuadsBackToFront() {
        val s = Surface(Expr.parse("x^2 + y^2"), -1f, 1f, -1f, 1f, steps = 10)
        val quads = s.quads(View3D(30f, 30f))
        assertEquals(100, quads.size)
        assertTrue(quads.zipWithNext().all { (a, b) -> a.depth <= b.depth })
        assertEquals(0.0, s.zMin, 1e-9)
        assertEquals(2.0, s.zMax, 1e-9)
    }

    @Test
    fun rotationKeepsTheBoxAroundTheTurnedBox() {
        val t = Transform.rotation((Math.PI / 4).toFloat(), 0f, 0f)
        val b = t.box(-1f, -1f, 1f, 1f)
        val r = Math.sqrt(2.0).toFloat()
        assertEquals(-r, b[0], 1e-5f)
        assertEquals(r, b[3], 1e-5f)
        // Escala alrededor de una esquina: esa esquina no se mueve.
        val s = Transform.scale(2f, 10f, 20f)
        assertEquals(10f, s.x(10f, 20f), 0f)
        assertEquals(30f, s.x(20f, 0f), 0f)
    }
}
