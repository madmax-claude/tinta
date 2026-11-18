package com.octavio.tinta

import com.octavio.tinta.input.StylusButtonClicks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StylusButtonClicksTest {
    private val clicks = StylusButtonClicks()

    @Test
    fun unToqueCortoCuentaUnaVez() {
        assertTrue(clicks.onKey(down = true, timeMs = 0))
        assertFalse(clicks.onKey(down = false, timeMs = 40))
        assertFalse(clicks.onButtonEdge(wasPressed = true, pressed = false, timeMs = 40))
    }

    @Test
    fun sostenerloMasDeLaVentanaNoVuelveAContarAlSoltar() {
        assertTrue(clicks.onButtonEdge(wasPressed = false, pressed = true, timeMs = 0))
        assertFalse(clicks.onKey(down = false, timeMs = 800))
        assertFalse(clicks.onDevice(StylusButtonClicks.ACTION_UP, 800))
        assertFalse(clicks.onDevice(StylusButtonClicks.ACTION_BUTTON_RELEASE, 810))
    }

    @Test
    fun elMismoClicPorDosCaminosCuentaUnaVez() {
        assertTrue(clicks.onKey(down = true, timeMs = 1_000))
        assertFalse(clicks.onDevice(StylusButtonClicks.ACTION_DOWN, 1_015))
        assertFalse(clicks.onButtonEdge(wasPressed = false, pressed = true, timeMs = 1_020))
        assertFalse(clicks.onDevice(StylusButtonClicks.ACTION_BUTTON_PRESS, 1_040))
    }

    @Test
    fun otroClicDespuesDeLaVentanaCuentaDeNuevo() {
        assertTrue(clicks.onKey(down = true, timeMs = 0))
        assertFalse(clicks.onKey(down = false, timeMs = 80))
        assertTrue(clicks.onKey(down = true, timeMs = StylusButtonClicks.GAP_MS))
    }

    @Test
    fun elScrollDelControlNoEsUnClic() {
        assertFalse(clicks.onDevice(StylusButtonClicks.ACTION_SCROLL, 0))
        assertFalse(clicks.onDevice(StylusButtonClicks.ACTION_SCROLL, 500))
        assertTrue(clicks.onDevice(StylusButtonClicks.ACTION_BUTTON_PRESS, 500))
    }

    @Test
    fun soltarOtraAccionNoEsUnClic() {
        assertFalse(clicks.onDevice(StylusButtonClicks.ACTION_UP, 0))
        assertFalse(clicks.onDevice(StylusButtonClicks.ACTION_BUTTON_RELEASE, 10))
        assertFalse(clicks.onDevice(99, 20))
        assertFalse(clicks.onButtonEdge(wasPressed = false, pressed = false, timeMs = 30))
        assertFalse(clicks.onButtonEdge(wasPressed = true, pressed = true, timeMs = 40))
    }
}
