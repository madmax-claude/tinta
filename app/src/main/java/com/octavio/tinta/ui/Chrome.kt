package com.octavio.tinta.ui

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * Los colores elegidos en Configuración para la barra de arriba, las barras de herramientas y la
 * pestaña elegida. Una barra pintada conserva su forma (se tiñe el fondo) y, si el color es oscuro,
 * sus íconos y textos pasan a claro para que se lean.
 */
object Chrome {
    /** Para elegir: claros, medios y oscuros (y con "+", cualquiera). */
    val COLORS = listOf(
        // Claros
        0xFFFFFFFF.toInt(), // blanco
        0xFFECEFF1.toInt(), // gris claro
        0xFFF3E9D2.toInt(), // arena
        0xFFFFF3C4.toInt(), // amarillo
        0xFFFFE0C2.toInt(), // durazno
        0xFFFBE3EC.toInt(), // rosa
        0xFFECE3F7.toInt(), // lila
        0xFFDCEBFA.toInt(), // celeste
        0xFFD6F1F3.toInt(), // turquesa
        0xFFDDF3E4.toInt(), // menta
        0xFFE9F2D3.toInt(), // pistacho
        0xFFE7DED6.toInt(), // topo
        // Medios
        0xFFF2B33D.toInt(), // mostaza
        0xFFE8794A.toInt(), // naranja
        0xFFD9536F.toInt(), // frambuesa
        0xFF8E6CC9.toInt(), // violeta
        0xFF4A90D9.toInt(), // azul claro
        0xFF2BA3A0.toInt(), // verde agua
        0xFF5DAA5A.toInt(), // verde
        // Oscuros
        0xFF2F5DA8.toInt(), // azul
        0xFF1F3A5F.toInt(), // azul marino
        0xFF2E7D5B.toInt(), // verde oscuro
        0xFF8E2B3A.toInt(), // bordó
        0xFF5B3A29.toInt(), // marrón
        0xFF37393E.toInt(), // gris oscuro
        0xFF1E1F22.toInt(), // negro
    )

    private const val LIGHT = 0xFFF2F2F2.toInt()
    private const val LIGHT_MUTED = 0xFFC4C7CC.toInt()

    fun isDark(color: Int) = ColorUtils.calculateLuminance(color) < 0.4

    /** Texto principal y secundario que se leen sobre `color`. */
    fun ink(color: Int, fallback: Int) = if (isDark(color)) LIGHT else fallback
    fun muted(color: Int, fallback: Int) = if (isDark(color)) LIGHT_MUTED else fallback

    /** Pinta la barra `view` (null: la deja como es). */
    fun paint(view: View, color: Int?) {
        if (color == null) return
        if (view.background == null) view.setBackgroundColor(color) else view.backgroundTintList = ColorStateList.valueOf(color)
        if (isDark(color)) lighten(view)
    }

    /** Íconos y textos claros, adentro de `v` (salvo los lápices, que muestran su color). */
    fun lighten(v: View) {
        when (v) {
            is PenButton -> Unit
            is EditText -> {
                v.setTextColor(LIGHT)
                v.setHintTextColor(LIGHT_MUTED)
            }
            is TextView -> {
                v.setTextColor(LIGHT)
                v.compoundDrawableTintList = ColorStateList.valueOf(LIGHT)
            }
            is ImageView -> v.imageTintList = ColorStateList.valueOf(LIGHT)
            is ViewGroup -> for (i in 0 until v.childCount) lighten(v.getChildAt(i))
        }
    }
}

/** Una muestra de color para elegir: círculo con borde fino, y anillo si está elegida. */
class Swatch(context: android.content.Context, var color: Int, private val original: Boolean, private val plus: Boolean = false) : View(context) {
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = this@Swatch.color }
    private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density
        this.color = 0x40000000
    }
    private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2.5f * context.resources.displayMetrics.density
        this.color = context.getColor(com.octavio.tinta.R.color.accent)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val r = 13 * d
        fill.color = color
        if (plus) {
            // "Otro color": un círculo vacío con un +.
            canvas.drawCircle(cx, cy, r, edge)
            canvas.drawLine(cx - r * 0.5f, cy, cx + r * 0.5f, cy, ring)
            canvas.drawLine(cx, cy - r * 0.5f, cx, cy + r * 0.5f, ring)
            if (isSelected) canvas.drawCircle(cx, cy, r + 4 * d, ring)
            return
        }
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, edge)
        // "El de siempre": una raya cruzada.
        if (original) canvas.drawLine(cx - r * 0.7f, cy + r * 0.7f, cx + r * 0.7f, cy - r * 0.7f, edge)
        if (isSelected) canvas.drawCircle(cx, cy, r + 4 * d, ring)
    }
}
