package com.octavio.tinta.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.octavio.tinta.R
import com.octavio.tinta.dp
import com.octavio.tinta.tip
import kotlin.math.abs

/**
 * Las hojas de la nota en miniatura, en una tira pegada a un costado que se puede esconder (queda
 * una pestañita). Tocar una lleva a esa hoja; mantenerla apretada y arrastrarla la cambia de lugar.
 */
@SuppressLint("ViewConstructor")
class PageStrip(context: Context, private val thumbs: PageThumbs) : FrameLayout(context) {

    interface Callbacks {
        fun onPageClick(k: Int)
        fun onPageMove(from: Int, to: Int)
        fun onAddPage()
        fun onStripCollapsed(collapsed: Boolean)

        /** Se movió la tira para arriba o para abajo (0 arriba, 1 abajo). */
        fun onStripBias(bias: Float)
    }

    var callbacks: Callbacks? = null

    var collapsed = false
        private set

    /** De qué lado está: a la izquierda salvo que ahí esté la barra lateral. */
    private var left = true

    /** De 0 (arriba) a 1 (abajo). */
    private var bias = 0.5f

    /** Después de acomodarla, para correr la barra de lápices si quedó encima. */
    var onPlaced: (() -> Unit)? = null

    private var face: Typeface = Typeface.SANS_SERIF

    private var count = 0
    private var current = 0

    /** Alto / ancho de las hojas, para el tamaño de las miniaturas. */
    private var aspect = 1.414f

    /** El orden que se ve mientras se arrastra una: qué hoja hay en cada lugar. */
    private val order = ArrayList<Int>()
    private var dragFrom = -1
    private var dragTo = -1

    private val panel = LinearLayout(context)
    private val list = RecyclerView(context)
    private val tab = ImageView(context)
    private val hide = ImageButton(context)
    private val adapter = Adapter()
    private val thumbWidth = context.dp(THUMB_DP).toInt()

