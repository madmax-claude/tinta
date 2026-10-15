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
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = context.dp(2f)
    }

    override fun onDraw(canvas: Canvas) {
        val p = pen ?: return
        val cx = width / 2f
        val cy = height / 2f
        val r = context.dp(14f)
        // El resaltador se muestra con su color pleno; la tinta, como se ve en la hoja.
        val color = if (p.tool == Tool.HIGHLIGHTER) p.color else Appearance.ink(p.color, p.tool, dark)
        fill.color = color
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, edge)
        // El dibujo, blanco o negro según cuán claro sea el color.
        val onColor = if (ColorUtils.calculateLuminance(color) > 0.45) 0xDD000000.toInt() else 0xFFFFFFFF.toInt()
        icon?.let {
            val s = context.dp(9f).toInt()
            it.setTint(onColor)
            it.setBounds((cx - s).toInt(), (cy - s).toInt(), (cx + s).toInt(), (cy + s).toInt())
            it.draw(canvas)
        }
        if (p.straight) {
            bar.color = context.getColor(R.color.ink)
            val y = cy + r + context.dp(4f)
            canvas.drawLine(cx - context.dp(7f), y, cx + context.dp(7f), y, bar)
        }
        if (isSelected) canvas.drawCircle(cx, cy, r + context.dp(4f), ring)
    }

    companion object {
        fun iconFor(tool: Tool) = when (tool) {
            Tool.PEN -> R.drawable.ic_pen
            Tool.MARKER -> R.drawable.ic_marker
            Tool.HIGHLIGHTER -> R.drawable.ic_highlighter
        }
    }
}
