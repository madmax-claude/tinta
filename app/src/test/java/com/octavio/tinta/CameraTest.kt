package com.octavio.tinta

import com.octavio.tinta.canvas.Camera
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraTest {

    @Test
    fun zoomKeepsFocusPointFixed() {
        val c = Camera(density = 2f)
        c.set(100f, 50f, 1f)
        val wx = c.toWorldX(400f)
        val wy = c.toWorldY(300f)
        c.panZoom(0f, 0f, 3f, 400f, 300f)
        assertEquals(wx, c.toWorldX(400f), 1e-4f)
        assertEquals(wy, c.toWorldY(300f), 1e-4f)
        assertEquals(3f, c.zoom, 1e-4f)
    }

    @Test
    fun panMovesWorldWithFingers() {
        val c = Camera(density = 2f)
        val before = c.toWorldX(10f)
        c.panZoom(30f, 0f, 1f, 0f, 0f)
        assertEquals(before, c.toWorldX(40f), 1e-4f)
    }

    @Test
    fun zoomIsClamped() {
        val c = Camera(density = 2f)
        c.panZoom(0f, 0f, 1000f, 0f, 0f)
        assertEquals(Camera.MAX_ZOOM, c.zoom, 1e-4f)
        c.panZoom(0f, 0f, 1e-6f, 0f, 0f)
        assertEquals(Camera.MIN_ZOOM, c.zoom, 1e-4f)
    }

    @Test
    fun showPutsWorldPointAtTopLeft() {
        val c = Camera(density = 2f)
        c.show(300f, -40f, 0.5f)
        assertEquals(300f, c.toWorldX(0f), 1e-4f)
        assertEquals(-40f, c.toWorldY(0f), 1e-4f)
        assertEquals(0.5f, c.zoom, 1e-4f)
    }
}
