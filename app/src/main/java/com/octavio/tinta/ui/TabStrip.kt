package com.octavio.tinta.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.octavio.tinta.R
import com.octavio.tinta.dp
import com.octavio.tinta.tip
import kotlin.math.abs

/**
 * Las notas abiertas en esta mitad de la pantalla, como pestañas: tocar una la muestra, tocar la
 * que se está mostrando la renombra, la cruz la cierra. Mantenerla apretada y arrastrar la cambia
 * de lugar; soltarla sin moverla abre el menú.
 */
class TabStrip @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : HorizontalScrollView(context, attrs) {

    interface Callbacks {
        fun onTabClick(id: String)
        fun onTabClose(id: String)
        fun onTabMenu(id: String, anchor: View)
        fun onAddTab(anchor: View)

        /** Las pestañas, de izquierda a derecha, después de arrastrar una. */
        fun onTabsReordered(ids: List<String>)
    }

    var callbacks: Callbacks? = null
    private var fontId: String? = null
    private var lastTabs: List<Pair<String, String>> = emptyList()
    private var lastActive: String? = null

    /** La letra de las pestañas, la misma de las herramientas y del menú. */
    fun setFont(id: String?) {
        if (fontId == id) return
        fontId = id
        if (lastTabs.isNotEmpty()) setTabs(lastTabs, lastActive)
    }
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
    @SuppressLint("ClickableViewAccessibility")
    fun setTabs(tabs: List<Pair<String, String>>, active: String?) {
        cancelDrag()
        lastTabs = tabs
        lastActive = active
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
                    typeface = Fonts.typeface(fontId)
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
                setOnTouchListener { v, e -> onTabTouch(v, id, e) }
                setOnLongClickListener {
                    beginDrag(it, id, lastRaw)
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

    private var dragTab: View? = null
    private var dragId: String? = null
    private var dragFrom = -1
    private var dragTo = -1
    private var dragMoved = false
    private var dragStartRaw = 0f
    private var lastRaw = 0f
    private var grabOffset = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    /** Sigue el dedo cerca del borde para alcanzar las pestañas que no entran. */
    private val edgeScroll = object : Runnable {
        override fun run() {
            val tab = dragTab ?: return
            if (!dragMoved) {
                postDelayed(this, 16)
                return
            }
            val tabAt = IntArray(2)
            tab.getLocationOnScreen(tabAt)
            val center = tabAt[0] + tab.width / 2
            val mine = IntArray(2)
            getLocationOnScreen(mine)
            val x = center - mine[0]
            val zone = context.dp(40f)
            val speed = context.dp(10f).toInt().coerceAtLeast(1)
            when {
                x < zone -> scrollBy(-speed, 0)
                x > width - zone -> scrollBy(speed, 0)
            }
            placeDrag(lastRaw)
            postDelayed(this, 16)
        }
    }

    private fun onTabTouch(tab: View, id: String, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastRaw = e.rawX
                dragStartRaw = e.rawX
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                lastRaw = e.rawX
                if (dragTab !== tab) return false
                tab.parent?.requestDisallowInterceptTouchEvent(true)
                if (!dragMoved && abs(e.rawX - dragStartRaw) > slop) dragMoved = true
                placeDrag(e.rawX)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragTab !== tab || dragId != id) return false
                finishDrag(e.actionMasked == MotionEvent.ACTION_UP)
                return true
            }
        }
        return false
    }

    private fun beginDrag(tab: View, id: String, rawX: Float) {
        if (dragTab != null) return
        dragTab = tab
        dragId = id
        dragFrom = row.indexOfChild(tab)
        dragTo = dragFrom
        dragMoved = false
        dragStartRaw = rawX
        lastRaw = rawX
        val mine = IntArray(2)
        getLocationOnScreen(mine)
        grabOffset = rawX - (mine[0] - scrollX + tab.left)
        tab.animate().cancel()
        tab.translationZ = context.dp(8f)
        tab.parent?.requestDisallowInterceptTouchEvent(true)
        removeCallbacks(edgeScroll)
        post(edgeScroll)
    }

    private fun placeDrag(rawX: Float) {
        val tab = dragTab ?: return
        lastRaw = rawX
        val mine = IntArray(2)
        getLocationOnScreen(mine)
        tab.translationX = rawX - grabOffset - (mine[0] - scrollX + tab.left)
        val to = targetIndex(tab)
        if (to == dragTo) return
        dragTo = to
        val n = row.childCount - 1
        val gap = tab.width + context.dp(4f)
        for (i in 0 until n) {
            val child = row.getChildAt(i)
            if (child === tab) continue
            val shift = when {
                to > dragFrom && i in (dragFrom + 1)..to -> -gap
                to < dragFrom && i in to until dragFrom -> gap
                else -> 0f
            }
            child.animate().translationX(shift).setDuration(90).start()
        }
    }

    /** Índice final: cuántas otras pestañas quedan a la izquierda del dedo. */
    private fun targetIndex(tab: View): Int {
        val center = tab.left + tab.translationX + tab.width / 2f
        var index = 0
        val n = (row.childCount - 1).coerceAtLeast(0)
        for (i in 0 until n) {
            val child = row.getChildAt(i)
            if (child === tab) continue
            if (center > child.left + child.width / 2f) index++
        }
        return index.coerceIn(0, (n - 1).coerceAtLeast(0))
    }

    private fun finishDrag(up: Boolean) {
        val tab = dragTab ?: return
        val id = dragId ?: return
        val from = dragFrom
        val moved = dragMoved
        val to = if (moved) targetIndex(tab) else from
        removeCallbacks(edgeScroll)
        dragTab = null
        dragId = null
        dragMoved = false
        dragFrom = -1
        dragTo = -1
        if (!moved || to == from) {
            resetShift()
            if (up && !moved) callbacks?.onTabMenu(id, tab)
            return
        }
        val ids = lastTabs.map { it.first }.toMutableList()
        if (from !in ids.indices || ids[from] != id) {
            resetShift()
            return
        }
        ids.removeAt(from)
        ids.add(to.coerceIn(0, ids.size), id)
        callbacks?.onTabsReordered(ids)
    }

    private fun cancelDrag() {
        if (dragTab == null) return
        removeCallbacks(edgeScroll)
        dragTab = null
        dragId = null
        dragMoved = false
        dragFrom = -1
        dragTo = -1
    }

    override fun onDetachedFromWindow() {
        cancelDrag()
        super.onDetachedFromWindow()
    }

    private fun resetShift() {
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            child.animate().cancel()
            child.translationX = 0f
            child.translationZ = 0f
        }
    }
}