    init {
        panel.orientation = LinearLayout.VERTICAL
        panel.elevation = context.dp(6f)
        val pad = context.dp(4f).toInt()
        panel.setPadding(pad, pad, pad, pad)
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "Hojas"
                textSize = 13f
                setTextColor(context.getColor(R.color.muted))
                setPadding(context.dp(6f).toInt(), 0, 0, 0)
                contentDescription = "Mover las hojas"
                tip("Arrastrá para subir o bajar las hojas")
                dragVertically(this)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            hide.setImageResource(R.drawable.ic_chevron)
            hide.background = context.getDrawable(R.drawable.bg_tool)
            hide.scaleType = ImageView.ScaleType.CENTER
            hide.contentDescription = "Esconder las miniaturas"
            hide.tip(hide.contentDescription)
            hide.alpha = 0.7f
            hide.setOnClickListener { setCollapsed(true, notify = true) }
            addView(hide, LinearLayout.LayoutParams(context.dp(30f).toInt(), context.dp(30f).toInt()))
        }
        panel.addView(header)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        list.isVerticalScrollBarEnabled = true
        ItemTouchHelper(Mover()).attachToRecyclerView(list)
        panel.addView(list, LinearLayout.LayoutParams(thumbWidth + context.dp(12f).toInt(), context.dp(160f).toInt()))
        panel.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_add)
            background = context.getDrawable(R.drawable.bg_tool)
            contentDescription = "Agregar una hoja al final"
            tip(contentDescription)
            setOnClickListener { callbacks?.onAddPage() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(40f).toInt()))
        tab.setImageResource(R.drawable.ic_pages)
        tab.scaleType = ImageView.ScaleType.CENTER
        tab.elevation = context.dp(6f)
        tab.contentDescription = "Mostrar las miniaturas de las hojas"
        tab.setOnClickListener { setCollapsed(false, notify = true) }
        dragVertically(tab)
        addView(panel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(tab, LayoutParams(context.dp(26f).toInt(), context.dp(64f).toInt(), Gravity.CENTER_VERTICAL))
        thumbs.onReady = { k -> order.indexOf(k).takeIf { it >= 0 }?.let(adapter::notifyItemChanged) }
        apply()
    }

    /** El color elegido para las barras de herramientas. */
    fun paint(color: Int) {
        Chrome.paint(panel, color)
        Chrome.paint(tab, color)
    }

    fun setSide(left: Boolean) {
        if (this.left == left) return
        this.left = left
        apply()
    }

    fun setBias(value: Float) {
        bias = value.coerceIn(0f, 1f)
        placeY()
    }

    fun setFont(id: String?) {
        face = Fonts.typeface(id)
        list.invalidate()
    }

    /** Lo que se ve de la tira (el panel o la pestaña), en coordenadas del padre. */
    fun occupied(): RectF? {
        val child = if (collapsed) tab else panel
        if (visibility != VISIBLE || child.visibility != VISIBLE || child.width == 0) return null
        val x0 = x + child.x
        val y0 = y + child.y
        return RectF(x0, y0, x0 + child.width, y0 + child.height)
    }

    fun setCollapsed(collapsed: Boolean, notify: Boolean = false) {
        if (this.collapsed == collapsed) return
        this.collapsed = collapsed
        apply()
        if (notify) callbacks?.onStripCollapsed(collapsed)
    }

    /** Cuántas hojas hay y cómo son. */
    fun setPages(count: Int, aspect: Float) {
        val changed = count != this.count || aspect != this.aspect
        this.count = count
        this.aspect = aspect
        if (dragFrom < 0) {
            order.clear()
            order.addAll(0 until count)
        }
        if (changed) {
            @Suppress("NotifyDataSetChanged") // cambiaron todas: otro tamaño u otra cantidad
            adapter.notifyDataSetChanged()
        }
    }

    /** Repinta todas (después de un cambio en la nota; las que no cambiaron salen del caché). */
    fun refresh() {
        for (i in order.indices) adapter.notifyItemChanged(i)
    }

    /** Marca la hoja que se está viendo y la deja a la vista en la tira. */
    fun setCurrent(k: Int) {
        if (k == current) return
        val old = order.indexOf(current)
        current = k
        if (old >= 0) adapter.notifyItemChanged(old)
        val now = order.indexOf(k)
        if (now >= 0) {
            adapter.notifyItemChanged(now)
            val lm = list.layoutManager as LinearLayoutManager
            if (now < lm.findFirstCompletelyVisibleItemPosition() || now > lm.findLastCompletelyVisibleItemPosition()) list.scrollToPosition(now)
        }
    }

    private fun apply() {
        panel.visibility = if (collapsed) GONE else VISIBLE
        tab.visibility = if (collapsed) VISIBLE else GONE
        val bg = if (left) R.drawable.bg_dock_left else R.drawable.bg_dock_right
        panel.background = context.getDrawable(bg)
        tab.background = context.getDrawable(bg)
        hide.rotation = if (left) 180f else 0f
        val side = if (left) Gravity.START else Gravity.END
        (tab.layoutParams as? LayoutParams)?.let {
            it.gravity = side or Gravity.CENTER_VERTICAL
            tab.layoutParams = it
        }
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        val edge = context.dp(10f).toInt()
        if (collapsed) {
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
        if (!collapsed) fitList()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!collapsed) fitList()
        placeY()
        onPlaced?.invoke()
    }

    /** La lista no ocupa toda la hoja: así la tira se puede subir o bajar. */
    private fun fitList() {
        val parentH = (parent as? View)?.height ?: return
        if (parentH <= 0) return
        val chrome = context.dp(8f + 30f + 40f + 8f).toInt()
        val cap = (parentH * 0.75f).toInt() - chrome
        val row = (thumbWidth * aspect).toInt() + context.dp(26f).toInt()
        val want = (count.coerceAtLeast(1) * row).coerceAtMost(cap.coerceAtLeast(row.coerceAtMost(parentH / 2)))
        val lp = list.layoutParams ?: return
        if (lp.height == want) return
        lp.height = want
        list.layoutParams = lp
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
                    if (!dragging && e.actionMasked == MotionEvent.ACTION_UP) handle.performClick()
                    else if (dragging) callbacks?.onStripBias(bias)
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

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = order.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(Thumb(parent.context))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val k = order[position]
            val view = holder.itemView as Thumb
            view.page = k
            view.selectedPage = k == current
            view.useTypeface(face)
            view.bitmap = thumbs.get(k)
            view.layoutParams = RecyclerView.LayoutParams(thumbWidth + context.dp(12f).toInt(), (thumbWidth * aspect).toInt() + context.dp(26f).toInt())
            view.setOnClickListener { callbacks?.onPageClick(k) }
            view.contentDescription = "Hoja ${k + 1}"
            view.invalidate()
        }
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view)

    /** Arrastrar para cambiar el orden: se ve en la tira mientras tanto y se aplica al soltar. */
    private inner class Mover : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            if (a < 0 || b < 0) return false
            if (dragFrom < 0) dragFrom = order[a]
            val k = order.removeAt(a)
            order.add(b, k)
            dragTo = b
            adapter.notifyItemMoved(a, b)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(rv, viewHolder)
            val from = dragFrom
            val to = dragTo
            dragFrom = -1
            dragTo = -1
            if (from >= 0 && to >= 0 && from != to) callbacks?.onPageMove(from, to)
            order.clear()
            order.addAll(0 until count)
            @Suppress("NotifyDataSetChanged")
            adapter.notifyDataSetChanged()
        }
    }

    /** Una miniatura con su número; con borde si es la hoja que se está viendo. */
    @SuppressLint("ViewConstructor")
    private class Thumb(context: Context) : View(context) {
        var page = 0
        var selectedPage = false
        var bitmap: android.graphics.Bitmap? = null
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(1f)
            color = context.getColor(R.color.card_stroke)
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(2.5f)
            color = context.getColor(R.color.accent)
        }
        private val blank = Paint().apply { color = 0xFFFDFBF7.toInt() }
        private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = context.dp(12f)
            color = context.getColor(R.color.muted)
        }

        fun useTypeface(face: Typeface) {
            if (number.typeface == face) return
            number.typeface = face
            invalidate()
        }
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        override fun onDraw(canvas: Canvas) {
            val m = context.dp(6f)
            val r = android.graphics.RectF(m, m, width - m, height - context.dp(20f))
            val b = bitmap
            if (b != null) canvas.drawBitmap(b, null, r, bitmapPaint) else canvas.drawRect(r, blank)
            canvas.drawRect(r, if (selectedPage) ring else edge)
            canvas.drawText("${page + 1}", width / 2f, height - context.dp(5f), number)
        }
    }

    private companion object {
        const val THUMB_DP = 96f
    }
}
