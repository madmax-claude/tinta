package com.octavio.tinta.canvas

import android.graphics.Matrix

/**
 * Transformación del mundo (en dp) a la pantalla (en px): pantalla = mundo · scale + (tx, ty).
 * No hay rotación, así que alcanza con tres números.
 */
class Camera(val density: Float) {
    var scale = density
        private set
    var tx = 0f
        private set
    var ty = 0f
        private set

    /** Zoom relativo a 1 dp = 1 dp en pantalla. */
    val zoom get() = scale / density

    /** Pone el punto del mundo (x, y) en la esquina superior izquierda de la pantalla. */
    fun show(x: Float, y: Float, zoom: Float) {
        set(0f, 0f, zoom)
        tx = -x * scale
        ty = -y * scale
    }

    fun set(x: Float, y: Float, zoom: Float) {
        tx = x
        ty = y
        scale = (zoom * density).coerceIn(MIN_ZOOM * density, MAX_ZOOM * density)
    }

    /** Mueve (dx, dy) px y escala por `factor` alrededor del punto de pantalla (fx, fy). */
    fun panZoom(dx: Float, dy: Float, factor: Float, fx: Float, fy: Float) {
        tx += dx
        ty += dy
        val newScale = (scale * factor).coerceIn(MIN_ZOOM * density, MAX_ZOOM * density)
        val f = newScale / scale
        tx = fx - (fx - tx) * f
        ty = fy - (fy - ty) * f
        scale = newScale
    }

    fun toWorldX(sx: Float) = (sx - tx) / scale
    fun toWorldY(sy: Float) = (sy - ty) / scale

    fun worldToScreen(out: Matrix): Matrix = out.apply {
        setScale(scale, scale)
        postTranslate(tx, ty)
    }

    fun screenToWorld(out: Matrix): Matrix = out.apply {
        setTranslate(-tx, -ty)
        postScale(1 / scale, 1 / scale)
    }

    companion object {
        const val MIN_ZOOM = 0.2f
        const val MAX_ZOOM = 8f
    }
}
