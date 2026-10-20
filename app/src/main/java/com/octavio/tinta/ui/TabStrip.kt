package com.octavio.tinta.ui

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.octavio.tinta.R
import com.octavio.tinta.dp
import com.octavio.tinta.tip

/**
 * Las notas abiertas en esta mitad de la pantalla, como pestañas: tocar una la muestra, tocar la
 * que se está mostrando la renombra, la cruz la cierra y mantenerla apretada da más opciones.
 */
class TabStrip @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : HorizontalScrollView(context, attrs) {

    interface Callbacks {
        fun onTabClick(id: String)
        fun onTabClose(id: String)
        fun onTabMenu(id: String, anchor: View)
        fun onAddTab(anchor: View)
    }

    var callbacks: Callbacks? = null
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        addView(row)
    }

    /** Color de la barra de arriba y de la pestaña elegida (Configuración); null: los de siempre. */
    var barColor: Int? = null
    var activeColor: Int? = null

    /** `tabs`: (id, título) en orden. */
    fun setTabs(tabs: List<Pair<String, String>>, active: String?) {
        row.removeAllViews()
        var activeView: View? = null
        for ((id, title) in tabs) {
            val tab = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = context.getDrawable(R.drawable.bg_tab)
                isSelected = id == active
                if (id == active) activeColor?.let { backgroundTintList = android.content.res.ColorStateList.valueOf(it) }
                val p = context.dp(10f).toInt()
                setPadding(p, 0, context.dp(2f).toInt(), 0)
                addView(TextView(context).apply {
                    text = title
                    textSize = if (id == active) 16f else 14f
                    val under = if (id == active) activeColor ?: barColor else barColor
                    val normal = context.getColor(if (id == active) R.color.ink else R.color.muted)
                    setTextColor(if (under == null) normal else if (id == active) Chrome.ink(under) else Chrome.muted(under))
                    maxWidth = context.dp(if (id == active) 260f else 170f).toInt()
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(ImageButton(context).apply {
                    setImageResource(R.drawable.ic_close)
                    background = context.getDrawable(R.drawable.bg_tool)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    val q = context.dp(7f).toInt()
                    setPadding(q, q, q, q)
                    alpha = 0.6f
                    contentDescription = "Cerrar «$title»"
                    (if (id == active) activeColor ?: barColor else barColor)?.let { imageTintList = android.content.res.ColorStateList.valueOf(Chrome.ink(it)) }
                    setOnClickListener { callbacks?.onTabClose(id) }
                }, LinearLayout.LayoutParams(context.dp(30f).toInt(), context.dp(30f).toInt()))
                setOnClickListener { callbacks?.onTabClick(id) }
                setOnLongClickListener {
                    callbacks?.onTabMenu(id, it)
                    true
                }
            }
            row.addView(tab, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, context.dp(40f).toInt()).apply {
                marginEnd = context.dp(4f).toInt()
            })
            if (id == active) activeView = tab
        }
        row.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_add)
            background = context.getDrawable(R.drawable.bg_tool)
            contentDescription = "Abrir otra nota en una pestaña"
            barColor?.let { imageTintList = android.content.res.ColorStateList.valueOf(Chrome.ink(it)) }
            tip(contentDescription)
            setOnClickListener { callbacks?.onAddTab(it) }
        }, LinearLayout.LayoutParams(context.dp(40f).toInt(), context.dp(40f).toInt()))
        activeView?.let { v -> post { smoothScrollTo((v.left - context.dp(40f).toInt()).coerceAtLeast(0), 0) } }
    }
}
