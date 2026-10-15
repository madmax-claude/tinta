package com.octavio.tinta

import com.octavio.tinta.model.History
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {

    @Test
    fun addUndoRedo() {
        val items = mutableListOf("a")
        val h = History()
        h.add(items, listOf("b", "c"))
        assertEquals(listOf("a", "b", "c"), items)
        h.undo()
        assertEquals(listOf("a"), items)
        h.redo()
        assertEquals(listOf("a", "b", "c"), items)
    }

    @Test
    fun eraseRestoresOriginalPositions() {
        val items = mutableListOf("a", "b", "c", "d")
        val h = History()
        // El borrador recorre de atrás para adelante: saca "d" (3) y después "b" (1).
        val removed = listOf(3 to items.removeAt(3), 1 to items.removeAt(1))
        h.recordRemoved(items, removed)
        assertEquals(listOf("a", "c"), items)
        h.undo()
        assertEquals(listOf("a", "b", "c", "d"), items)
        h.redo()
        assertEquals(listOf("a", "c"), items)
    }

    @Test
    fun clearAndNewEditDropsRedo() {
        val items = mutableListOf("a", "b")
        val images = mutableListOf(1, 2, 3)
        val h = History()
        h.clear(items, images)
        assertEquals(emptyList<String>(), items)
        assertEquals(emptyList<Int>(), images)
        h.undo()
        assertEquals(listOf("a", "b"), items)
        assertEquals(listOf(1, 2, 3), images)
        h.add(items, listOf("x"))
        assertFalse(h.canRedo)
        h.undo()
        h.undo()
        assertEquals(listOf("a", "b"), items)
    }

    /** Mover una selección: se sacan los originales y se agregan las copias movidas al final. */
    @Test
    fun groupUndoesTogether() {
        val strokes = mutableListOf("a", "b", "c", "d")
        val images = mutableListOf(10, 20)
        val h = History()
        val lifted = History.removeAt(strokes, listOf(1, 3))
        val liftedImage = History.removeAt(images, listOf(0))
        strokes.addAll(listOf("b'", "d'"))
        images.add(11)
        h.record(
            History.Group(
                listOf(lifted, History.Add(strokes, listOf("b'", "d'")), liftedImage, History.Add(images, listOf(11))),
            ),
        )
        assertEquals(listOf("a", "c", "b'", "d'"), strokes)
        assertEquals(listOf(20, 11), images)
        val edit = h.undo()!!
        assertTrue(edit.affects(images))
        assertEquals(listOf("a", "b", "c", "d"), strokes)
        assertEquals(listOf(10, 20), images)
        h.redo()
        assertEquals(listOf("a", "c", "b'", "d'"), strokes)
        assertEquals(listOf(20, 11), images)
    }
}
