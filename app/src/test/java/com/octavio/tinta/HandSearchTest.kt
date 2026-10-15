package com.octavio.tinta

import com.octavio.tinta.model.Find
import com.octavio.tinta.model.Lines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandSearchTest {
    /** Una "letra" de 20 de alto en (x, y); `tall` para las que suben o bajan (l, g). */
    private fun letter(x: Float, y: Float, tall: Float = 0f) = Lines.Box(x, y - tall, x + 12f, y + 20f)

    @Test
    fun twoLinesOfWords() {
        val boxes = ArrayList<Lines.Box>()
        // Primer renglón: "hola mundo", con una letra alta; segundo, 40 más abajo.
        for (k in 0 until 4) boxes.add(letter(10f + k * 14f, 100f, if (k == 1) 10f else 0f))
        for (k in 0 until 5) boxes.add(letter(90f + k * 14f, 102f))
        for (k in 0 until 6) boxes.add(letter(10f + k * 14f, 140f))
        val lines = Lines.group(boxes)
        assertEquals(2, lines.size)
        assertEquals((0 until 9).toList(), lines[0])
        assertEquals((9 until 15).toList(), lines[1])
    }

    @Test
    fun aBigGapSplitsTheLine() {
        // Dos columnas a la misma altura.
        val boxes = (0 until 3).map { letter(10f + it * 14f, 100f) } + (0 until 3).map { letter(600f + it * 14f, 100f) }
        val lines = Lines.group(boxes)
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4, 5)), lines)
    }

    @Test
    fun drawingsAreLeftOut() {
        val boxes = (0 until 5).map { letter(10f + it * 14f, 100f) } + Lines.Box(0f, 0f, 400f, 400f)
        val lines = Lines.group(boxes)
        assertEquals(listOf((0 until 5).toList()), lines)
    }

    @Test
    fun linesComeTopToBottom() {
        // Escrito de abajo para arriba: igual salen en orden.
        val boxes = (0 until 3).map { letter(10f + it * 14f, 300f) } + (0 until 3).map { letter(10f + it * 14f, 100f) }
        val lines = Lines.group(boxes)
        assertEquals(listOf(listOf(3, 4, 5), listOf(0, 1, 2)), lines)
    }

    @Test
    fun normalizeIgnoresCaseAccentsAndSpaces() {
        assertEquals("ecuaciondecalor", Find.normalize("Ecuación de  CALOR"))
        assertEquals("pinguino", Find.normalize("pingüino"))
    }

    @Test
    fun matchesWithSmallMistakes() {
        val text = Find.normalize("la ecuacion del calor en una barra")
        assertTrue(Find.matches(text, Find.normalize("calor")))
        assertTrue(Find.matches(text, Find.normalize("ecuación")))
        // Una letra mal leída en una palabra de 6 o más.
        assertTrue(Find.matches(text, Find.normalize("ecuasion")))
        assertEquals(1, Find.errors(text, Find.normalize("ecuasion")))
        assertEquals(0, Find.errors(text, Find.normalize("calor")))
        // Las palabras cortas, exactas (si no, en una nota larga aparecen por todos lados).
        assertFalse(Find.matches(text, Find.normalize("barro")))
        assertFalse(Find.matches(text, Find.normalize("gas")))
        assertFalse(Find.matches(text, Find.normalize("entropía")))
        assertFalse(Find.matches(text, ""))
    }

    @Test
    fun distanceIsZeroInside() {
        assertEquals(0, Find.distance("abcdef", "cde"))
        assertEquals(1, Find.distance("abcdef", "cxe"))
        assertEquals(3, Find.distance("", "abc"))
    }
}
