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

/** Qué hacer con la captura recién marcada; flota arriba (o abajo) del recuadro. */
@SuppressLint("ViewConstructor")
class CaptureBar(context: Context) : LinearLayout(context) {

    interface Callbacks {
        /** Para pegarla en una nota (con el lazo) o en otra app. */
        fun onCaptureCopy()
        fun onCaptureSave()
        fun onCaptureShare()
        fun onCaptureClose()
    }

    var callbacks: Callbacks? = null

    init {
        orientation = HORIZONTAL
        background = context.getDrawable(R.drawable.bg_toolbar)
        elevation = context.dp(6f)
        val pad = context.dp(3f).toInt()
        setPadding(pad, pad, pad, pad)
        add(R.drawable.ic_copy, "Copiar (para pegarla en una nota o en otra app)") { callbacks?.onCaptureCopy() }
        add(R.drawable.ic_save, "Guardar en la galería") { callbacks?.onCaptureSave() }
        add(R.drawable.ic_share, "Compartir") { callbacks?.onCaptureShare() }
        add(R.drawable.ic_close, "Descartar la captura") { callbacks?.onCaptureClose() }
        visibility = GONE
    }

    private fun add(icon: Int, description: String, onClick: () -> Unit) {
        addView(ImageButton(context).apply {
            setImageResource(icon)
            background = context.getDrawable(R.drawable.bg_tool)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = description
            tip(description)
            setOnClickListener { onClick() }
        }, LayoutParams(context.dp(46f).toInt(), context.dp(46f).toInt()))
    }

    /** Arriba del recuadro (en coordenadas del padre) si entra; si no, abajo o adentro. Null la esconde. */
    fun showFor(target: RectF?) {
        if (target == null) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        val parent = parent as? ViewGroup ?: return
        if (width == 0) {
            post { showFor(target) }
            return
        }
        val gap = context.dp(10f)
        val margin = context.dp(6f)
        var top = target.top - height - gap
        if (top < margin) top = target.bottom + gap
        if (top + height > parent.height - margin) top = (target.top + gap).coerceIn(margin, (parent.height - height - margin).coerceAtLeast(margin))
        x = (target.centerX() - width / 2f).coerceIn(margin, (parent.width - width - margin).coerceAtLeast(margin))
        y = top
    }
}
