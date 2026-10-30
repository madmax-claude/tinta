package com.octavio.tinta.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.graphics.ColorUtils
import com.octavio.tinta.R
import com.octavio.tinta.dp
import com.octavio.tinta.model.Appearance
import com.octavio.tinta.model.Pen
import com.octavio.tinta.model.Tool

/**
 * Botón de un lápiz de la barra: un círculo de su color con el dibujo de la herramienta encima, y
 * una raya debajo si traza líneas rectas. Con anillo si está elegido.
 */
class PenButton(context: Context, private val dark: Boolean) : View(context) {
    var pen: Pen? = null
        set(value) {
            field = value
            icon = value?.let { context.getDrawable(iconFor(it.tool))?.mutate() }
            contentDescription = value?.let { buildString {
                append(it.tool.label)
                if (it.straight) append(", línea recta")
            } }
            invalidate()
        }

    private var icon: Drawable? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2.5f)
        color = context.getColor(R.color.accent)
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1f)
        color = context.getColor(R.color.card_stroke)
    }
    /** Color de la raya de línea recta: el de los íconos de la barra, para que contraste. */
    var indicator = context.getColor(R.color.ink)
        set(value) {
            field = value
            invalidate()
        }

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = context.dp(3f)
    }

    override fun onDraw(canvas: Canvas) {
        val p = pen ?: return
        val cx = width / 2f
        val mark = p.straight || p.dash != com.octavio.tinta.model.Dash.SOLID
        val r = context.dp(11f)
        val cy = if (mark) height / 2f - context.dp(4f) else height / 2f
        // El resaltador se muestra con su opacidad; la tinta, como se ve en la hoja.
        val color = if (p.tool == Tool.HIGHLIGHTER) {
            val a = (p.opacity.coerceIn(5, 100) * 255 / 100).coerceIn(1, 255)
            (p.color and 0x00FFFFFF) or (a shl 24)
        } else {
            Appearance.ink(p.color, p.tool, dark)
        }
        fill.color = color
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, edge)
        // El dibujo, blanco o negro según cuán claro sea el color (sin contar la transparencia).
        val onColor = if (ColorUtils.calculateLuminance(color or 0xFF000000.toInt()) > 0.45) 0xDD000000.toInt() else 0xFFFFFFFF.toInt()
        icon?.let {
            val s = context.dp(9f).toInt()
            it.setTint(onColor)
            it.setBounds((cx - s).toInt(), (cy - s).toInt(), (cx + s).toInt(), (cy + s).toInt())
            it.draw(canvas)
        }
        // Debajo, una raya si traza rectas; con su patrón si es a rayas o de puntos.
        if (p.straight || p.dash != com.octavio.tinta.model.Dash.SOLID) {
            bar.color = indicator
            val y = (cy + r + context.dp(3.5f)).coerceAtMost(height - context.dp(1.5f))
            val half = context.dp(if (p.dash == com.octavio.tinta.model.Dash.SOLID) 12f else 14f)
            val w = bar.strokeWidth
            bar.pathEffect = p.dash.lengths(w)?.let { l ->
                android.graphics.DashPathEffect(FloatArray(l.size) { i -> if (i % 2 == 0) (l[i] - w).coerceAtLeast(0.01f) else l[i] + w }, 0f)
            }
            canvas.drawLine(cx - half, y, cx + half, y, bar)
        }
        if (isSelected) canvas.drawCircle(cx, cy, r + context.dp(2.5f), ring)
    }

    companion object {
        fun iconFor(tool: Tool) = when (tool) {
            Tool.PEN -> R.drawable.ic_pen
            Tool.MARKER -> R.drawable.ic_marker
            Tool.HIGHLIGHTER -> R.drawable.ic_highlighter
        }
    }
}
