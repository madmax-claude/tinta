package com.octavio.tinta.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import com.octavio.tinta.R
import com.octavio.tinta.canvas.NoteCanvasView
import com.octavio.tinta.dp
import com.octavio.tinta.model.Pen
import kotlin.math.hypot

/**
 * Barra de herramientas que flota sobre la hoja. Se arrastra desde la manija a cualquier lugar; un
 * toque en la manija la pone vertical u horizontal. Tiene los lápices (cada uno con su color
 * fijo), el botón para agregar otro, el borrador, el lazo y deshacer/rehacer.
 */
@SuppressLint("ViewConstructor")
class FloatingToolbar(context: Context, private val dark: Boolean) : LinearLayout(context) {

    interface Callbacks {
        fun onPenClick(index: Int, button: View)

        /** Mantener apretado un lápiz: sus opciones rápidas (quitarlo). */
        fun onPenLongClick(index: Int, button: View)

        /** Mantener apretada la manija: opciones de la barra (borrar todos los lápices). */
        fun onGripLongPress(grip: View)
        fun onAddPen(button: View)
        fun onEraserClick(button: View)
        fun onLassoClick(button: View)
        fun onUndo()
        fun onRedo()

        /** Terminó de moverla; (fx, fy) es su posición como fracción del espacio libre. */
        fun onMoved(fx: Float, fy: Float)
        fun onOrientationChanged(vertical: Boolean)
    }

    var callbacks: Callbacks? = null

    private val grip = ImageView(context)
    private val pensBox = LinearLayout(context)
    private val add = button(R.drawable.ic_add, "Agregar lápiz")
    private val eraser = button(R.drawable.ic_eraser, "Borrador")
    private val lasso = button(R.drawable.ic_lasso, "Lazo para elegir y mover")
    val undo = button(R.drawable.ic_undo, context.getString(R.string.undo))
    val redo = button(R.drawable.ic_redo, context.getString(R.string.redo))
    private val dividers = listOf(View(context), View(context))
    private val penButtons = ArrayList<PenButton>()

    /** Posición como fracción del espacio libre, para que sobreviva a girar la tablet. */
    private var fx = 0.5f
    private var fy = 0f

    var vertical = false
        set(value) {
            field = value
            applyOrientation()
        }

    init {
        background = context.getDrawable(R.drawable.bg_toolbar)
        elevation = context.dp(6f)
        val pad = context.dp(4f).toInt()
        setPadding(pad, pad, pad, pad)
        gravity = android.view.Gravity.CENTER

        grip.setImageResource(R.drawable.ic_drag)
        grip.contentDescription = "Mover la barra (tocar para girarla; mantener para más opciones)"
        grip.alpha = 0.6f
        grip.scaleType = ImageView.ScaleType.CENTER
        setupDrag()

        add.setOnClickListener { callbacks?.onAddPen(it) }
        eraser.setOnClickListener { callbacks?.onEraserClick(it) }
        lasso.setOnClickListener { callbacks?.onLassoClick(it) }
        undo.setOnClickListener { callbacks?.onUndo() }
        redo.setOnClickListener { callbacks?.onRedo() }
        for (d in dividers) d.setBackgroundColor(context.getColor(R.color.divider))

        addView(grip)
        addView(pensBox)
        addView(add)
        addView(dividers[0])
        addView(eraser)
        addView(lasso)
        addView(dividers[1])
        addView(undo)
        addView(redo)
        applyOrientation()
    }

    private fun button(icon: Int, description: String) = ImageButton(context).apply {
        setImageResource(icon)
        background = context.getDrawable(R.drawable.bg_tool)
        val p = context.dp(10f).toInt()
        setPadding(p, p, p, p)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = description
        tooltipText = description
    }

