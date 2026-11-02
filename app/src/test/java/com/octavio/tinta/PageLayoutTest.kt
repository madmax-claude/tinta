package com.octavio.tinta

import com.octavio.tinta.model.Background
import com.octavio.tinta.model.PageLayout
import com.octavio.tinta.model.PageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLayoutTest {

    @Test
    fun a4IsRealSize() {
        val a4 = PageLayout(PageSize.A4)
        // 160 dp por pulgada: 210 mm = 8,27 pulgadas.
        assertEquals(1322.8f, a4.width, 0.1f)
        assertEquals(1870.9f, a4.height, 0.1f)
        val wide = a4.copy(landscape = true)
        assertEquals(a4.height, wide.width, 0f)
        assertTrue(a4.paged)
        assertFalse(PageLayout().paged)
    }

    @Test
    fun pagesGrowWithContentAndKeepOneBlank() {
        val p = PageLayout(PageSize.A5, top = 100f)
        assertEquals(1, p.count(Float.NaN))
        assertEquals(1, p.used(Float.NaN))
        assertEquals(2, p.count(150f)) // escrito en la primera: se ve una en blanco más
        assertEquals(1, p.used(150f))
        val third = p.pageTop(2) + 10f
        assertEquals(2, p.pageAt(third))
        assertEquals(4, p.count(third))
        assertEquals(3, p.used(third))
        // Entre dos hojas cuenta la de arriba.
        assertEquals(0, p.pageAt(p.pageTop(1) - PageLayout.GAP / 2))
    }

    @Test
    fun pagesAddedByHandStayEvenIfBlank() {
        val p = PageLayout(PageSize.A4, minPages = 5)
        assertEquals(5, p.count(Float.NaN))
        assertEquals(5, p.used(Float.NaN)) // se exportan también
        // Si lo escrito llega más lejos, manda lo escrito.
        val far = p.pageTop(6) + 10f
        assertEquals(8, p.count(far))
        assertEquals(7, p.used(far))
    }

    @Test
    fun notebookStacksPagesSideBySide() {
        val p = PageLayout(PageSize.A5, horizontal = true, left = 10f, top = 20f)
        assertEquals(p.width + PageLayout.GAP, p.step, 0f)
        assertEquals(10f, p.pageLeft(0), 0f)
        assertEquals(20f, p.pageTop(0), 0f)
        assertEquals(20f, p.pageTop(1), 0f)
        assertEquals(p.pageLeft(0) + p.step, p.pageLeft(1), 0f)
        assertEquals(1, p.pageOf(p.pageLeft(1) + 4f, p.top + 4f))
        // El hueco entre dos hojas cuenta para la de la izquierda.
        assertEquals(0, p.pageOf(p.pageLeft(1) - PageLayout.GAP / 2, p.top))
        val far = p.pageLeft(2) + 10f
        assertEquals(4, p.count(far))
        assertEquals(3, p.used(far))
        // En vertical el eje sigue siendo la altura: la x no cambia de hoja.
        val down = PageLayout(PageSize.A5, top = 100f)
        assertEquals(0, down.pageOf(down.right + 500f, down.top + 10f))
        assertFalse(down.horizontal)
    }

    @Test
    fun aroundExistingWriting() {
        val p = PageLayout.around(PageSize.LETTER, false, 300f, -20f)
        assertEquals(300f - PageLayout.MARGIN, p.left, 0f)
        assertEquals(-20f - PageLayout.MARGIN, p.top, 0f)
        assertEquals(0f, PageLayout.around(PageSize.A4, false, Float.NaN, Float.NaN).left, 0f)
    }

    @Test
    fun namesAndPapersMoveWithThePage() {
        val p = PageLayout(PageSize.A4, names = listOf("Uno", "Dos"), papers = listOf(-1, Background.LINES.id))
        assertEquals("Uno", p.nameOf(0))
        assertEquals("", p.nameOf(4))
        assertEquals(Background.GRID, p.paperOf(0, Background.GRID))
        assertEquals(Background.LINES, p.paperOf(1, Background.GRID))
        assertTrue(p.hasSheetMeta())
        assertFalse(PageLayout(PageSize.A4).hasSheetMeta())

        val inserted = p.remapSheets(3, { if (it < 1) it else it + 1 }, fill = 1 to Background.DOTS.id)
        assertEquals("Uno", inserted.nameOf(0))
        assertEquals("", inserted.nameOf(1))
        assertEquals("Dos", inserted.nameOf(2))
        assertEquals(Background.DOTS.id, inserted.paperIdOf(1))
        assertEquals(Background.LINES.id, inserted.paperIdOf(2))
        assertEquals(3, inserted.minPages)

        val copy = p.remapSheets(3, { if (it < 0) it else it + 1 }, copyFrom = setOf(0))
        assertEquals("Uno", copy.nameOf(0))
        assertEquals("Uno", copy.nameOf(1))
        assertEquals("Dos", copy.nameOf(2))
        assertEquals(-1, copy.paperIdOf(0))
        assertEquals(Background.LINES.id, copy.paperIdOf(2))

        val deleted = p.remapSheets(1, { if (it == 0) null else it - 1 })
        assertEquals("Dos", deleted.nameOf(0))
        assertEquals(Background.LINES, deleted.paperOf(0, Background.GRID))
        assertEquals("", deleted.nameOf(1))
    }
}
