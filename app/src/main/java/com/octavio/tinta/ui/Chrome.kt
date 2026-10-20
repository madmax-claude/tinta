package com.octavio.tinta.ui

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import com.octavio.tinta.model.Colors

/**
 * Los colores elegidos en Configuración para la barra de arriba, las barras de herramientas y la
 * pestaña elegida. Una barra pintada conserva su forma (se tiñe el fondo) y, si el color es oscuro,
 * sus íconos y textos pasan a claro para que se lean.
 */
object Chrome {
    /** Para elegir (y con "+", cualquiera). */
    val COLORS = listOf(
        0xFFFFFFFF.toInt(), // blanco
        0xFFF3E9D2.toInt(), // arena
        0xFFFFF3C4.toInt(), // amarillo
        0xFFFBE3EC.toInt(), // rosa
        0xFFDCEBFA.toInt(), // celeste
        0xFFDDF3E4.toInt(), // menta
        0xFFE8794A.toInt(), // naranja
        0xFF4A90D9.toInt(), // azul claro
        0xFF2F5DA8.toInt(), // azul
        0xFF2E7D5B.toInt(), // verde oscuro
        0xFF8E2B3A.toInt(), // bordó
        0xFF37393E.toInt(), // gris oscuro
        0xFF1E1F22.toInt(), // negro
    )

    private const val LIGHT = 0xFFF2F2F2.toInt()
    private const val LIGHT_MUTED = 0xFFC4C7CC.toInt()
    private const val DARK = 0xFF1F1F1F.toInt()
    private const val DARK_MUTED = 0xFF5F6368.toInt()

    /**
     * Si sobre `color` se lee mejor un texto claro: por contraste, no por el modo (en modo oscuro
     * los textos son claros y sobre una barra clara no se leían).
     */
    fun isDark(color: Int) = Colors.prefersLightText(color)

    /** Texto principal y secundario que se leen sobre `color`. */
    fun ink(color: Int) = if (isDark(color)) LIGHT else DARK
    fun muted(color: Int) = if (isDark(color)) LIGHT_MUTED else DARK_MUTED

    /** Pinta la barra `view` (null: la deja como es) y le pone textos e íconos que se lean. */
    fun paint(view: View, color: Int?) {
        if (color == null) return
        if (view.background == null) view.setBackgroundColor(color) else view.backgroundTintList = ColorStateList.valueOf(color)
        contrast(view, color)
    }

    /** Textos e íconos adentro de `v` que se lean sobre `color` (salvo los lápices y las pestañas). */
    fun contrast(v: View, color: Int) {
        val ink = ink(color)
        val muted = muted(color)
        when (v) {
            is PenButton, is TabStrip -> Unit
            is EditText -> {
                v.setTextColor(ink)
                v.setHintTextColor(muted)
                v.compoundDrawableTintList = ColorStateList.valueOf(muted)
            }
            is TextView -> {
                v.setTextColor(ink)
                v.compoundDrawableTintList = ColorStateList.valueOf(ink)
            }
            is ImageView -> v.imageTintList = ColorStateList.valueOf(ink)
            is ViewGroup -> for (i in 0 until v.childCount) contrast(v.getChildAt(i), color)
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
