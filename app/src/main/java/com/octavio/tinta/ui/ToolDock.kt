package com.octavio.tinta.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.octavio.tinta.R
import com.octavio.tinta.dp
import com.octavio.tinta.tip
import kotlin.math.abs

/**
 * Barra fija pegada a un costado de la hoja con las herramientas más elaboradas, como las paletas
 * de AutoCAD: se esconde contra el borde (queda una pestañita para volver a abrirla) y se puede
 * pasar al otro costado.
 */
@SuppressLint("ViewConstructor")
class ToolDock(context: Context) : FrameLayout(context) {

    enum class Tool(val icon: Int, val label: String, val hint: String = label) {
        PAGES(R.drawable.ic_pages, "Hojas"),
        ZOOM(R.drawable.ic_zoom, "Lupa"),
        IMAGE(R.drawable.ic_image, "Foto/video", "Agregar una imagen o un video"),
        TEXT(R.drawable.ic_text, "Texto", "Escribir con el teclado"),
        GRAPH2D(R.drawable.ic_graph, "Gráfico"),
        GRAPH3D(R.drawable.ic_surface, "Gráfico 3D"),
        SOLID(R.drawable.ic_cube, "Figura"),
        AUDIO(R.drawable.ic_mic, "Grabar"),
        LASER(R.drawable.ic_laser, "Láser"),
        STICKERS(R.drawable.ic_sticker, "Stickers"),
        TIMER(R.drawable.ic_timer, "Reloj"),
    }

    interface Callbacks {
        fun onDockTool(tool: Tool, button: View)
        fun onDockChanged(right: Boolean, collapsed: Boolean)

        /** Se cambió el orden de las herramientas (mantener apretada una y arrastrarla). */
        fun onDockOrder(order: List<Tool>)

        /** Se movió la barra para arriba o para abajo (0 arriba, 1 abajo). */
        fun onDockBias(bias: Float)
    }

    var callbacks: Callbacks? = null

    var right = true
        private set
    var collapsed = false
        private set

    /** De 0 (arriba) a 1 (abajo). */
    private var bias = 0.5f

    /** Después de acomodarla, para correr la barra de lápices si quedó encima. */
    var onPlaced: (() -> Unit)? = null

    private val panel = LinearLayout(context)
    private val tab = ImageView(context)
    private val buttons = HashMap<Tool, View>()

    /** Las herramientas en el orden en que se ven. */
    private val order = ArrayList(Tool.entries)
    private val itemSlot = context.dp(54f) // alto de cada una más su margen

    private val reorder = Reorder(
        items = { order.mapNotNull { buttons[it] } },
        vertical = { true },
        slot = { itemSlot },
        onMoved = { from, to ->
            order.add(to, order.removeAt(from))
            arrange()
            callbacks?.onDockOrder(order.toList())
        },
    )
    private val hide = ImageButton(context)
    private val side = ImageButton(context)

