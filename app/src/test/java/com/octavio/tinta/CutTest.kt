package com.octavio.tinta

import com.octavio.tinta.model.Cut
import com.octavio.tinta.model.History
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CutTest {
    private fun line(vararg xs: Float) = xs.mapIndexed { k, x -> Cut.Point(x, 0f, k * 8f, 0.5f, -1f, -1f) }

    @Test
    fun eraserInTheMiddleLeavesTwoPieces() {
        // Una línea de 0 a 100 con sólo dos puntos: igual se corta, por los puntos intermedios.
        val parts = Cut.outside(line(0f, 100f), 50f, -20f, 50f, 20f, 10f, 2f)!!
        assertEquals(2, parts.size)
        assertTrue(parts[0].last().x <= 40f)
        assertTrue(parts[1].first().x >= 60f)
        assertEquals(0f, parts[0].first().x, 0f)
        assertEquals(100f, parts[1].last().x, 0f)
        // Las presiones y los tiempos intermedios salen interpolados.
        assertTrue(parts[1].first().t > 0f && parts[1].first().t < 8f)
    }

    @Test
    fun farAwayTouchesNothing() {
        assertNull(Cut.outside(line(0f, 100f), 50f, 40f, 60f, 40f, 10f, 2f))
    }

    @Test
    fun coveringItAllLeavesNothing() {
        assertEquals(0, Cut.outside(line(0f, 10f), 5f, 0f, 5f, 0f, 30f, 2f)!!.size)
    }

    @Test
    fun insertUndoRedo() {
        val items = mutableListOf("a", "b", "c")
        val h = History()
        // El borrador parcial: saca "b" y pone sus dos pedazos en su lugar.
        val removed = History.Remove(items, listOf(1 to items.removeAt(1)))
        items.addAll(1, listOf("b1", "b2"))
        h.record(History.Group(listOf(removed, History.Insert(items, 1, listOf("b1", "b2")))))
        assertEquals(listOf("a", "b1", "b2", "c"), items)
        h.undo()
        assertEquals(listOf("a", "b", "c"), items)
        h.redo()
        assertEquals(listOf("a", "b1", "b2", "c"), items)
    }
}
