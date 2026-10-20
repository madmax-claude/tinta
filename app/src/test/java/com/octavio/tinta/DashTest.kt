package com.octavio.tinta

import com.octavio.tinta.model.Appearance
import com.octavio.tinta.model.Cut
import com.octavio.tinta.model.Dash
import com.octavio.tinta.model.Tool
import com.octavio.tinta.model.Colors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DashTest {
    private fun line(vararg xs: Float) = xs.mapIndexed { k, x -> Cut.Point(x, 0f, k * 8f, 0.5f, -1f, -1f) }

    @Test
    fun dashesAlongAStraightLine() {
        // 0 a 100 con rayas de 10 y huecos de 5: tinta en [0,10], [15,25], …, [90,100].
        val parts = Dash.split(line(0f, 100f), floatArrayOf(10f, 5f))
        assertEquals(7, parts.size)
        assertEquals(0f, parts[0].first().x, 1e-4f)
        assertEquals(10f, parts[0].last().x, 1e-4f)
        assertEquals(15f, parts[1].first().x, 1e-4f)
        assertEquals(25f, parts[1].last().x, 1e-4f)
        assertEquals(100f, parts.last().last().x, 1e-4f)
        assertTrue(parts.all { it.size >= 2 })
    }

    @Test
    fun theLengthIsMeasuredAcrossPoints() {
        // Muchos puntos cortos: el patrón sigue a lo largo, no se reinicia en cada punto.
        val parts = Dash.split(line(*FloatArray(21) { it * 5f }), floatArrayOf(10f, 5f))
        assertEquals(7, parts.size)
        assertEquals(30f, parts[2].first().x, 1e-4f)
    }

    @Test
    fun dotsAreTinyRuns() {
        val lengths = Dash.DOTTED.lengths(2f)!!
        val parts = Dash.split(line(0f, 50f), lengths)
        assertTrue(parts.size > 5)
        assertTrue(parts.all { it.size >= 2 && abs(it.last().x - it.first().x) < 2.5f })
    }

    @Test
    fun hslRoundTrip() {
        for (c in listOf(0xFF2F5DA8.toInt(), 0xFFFFF3B0.toInt(), 0xFF000000.toInt(), 0xFFD32F2F.toInt(), 0xFF808080.toInt())) {
            val back = Colors.fromHsl(Colors.toHsl(c))
            for (shift in listOf(16, 8, 0)) assertTrue(abs(((back shr shift) and 0xFF) - ((c shr shift) and 0xFF)) <= 1)
        }
    }

    @Test
    fun solidIsNotSplit() {
        assertEquals(null, Dash.SOLID.lengths(2f))
    }

    private val paper = 0xFF1E1F22.toInt()

    private fun readable(c: Int) = Colors.contrast(c, paper) >= 4.4

    @Test
    fun darkModeKeepsEveryInkReadable() {
        for (c in listOf(0xFF000000, 0xFFFFFFFF, 0xFFFFF3B0, 0xFF0B2A6F, 0xFF0000FF, 0xFFD32F2F, 0xFF2E7D32, 0xFFFFE0F0, 0xFF808080)) {
            val shown = Appearance.ink(c.toInt(), Tool.PEN, dark = true)
            assertTrue("%08X → %08X".format(c, shown), readable(shown))
        }
    }

    @Test
    fun lightInksStayLightAndDarkOnesTurnLight() {
        // Un amarillo clarito ya se lee sobre el papel oscuro: queda igual.
        val pale = 0xFFFFF3B0.toInt()
        assertEquals(pale, Appearance.ink(pale, Tool.PEN, dark = true))
        // El negro pasa a claro.
        assertTrue(Colors.luminance(Appearance.ink(0xFF000000.toInt(), Tool.PEN, true)) > 0.6)
    }

    @Test
    fun pickingInDarkModeRoundTrips() {
        for (shown in listOf(0xFFFFFFFF.toInt(), 0xFF90CAF9.toInt(), 0xFFFFCC80.toInt())) {
            val stored = Appearance.stored(shown, Tool.PEN, dark = true)
            val again = Appearance.ink(stored, Tool.PEN, dark = true)
            val d = Colors.toHsl(again)[2] - Colors.toHsl(shown)[2]
            assertTrue("%08X → %08X → %08X".format(shown, stored, again), abs(d) < 0.08f)
        }
    }
}
