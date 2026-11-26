package com.octavio.tinta

import com.octavio.tinta.ui.DockFit
import com.octavio.tinta.ui.dockFit
import com.octavio.tinta.ui.topBarOverflowCount
import org.junit.Assert.assertEquals
import org.junit.Test

class BarFitTest {
    @Test
    fun unaColumnaCuandoEntra() {
        assertEquals(DockFit.ONE, dockFit(800, 650, 380, 140, 1700, 110))
    }

    @Test
    fun dosColumnasSiElAltoNoAlcanza() {
        assertEquals(DockFit.TWO, dockFit(500, 650, 380, 140, 1700, 110))
    }

    @Test
    fun scrollSiDosColumnasNoEntranEnElAncho() {
        assertEquals(DockFit.SCROLL, dockFit(500, 650, 380, 140, 200, 110))
    }

    @Test
    fun seColapsaSiNoEntraNiUnaHerramienta() {
        assertEquals(DockFit.COLLAPSE, dockFit(80, 650, 380, 140, 1700, 110))
    }

    @Test
    fun barraAnchaNoEscondeBotones() {
        assertEquals(0, topBarOverflowCount(2000, 46, 46, 164, 7))
    }

    @Test
    fun ventanaAngostaMandaAlMenu() {
        // 358 − 46 − 164 = 148; con el menú quedan 102; entran 2 botones de 46.
        assertEquals(5, topBarOverflowCount(358, 46, 46, 164, 7))
    }

    @Test
    fun ventanaMinimaEscondeTodos() {
        assertEquals(7, topBarOverflowCount(180, 46, 46, 164, 7))
    }
}
