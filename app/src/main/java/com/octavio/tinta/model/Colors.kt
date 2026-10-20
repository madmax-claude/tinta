package com.octavio.tinta.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Cuentas de color en Kotlin puro (sin Android, para poder probarlas en la compu). */
object Colors {
    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF

    /** Luminancia relativa (0 negro, 1 blanco), como en las pautas de accesibilidad. */
    fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(r(c)) + 0.7152 * ch(g(c)) + 0.0722 * ch(b(c))
    }

    /** Contraste entre dos colores opacos: 1 (iguales) a 21 (negro y blanco). */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Si sobre `c` se lee mejor un texto blanco que uno negro. */
    fun prefersLightText(c: Int) = contrast(c, 0xFFFFFFFF.toInt()) >= contrast(c, 0xFF000000.toInt())

    /** Tono (0–360), saturación y luminosidad (0–1). */
    fun toHsl(c: Int): FloatArray {
        val rf = r(c) / 255f
        val gf = g(c) / 255f
        val bf = b(c) / 255f
        val mx = max(rf, max(gf, bf))
        val mn = min(rf, min(gf, bf))
        val d = mx - mn
        val l = (mx + mn) / 2f
        if (d == 0f) return floatArrayOf(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f))
        var h = when (mx) {
            rf -> ((gf - bf) / d) % 6f
            gf -> (bf - rf) / d + 2f
            else -> (rf - gf) / d + 4f
        } * 60f
        if (h < 0f) h += 360f
        return floatArrayOf(h, s.coerceIn(0f, 1f), l)
    }

    /** De tono, saturación y luminosidad a color, con la opacidad `alpha` (0–255). */
    fun fromHsl(hsl: FloatArray, alpha: Int = 0xFF): Int {
        val (h, s, l) = hsl
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (rf, gf, bf) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
        return (alpha shl 24) or (ch(rf) shl 16) or (ch(gf) shl 8) or ch(bf)
    }
}
