package com.octavio.tinta.ui

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot

/** Corre un rectángulo para que quede dentro del padre y no pise otras barras. */
object Clearance {
    fun push(x: Float, y: Float, w: Float, h: Float, parentW: Float, parentH: Float, blocked: List<RectF>): Pair<Float, Float> {
        val maxX = (parentW - w).coerceAtLeast(0f)
        val maxY = (parentH - h).coerceAtLeast(0f)
        var nx = x.coerceIn(0f, maxX)
        var ny = y.coerceIn(0f, maxY)
        if (w <= 0f || h <= 0f) return nx to ny
        var guard = 0
        while (guard < 8) {
            guard++
            val hit = blocked.firstOrNull { nx + w > it.left && nx < it.right && ny + h > it.top && ny < it.bottom } ?: break
            val pushes = listOf(
                -((nx + w) - hit.left) to 0f,
                (hit.right - nx) to 0f,
                0f to -((ny + h) - hit.top),
                0f to (hit.bottom - ny),
            )
            val (dx, dy) = pushes.minBy { (dx, dy) ->
                val x2 = (nx + dx).coerceIn(0f, maxX)
                val y2 = (ny + dy).coerceIn(0f, maxY)
                abs(nx + dx - x2) + abs(ny + dy - y2) + hypot(dx, dy)
            }
            nx = (nx + dx).coerceIn(0f, maxX)
            ny = (ny + dy).coerceIn(0f, maxY)
        }
        return nx to ny
    }
}
