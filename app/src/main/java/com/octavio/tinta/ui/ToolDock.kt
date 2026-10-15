package com.octavio.tinta.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.octavio.tinta.R
import com.octavio.tinta.dp

/**
 * Barra fija pegada a un costado de la hoja con las herramientas más elaboradas, como las paletas
 * de AutoCAD: se esconde contra el borde (queda una pestañita para volver a abrirla) y se puede
 * pasar al otro costado.
 */
@SuppressLint("ViewConstructor")
class ToolDock(context: Context) : FrameLayout(context) {

    enum class Tool(val icon: Int, val label: String) {
        PAGES(R.drawable.ic_pages, "Hojas"),
        ZOOM(R.drawable.ic_zoom, "Lupa"),
        IMAGE(R.drawable.ic_image, "Imagen"),
        GRAPH2D(R.drawable.ic_graph, "Gráfico"),
        GRAPH3D(R.drawable.ic_surface, "Gráfico 3D"),
        SOLID(R.drawable.ic_cube, "Figura 3D"),
        AUDIO(R.drawable.ic_mic, "Grabar"),
        VIDEO(R.drawable.ic_video, "Video"),
        LASER(R.drawable.ic_laser, "Láser"),
        STICKERS(R.drawable.ic_sticker, "Stickers"),
        TIMER(R.drawable.ic_timer, "Reloj"),
    }

    interface Callbacks {
        fun onDockTool(tool: Tool, button: View)
        fun onDockChanged(right: Boolean, collapsed: Boolean)
    }

    var callbacks: Callbacks? = null

    var right = true
        private set
    var collapsed = false
        private set

    private val panel = LinearLayout(context)
    private val tab = ImageView(context)
    private val buttons = HashMap<Tool, View>()
    private val hide = ImageButton(context)
    private val side = ImageButton(context)

    init {
        panel.orientation = LinearLayout.VERTICAL
        panel.gravity = Gravity.CENTER_HORIZONTAL
        panel.elevation = context.dp(6f)
        val pad = context.dp(4f).toInt()
        panel.setPadding(pad, pad, pad, pad)
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for ((b, icon, description) in listOf(
            Triple(hide, R.drawable.ic_chevron, "Esconder la barra"),
            Triple(side, R.drawable.ic_swap, "Pasar la barra al otro costado"),
        )) {
            b.setImageResource(icon)
            b.background = context.getDrawable(R.drawable.bg_tool)
            b.scaleType = ImageView.ScaleType.CENTER
            b.contentDescription = description
            b.tooltipText = description
            b.alpha = 0.7f
            header.addView(b, LinearLayout.LayoutParams(context.dp(30f).toInt(), context.dp(30f).toInt()))
        }
        panel.addView(header)
        for (t in Tool.entries) {
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = context.getDrawable(R.drawable.bg_tool)
                val p = context.dp(4f).toInt()
                setPadding(p, p, p, p)
                contentDescription = t.label
                tooltipText = t.label
                addView(ImageView(context).apply { setImageResource(t.icon) }, LinearLayout.LayoutParams(context.dp(24f).toInt(), context.dp(24f).toInt()))
                addView(TextView(context).apply {
                    text = t.label
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setTextColor(context.getColor(R.color.ink))
                    maxLines = 1
                })
                setOnClickListener { callbacks?.onDockTool(t, this) }
            }
            buttons[t] = item
            panel.addView(item, LinearLayout.LayoutParams(context.dp(64f).toInt(), context.dp(52f).toInt()).apply {
                topMargin = context.dp(2f).toInt()
            })
        }
        tab.setImageResource(R.drawable.ic_chevron)
        tab.scaleType = ImageView.ScaleType.CENTER
        tab.elevation = context.dp(6f)
        tab.contentDescription = "Mostrar las herramientas"
        tab.setOnClickListener { setState(right, collapsed = false) }
        hide.setOnClickListener { setState(right, collapsed = true) }
        side.setOnClickListener { setState(!right, collapsed) }
        addView(panel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        addView(tab, LayoutParams(context.dp(22f).toInt(), context.dp(72f).toInt(), Gravity.CENTER_VERTICAL))
        apply()
    }

    /** Marca la herramienta que está activa (el láser), o ninguna. */
    fun setActive(tool: Tool?) {
        for ((t, v) in buttons) if (t != Tool.TIMER && t != Tool.ZOOM) v.isSelected = t == tool
    }

    /** Cambia el rótulo de una herramienta (por ejemplo, el tiempo que le queda al reloj); null vuelve al de siempre. */
    fun setLabel(tool: Tool, text: String?, highlighted: Boolean = false) {
        val item = buttons[tool] as? LinearLayout ?: return
        (item.getChildAt(1) as? TextView)?.text = text ?: tool.label
        item.isSelected = highlighted
    }

    fun setState(right: Boolean, collapsed: Boolean) {
        val changed = right != this.right || collapsed != this.collapsed
        this.right = right
        this.collapsed = collapsed
        apply()
        if (changed) callbacks?.onDockChanged(right, collapsed)
    }

    private fun apply() {
        panel.visibility = if (collapsed) GONE else VISIBLE
        tab.visibility = if (collapsed) VISIBLE else GONE
        // Esquinas redondeadas sólo del lado de adentro.
        panel.background = context.getDrawable(if (right) R.drawable.bg_dock_right else R.drawable.bg_dock_left)
        tab.background = context.getDrawable(if (right) R.drawable.bg_dock_right else R.drawable.bg_dock_left)
        // La flecha apunta hacia donde se mueve: a esconderse o a salir.
        hide.rotation = if (right) 0f else 180f
        tab.rotation = if (right) 180f else 0f
        val lp = layoutParams as? LayoutParams
        if (lp != null) {
            lp.gravity = (if (right) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            layoutParams = lp
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        apply()
    }
}
