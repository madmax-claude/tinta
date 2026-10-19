package com.octavio.tinta.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import com.octavio.tinta.R
import com.octavio.tinta.dp
import com.octavio.tinta.tip

/** Los botones de la lupa, arriba a la derecha del panel. */
@SuppressLint("ViewConstructor")
class ZoomBar(context: Context) : LinearLayout(context) {
    interface Callbacks {
        fun onZoomBack()
        fun onZoomForward()
        fun onZoomNewLine()
        fun onZoomBigger()
        fun onZoomSmaller()
        fun onZoomClose()
    }

    var callbacks: Callbacks? = null

    init {
        orientation = HORIZONTAL
        background = context.getDrawable(R.drawable.bg_toolbar)
        elevation = context.dp(12f) // encima del reloj y de las barras fijas: aparece donde se trabaja
        val pad = context.dp(3f).toInt()
        setPadding(pad, pad, pad, pad)
        add(R.drawable.ic_chevron, "Para atrás", rotation = 180f) { callbacks?.onZoomBack() }
        add(R.drawable.ic_chevron, "Para adelante") { callbacks?.onZoomForward() }
        add(R.drawable.ic_newline, "Renglón nuevo") { callbacks?.onZoomNewLine() }
        add(R.drawable.ic_remove, "Menos aumento") { callbacks?.onZoomSmaller() }
        add(R.drawable.ic_add, "Más aumento") { callbacks?.onZoomBigger() }
        add(R.drawable.ic_close, "Cerrar la lupa") { callbacks?.onZoomClose() }
        visibility = GONE
    }

    private fun add(icon: Int, description: String, rotation: Float = 0f, onClick: () -> Unit) {
        addView(ImageButton(context).apply {
            setImageResource(icon)
            this.rotation = rotation
            background = context.getDrawable(R.drawable.bg_tool)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = description
            tip(description)
            setOnClickListener { onClick() }
        }, LayoutParams(context.dp(44f).toInt(), context.dp(44f).toInt()))
    }

    /** Arriba a la derecha del panel (en coordenadas del padre), o escondida con null. */
    fun showFor(panel: RectF?) {
        if (panel == null) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        if (width == 0) {
            post { showFor(panel) }
            return
        }
        val parent = parent as? ViewGroup ?: return
        x = (panel.right - width).coerceIn(0f, (parent.width - width).toFloat().coerceAtLeast(0f))
        y = (panel.top - height - context.dp(6f)).coerceAtLeast(0f)
    }
}
