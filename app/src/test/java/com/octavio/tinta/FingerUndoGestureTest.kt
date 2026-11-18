package com.octavio.tinta

import com.octavio.tinta.input.FingerUndoGesture
import com.octavio.tinta.input.FingerUndoGesture.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FingerUndoGestureTest {
    private val tapMs = 350L
    private val slop = 10f

    private fun gesture() = FingerUndoGesture(slop, tapMs)

    @Test
    fun dosDedosQuietosDeshacen() {
        val g = gesture()
        g.down(1, 0f, 0f, 0)
        g.down(2, 40f, 0f, 20)
        assertNull(g.up(1, 80))
        assertEquals(Action.UNDO, g.up(2, 100))
    }

    @Test
    fun tresDedosQuietosRehacen() {
        val g = gesture()
        g.down(1, 0f, 0f, 0)
        g.down(2, 30f, 0f, 10)
        g.down(3, 60f, 0f, 20)
        assertNull(g.up(1, 40))
        assertNull(g.up(2, 50))
        assertEquals(Action.REDO, g.up(3, 60))
    }

    @Test
    fun siSeMueveNoEsToque() {
        val g = gesture()
        g.down(1, 0f, 0f, 0)
        g.down(2, 40f, 0f, 10)
        g.move(2, 40f + slop, 0f)
        assertNull(g.up(1, 80))
        assertNull(g.up(2, 90))
    }

    @Test
    fun unTemblorMenorQueElMargenSigueSiendoToque() {
        val g = gesture()
        g.down(1, 0f, 0f, 0)
        g.down(2, 40f, 0f, 10)
        g.move(1, slop - 1f, 0f)
        g.move(2, 40f, slop - 1f)
        assertNull(g.up(2, 100))
        assertEquals(Action.UNDO, g.up(1, 120))
    }

    @Test
    fun unToqueLargoNoCuenta() {
        val g = gesture()
        g.down(1, 0f, 0f, 0)
        g.down(2, 10f, 0f, 10)
        assertNull(g.up(1, tapMs))
        assertNull(g.up(2, tapMs + 1))
    }

    @Test
    fun unDedoOCuatroNoHacenNada() {
        val one = gesture()
        one.down(1, 0f, 0f, 0)
        assertNull(one.up(1, 50))

        val four = gesture()
        repeat(4) { four.down(it, it * 20f, 0f, it * 10L) }
        repeat(3) { assertNull(four.up(it, 80)) }
        assertNull(four.up(3, 90))
    }

    @Test
    fun dedosQueNoSePisanNoSonUnGesto() {
        val g = gesture()
        g.down(1, 0f, 0f, 0)
        assertNull(g.up(1, 40))
        g.down(2, 0f, 0f, 50)
        assertNull(g.up(2, 80))
    }

    @Test
    fun despuesDeCancelarNoDispara() {
        val g = gesture()
        g.down(1, 0f, 0f, 0)
        g.down(2, 20f, 0f, 10)
        g.cancel()
        assertNull(g.up(1, 40))
        assertNull(g.up(2, 50))
        g.down(3, 0f, 0f, 100)
        g.down(4, 30f, 0f, 110)
        assertNull(g.up(3, 130))
        assertEquals(Action.UNDO, g.up(4, 140))
    }
}