    init {
        panel.orientation = LinearLayout.VERTICAL
        panel.gravity = Gravity.CENTER_HORIZONTAL
        panel.elevation = context.dp(6f)
        val pad = context.dp(4f).toInt()
        panel.setPadding(pad, pad, pad, pad)
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            contentDescription = "Mover la barra"
            tip("Arrastrá para subir o bajar la barra")
        }
        for ((b, icon, description) in listOf(
            Triple(hide, R.drawable.ic_chevron, "Esconder la barra"),
            Triple(side, R.drawable.ic_swap, "Pasar la barra al otro costado"),
        )) {
            b.setImageResource(icon)
            b.background = context.getDrawable(R.drawable.bg_tool)
            b.scaleType = ImageView.ScaleType.CENTER
            b.contentDescription = description
            b.tip(description)
            b.alpha = 0.7f
            header.addView(b, LinearLayout.LayoutParams(context.dp(30f).toInt(), context.dp(30f).toInt()))
        }
        panel.addView(header)
        dragVertically(header)
        for (t in Tool.entries) {
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = context.getDrawable(R.drawable.bg_tool)
                val p = context.dp(4f).toInt()
                setPadding(p, p, p, p)
                contentDescription = t.hint
                tip(t.hint)
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
            reorder.attach(item)
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
        dragVertically(tab)
        hide.setOnClickListener { setState(right, collapsed = true) }
        side.setOnClickListener { setState(!right, collapsed) }
        addView(panel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        addView(tab, LayoutParams(context.dp(26f).toInt(), context.dp(64f).toInt(), Gravity.CENTER_VERTICAL))
        apply()
    }

    fun setBias(value: Float) {
        bias = value.coerceIn(0f, 1f)
        placeY()
    }

    /** Lo que se ve de la barra (el panel o la pestaña), en coordenadas del padre. */
    fun occupied(): RectF? {
        val child = if (collapsed) tab else panel
        if (visibility != VISIBLE || child.visibility != VISIBLE || child.width == 0) return null
        val x0 = x + child.x
        val y0 = y + child.y
        return RectF(x0, y0, x0 + child.width, y0 + child.height)
    }

    /** El color elegido para las barras de herramientas. */
    fun paint(color: Int) {
        Chrome.paint(panel, color)
        Chrome.paint(tab, color)
    }

    /** Pone las herramientas en este orden (las que falten, al final como siempre). */
    fun setOrder(saved: List<Tool>) {
        order.clear()
        order.addAll(saved.distinct())
        for (t in Tool.entries) if (t !in order) order.add(t)
        arrange()
    }

    private fun arrange() {
        for (t in order) {
            val v = buttons[t] ?: continue
            panel.removeView(v)
            panel.addView(v)
        }
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
        // Esquinas redondeadas sólo del lado de adentro. La pestaña usa el mismo fondo que las hojas.
        val bg = if (right) R.drawable.bg_dock_right else R.drawable.bg_dock_left
        panel.background = context.getDrawable(bg)
        tab.background = context.getDrawable(bg)
        // La flecha apunta hacia donde se mueve: a esconderse o a salir.
        hide.rotation = if (right) 0f else 180f
        tab.rotation = if (right) 180f else 0f
        val side = if (right) Gravity.END else Gravity.START
        (tab.layoutParams as? LayoutParams)?.let {
            it.gravity = side or Gravity.CENTER_VERTICAL
            tab.layoutParams = it
        }
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        val edge = context.dp(10f).toInt()
        if (collapsed) {
            // Igual que la tira de hojas: una columna del alto de la hoja, pegada al borde.
            lp.width = FrameLayout.LayoutParams.WRAP_CONTENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = side
            lp.setMargins(0, edge, 0, edge)
            translationY = 0f
        } else {
            lp.width = FrameLayout.LayoutParams.WRAP_CONTENT
            lp.height = FrameLayout.LayoutParams.WRAP_CONTENT
            lp.gravity = side or Gravity.TOP
            lp.setMargins(0, 0, 0, 0)
            tab.translationY = 0f
        }
        layoutParams = lp
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        placeY()
        onPlaced?.invoke()
    }

    private fun placeY() {
        if (collapsed) {
            translationY = 0f
            val travel = (height - tab.height).coerceAtLeast(0)
            tab.translationY = (bias - 0.5f) * travel
        } else {
            tab.translationY = 0f
            val parent = parent as? View ?: return
            val margin = context.dp(8f)
            val free = (parent.height - height - 2 * margin).coerceAtLeast(0f)
            translationY = margin + bias * free
        }
    }

    private fun travel(): Float {
        val parent = parent as? View ?: return 1f
        return if (collapsed) {
            (height - tab.height).coerceAtLeast(1).toFloat()
        } else {
            val margin = context.dp(8f)
            (parent.height - height - 2 * margin).coerceAtLeast(1f)
        }
    }

    /** Arrastrar el encabezado sube o baja la barra. Un toque sigue apretando el botón de abajo. */
    @SuppressLint("ClickableViewAccessibility")
    private fun dragVertically(handle: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downY = 0f
        var start = 0f
        var dragging = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    start = bias
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (!dragging && abs(dy) > slop) dragging = true
                    if (dragging) {
                        bias = (start + dy / travel()).coerceIn(0f, 1f)
                        placeY()
                        onPlaced?.invoke()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging && e.actionMasked == MotionEvent.ACTION_UP) {
                        if (handle is ViewGroup) {
                            for (i in 0 until handle.childCount) {
                                val c = handle.getChildAt(i)
                                if (e.x >= c.left && e.x < c.right && e.y >= c.top && e.y < c.bottom) c.performClick()
                            }
                        } else {
                            handle.performClick()
                        }
                    } else if (dragging) {
                        callbacks?.onDockBias(bias)
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        apply()
    }
}
