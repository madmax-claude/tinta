package com.octavio.tinta

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
    fun aroundExistingWriting() {
        val p = PageLayout.around(PageSize.LETTER, false, 300f, -20f)
        assertEquals(300f - PageLayout.MARGIN, p.left, 0f)
        assertEquals(-20f - PageLayout.MARGIN, p.top, 0f)
        assertEquals(0f, PageLayout.around(PageSize.A4, false, Float.NaN, Float.NaN).left, 0f)
    }
}