    fun setPens(pens: List<Pen>, selected: Int, mode: NoteCanvasView.Mode) {
        while (penButtons.size > pens.size) pensBox.removeView(penButtons.removeAt(penButtons.lastIndex))
        while (penButtons.size < pens.size) {
            val b = PenButton(context, dark)
            val index = penButtons.size
            b.setOnClickListener { callbacks?.onPenClick(index, it) }
            b.setOnLongClickListener {
                callbacks?.onPenLongClick(index, it)
                true
            }
            penButtons.add(b)
            pensBox.addView(b, itemParams())
        }
        pens.forEachIndexed { i, p ->
            penButtons[i].pen = p
            penButtons[i].isSelected = mode == NoteCanvasView.Mode.PEN && i == selected
        }
        eraser.isSelected = mode == NoteCanvasView.Mode.ERASER
        lasso.isSelected = mode == NoteCanvasView.Mode.LASSO
        post { clampToParent() }
    }

    fun penButton(index: Int): View? = penButtons.getOrNull(index)

    fun setUndoState(canUndo: Boolean, canRedo: Boolean) {
        undo.isEnabled = canUndo
        redo.isEnabled = canRedo
        undo.alpha = if (canUndo) 1f else 0.35f
        redo.alpha = if (canRedo) 1f else 0.35f
    }

    private fun itemParams(): LayoutParams {
        val s = context.dp(46f).toInt()
        return LayoutParams(s, s)
    }

    private fun applyOrientation() {
        orientation = if (vertical) VERTICAL else HORIZONTAL
        pensBox.orientation = orientation
        for (v in listOf(grip, add, eraser, lasso, undo, redo)) v.layoutParams = itemParams()
        for (b in penButtons) b.layoutParams = itemParams()
        val long = context.dp(28f).toInt()
        val thin = context.dp(1f).toInt()
        val margin = context.dp(6f).toInt()
        for (d in dividers) {
            d.layoutParams = if (vertical) {
                LayoutParams(long, thin).apply { setMargins(0, margin, 0, margin) }
            } else {
                LayoutParams(thin, long).apply { setMargins(margin, 0, margin, 0) }
            }
        }
        requestLayout()
        post { clampToParent() }
    }

    /** Ubica la barra según la fracción guardada. Llamar cuando cambia el tamaño del lienzo. */
    fun placeAt(fx: Float, fy: Float) {
        this.fx = fx.coerceIn(0f, 1f)
        this.fy = fy.coerceIn(0f, 1f)
        clampToParent()
    }

    private fun clampToParent() {
        val parent = parent as? ViewGroup ?: return
        val freeX = (parent.width - width).coerceAtLeast(0)
        val freeY = (parent.height - height).coerceAtLeast(0)
        x = fx * freeX
        y = fy * freeY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        var held = false
        val longPress = Runnable {
            if (dragging) return@Runnable
            held = true
            grip.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            callbacks?.onGripLongPress(grip)
        }
        grip.setOnTouchListener { _, e ->
            val parent = parent as? ViewGroup ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = x
                    startY = y
                    dragging = false
                    held = false
                    grip.alpha = 1f
                    grip.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && !held && hypot(dx, dy) > slop) {
                        dragging = true
                        grip.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        x = (startX + dx).coerceIn(0f, (parent.width - width).coerceAtLeast(0).toFloat())
                        y = (startY + dy).coerceIn(0f, (parent.height - height).coerceAtLeast(0).toFloat())
                    }
                }
                MotionEvent.ACTION_UP -> {
                    grip.alpha = 0.6f
                    grip.removeCallbacks(longPress)
                    if (held) {
                        // Ya se mostraron las opciones: soltar no gira la barra.
                    } else if (dragging) {
                        val freeX = (parent.width - width).coerceAtLeast(1)
                        val freeY = (parent.height - height).coerceAtLeast(1)
                        fx = x / freeX
                        fy = y / freeY
                        callbacks?.onMoved(fx, fy)
                    } else {
                        vertical = !vertical
                        callbacks?.onOrientationChanged(vertical)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    grip.alpha = 0.6f
                    grip.removeCallbacks(longPress)
                }
            }
            true
        }
    }
}
