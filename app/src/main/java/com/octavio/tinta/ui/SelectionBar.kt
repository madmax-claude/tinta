package com.octavio.tinta.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import com.octavio.tinta.R
import com.octavio.tinta.dp
import com.octavio.tinta.tip

/** Acciones sobre lo elegido con el lazo; flota arriba (o abajo) de lo elegido. */
@SuppressLint("ViewConstructor")
class SelectionBar(context: Context) : LinearLayout(context) {

    interface Callbacks {
        fun onCopySelection()
        fun onCutSelection()
        fun onDuplicateSelection()
        fun onColorSelection(button: View)
        fun onDeleteSelection()

        /** Editar el gráfico o la figura, recortar la imagen o reproducir el audio o video. */
        fun onEditSelection(button: View)
        fun onStickerSelection()
    }

    /** Qué hace el botón de editar según lo elegido. */
    enum class Edit(val icon: Int, val label: String) {
        WIDGET(R.drawable.ic_edit, "Editar"),
        CROP(R.drawable.ic_crop, "Recortar"),
        PLAY(R.drawable.ic_play, "Reproducir"),
    }

    var callbacks: Callbacks? = null

    private val color: ImageButton
    private val edit: ImageButton

    init {
        orientation = HORIZONTAL
        background = context.getDrawable(R.drawable.bg_toolbar)
        elevation = context.dp(12f) // encima del reloj y de las barras fijas: aparece donde se trabaja
        val pad = context.dp(3f).toInt()
        setPadding(pad, pad, pad, pad)
        edit = add(R.drawable.ic_edit, "Editar") { callbacks?.onEditSelection(it) }
        add(R.drawable.ic_cut, "Cortar (mantené apretado con el lazo para pegar)") { callbacks?.onCutSelection() }
        add(R.drawable.ic_copy, "Copiar") { callbacks?.onCopySelection() }
        add(R.drawable.ic_duplicate, "Duplicar") { callbacks?.onDuplicateSelection() }
        color = add(R.drawable.ic_palette, "Cambiar el color") { callbacks?.onColorSelection(it) }
        add(R.drawable.ic_sticker, "Guardar como sticker") { callbacks?.onStickerSelection() }
        add(R.drawable.ic_delete, "Borrar lo elegido") { callbacks?.onDeleteSelection() }
        visibility = GONE
    }

    private fun add(icon: Int, description: String, onClick: (View) -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        background = context.getDrawable(R.drawable.bg_tool)
        val p = context.dp(9f).toInt()
        setPadding(p, p, p, p)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = description
        tip(description)
        setOnClickListener(onClick)
    }.also { addView(it, LayoutParams(context.dp(42f).toInt(), context.dp(42f).toInt())) }

    /**
     * Muestra la barra junto a `target` (lo elegido, en coordenadas del padre), o la esconde si es
     * null. `canColor`: si hay algo a lo que cambiarle el color; `editAs`: qué hace el botón de
     * editar, o null si no aparece.
     */
    fun showFor(target: RectF?, canColor: Boolean, editAs: Edit? = null) {
        if (target == null) {
            visibility = GONE
            return
        }
        color.visibility = if (canColor) VISIBLE else GONE
        edit.visibility = if (editAs != null) VISIBLE else GONE
        editAs?.let {
            edit.setImageResource(it.icon)
            edit.contentDescription = it.label
            edit.tip(it.label)
        }
        visibility = VISIBLE
        val parent = parent as? ViewGroup ?: return
        if (width == 0) {
            // Todavía no se midió: se ubica cuando termine.
            post { showFor(target, canColor, editAs) }
            return
        }
        val gap = context.dp(10f)
        val margin = context.dp(6f)
        var top = target.top - height - gap
        if (top < margin) top = target.bottom + gap
        if (top + height > parent.height - margin) top = (parent.height - height - margin).coerceAtLeast(margin)
        x = (target.centerX() - width / 2f).coerceIn(margin, (parent.width - width - margin).coerceAtLeast(margin))
        y = top
    }
}
