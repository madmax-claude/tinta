package com.octavio.tinta.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Lo que se le hace a lo elegido con el lazo: p' = a·R(θ)·p + d, con `a` > 0 la escala, θ el giro
 * en radianes (positivo en el sentido de las agujas del reloj, porque y crece hacia abajo) y d el
 * corrimiento, todo en el mundo.
 */
data class Transform(val a: Float = 1f, val angle: Float = 0f, val dx: Float = 0f, val dy: Float = 0f) {
    val cos = cos(angle)
    val sin = sin(angle)

    val isIdentity get() = a == 1f && angle == 0f && dx == 0f && dy == 0f

    fun x(x: Float, y: Float) = a * (cos * x - sin * y) + dx
    fun y(x: Float, y: Float) = a * (sin * x + cos * y) + dy

    val degrees get() = Math.toDegrees(angle.toDouble()).toFloat()

    /** La caja que contiene a la caja (l, t, r, b) transformada: [left, top, right, bottom]. */
    fun box(l: Float, t: Float, r: Float, b: Float): FloatArray {
        val xs = floatArrayOf(x(l, t), x(r, t), x(r, b), x(l, b))
        val ys = floatArrayOf(y(l, t), y(r, t), y(r, b), y(l, b))
        return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /** Sin giro alcanza con mover la caja; con giro, la caja crece lo justo para contener todo. */
    fun isAxisAligned() = abs(sin) < 1e-6f

    companion object {
        fun translation(dx: Float, dy: Float) = Transform(1f, 0f, dx, dy)

        /** Escala `a` dejando quieto el punto (px, py). */
        fun scale(a: Float, px: Float, py: Float) = Transform(a, 0f, px * (1 - a), py * (1 - a))

        /** Gira `angle` alrededor de (px, py). */
        fun rotation(angle: Float, px: Float, py: Float): Transform {
            val c = cos(angle)
            val s = sin(angle)
            return Transform(1f, angle, px - (c * px - s * py), py - (s * px + c * py))
        }

        /** Un rectángulo (l, t, w, h) que se mueve con su centro, sin girar: el nuevo (l, t, w, h). */
        fun upright(t: Transform, l: Float, top: Float, w: Float, h: Float): FloatArray {
            val cx = l + w / 2
            val cy = top + h / 2
            val nx = t.x(cx, cy)
            val ny = t.y(cx, cy)
            return floatArrayOf(nx - w * t.a / 2, ny - h * t.a / 2, w * t.a, h * t.a)
        }

        fun clampBox(box: FloatArray, other: FloatArray) = floatArrayOf(
            min(box[0], other[0]), min(box[1], other[1]), max(box[2], other[2]), max(box[3], other[3]),
        )
    }
}
