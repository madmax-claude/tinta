package com.octavio.tinta.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
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
import com.octavio.tinta.tip
import com.octavio.tinta.model.Pen
import kotlin.math.abs
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

        /** Mantener apretado un lápiz y soltarlo sin moverlo: sus opciones rápidas (quitarlo). */
        fun onPenLongClick(index: Int, button: View)

        /** Se arrastró el lápiz `from` al lugar `to` (mantenerlo apretado y moverlo). */
        fun onPenMoved(from: Int, to: Int)

        /** Mantener apretada la manija: opciones de la barra (borrar todos los lápices). */
        fun onGripLongPress(grip: View)
        fun onAddPen(button: View)
        fun onEraserClick(button: View)
        fun onLassoClick(button: View)

        /** La captura de pantalla (al lado del lazo). */
        fun onCaptureClick(button: View)
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
    private val capture = button(R.drawable.ic_capture, "Captura: marcá con el lápiz qué capturar")
    val undo = button(R.drawable.ic_undo, context.getString(R.string.undo))
    val redo = button(R.drawable.ic_redo, context.getString(R.string.redo))
    private val dividers = listOf(View(context), View(context))
    private val penButtons = ArrayList<PenButton>()

    /** Posición como fracción del espacio libre, para que sobreviva a girar la tablet. */
    private var fx = 0.5f
    private var fy = 0f

    /** Zonas de las otras barras, en coordenadas del mismo padre: no se puede solapar. */
    private val blocked = ArrayList<RectF>()

    private var indicator = context.getColor(R.color.ink)

    var vertical = false
        set(value) {
            field = value
            applyOrientation()
        }

    init {
        background = context.getDrawable(R.drawable.bg_toolbar)
        elevation = context.dp(6f)
        val pad = context.dp(3f).toInt()
        setPadding(pad, pad, pad, pad)
        gravity = android.view.Gravity.CENTER

        // El lápiz que se levanta para moverlo es un poco más grande: que no lo corte el borde.
        clipChildren = false
        pensBox.clipChildren = false
        grip.setImageResource(R.drawable.ic_drag)
        grip.contentDescription = "Mover la barra (tocar para girarla; mantener para más opciones)"
        grip.alpha = 0.6f
        grip.scaleType = ImageView.ScaleType.CENTER
        setupDrag()

        add.setOnClickListener { callbacks?.onAddPen(it) }
        eraser.setOnClickListener { callbacks?.onEraserClick(it) }
        lasso.setOnClickListener { callbacks?.onLassoClick(it) }
        capture.setOnClickListener { callbacks?.onCaptureClick(it) }
        undo.setOnClickListener { callbacks?.onUndo() }
        redo.setOnClickListener { callbacks?.onRedo() }
        for (d in dividers) d.setBackgroundColor(context.getColor(R.color.divider))

        addView(grip)
        addView(pensBox)
        addView(add)
        addView(dividers[0])
        addView(eraser)
        addView(lasso)
        addView(capture)
        addView(dividers[1])
        addView(undo)
        addView(redo)
        applyOrientation()
    }

    private fun button(icon: Int, description: String) = ImageButton(context).apply {
        setImageResource(icon)
        background = context.getDrawable(R.drawable.bg_tool)
        val p = context.dp(7f).toInt()
        setPadding(p, p, p, p)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = description
        tip(description)
    }

    fun setPens(pens: List<Pen>, selected: Int, mode: NoteCanvasView.Mode) {
        while (penButtons.size > pens.size) pensBox.removeView(penButtons.removeAt(penButtons.lastIndex))
        while (penButtons.size < pens.size) {
            val b = PenButton(context, dark)
            b.indicator = indicator
            val index = penButtons.size
            b.setOnClickListener { callbacks?.onPenClick(index, it) }
            penOrder.attach(b)
            penButtons.add(b)
            pensBox.addView(b, penParams())
        }
        pens.forEachIndexed { i, p ->
            penButtons[i].pen = p
            penButtons[i].isSelected = mode == NoteCanvasView.Mode.PEN && i == selected
        }
        eraser.isSelected = mode == NoteCanvasView.Mode.ERASER
        lasso.isSelected = mode == NoteCanvasView.Mode.LASSO
        capture.isSelected = mode == NoteCanvasView.Mode.CAPTURE
        post { clampToParent() }
    }

    fun penButton(index: Int): View? = penButtons.getOrNull(index)

    /** Mantener apretado un lápiz y arrastrarlo lo cambia de lugar; soltarlo sin moverlo, sus opciones. */
    private val penOrder = Reorder(
        items = { penButtons },
        vertical = { vertical },
        slot = { v -> (if (vertical) v.height else v.width).toFloat() },
        onMoved = { from, to -> callbacks?.onPenMoved(from, to) },
        onHeld = { i, v -> callbacks?.onPenLongClick(i, v) },
    )


    fun setUndoState(canUndo: Boolean, canRedo: Boolean) {
        undo.isEnabled = canUndo
        redo.isEnabled = canRedo
        undo.alpha = if (canUndo) 1f else 0.35f
        redo.alpha = if (canRedo) 1f else 0.35f
    }

    /** El color de la raya que marca la línea recta, el mismo de los íconos de esta barra. */
    fun setIndicatorColor(color: Int) {
        indicator = color
        for (b in penButtons) b.indicator = color
    }

    /**
     * Rectángulos (del padre) que esta barra no puede tapar: la de herramientas y la de hojas.
     * Si quedó encima, se corre al lugar libre más cercano.
     */
    fun setBlocked(rects: List<RectF>) {
        blocked.clear()
        for (r in rects) blocked.add(RectF(r))
        val parent = parent as? View ?: return
        val maxX = (parent.width - width).coerceAtLeast(0).toFloat()
        val maxY = (parent.height - height).coerceAtLeast(0).toFloat()
        resolve(fx * maxX, fy * maxY, notify = true)
    }

    private fun itemParams(): LayoutParams {
        val s = context.dp(36f).toInt()
        return LayoutParams(s, s)
    }

    /** La raya de línea recta entra en el mismo alto que el resto de los botones. */
    private fun penParams(): LayoutParams {
        val s = context.dp(36f).toInt()
        return LayoutParams(s, s)
    }

    private fun applyOrientation() {
        orientation = if (vertical) VERTICAL else HORIZONTAL
        pensBox.orientation = orientation
        for (v in listOf(grip, add, eraser, lasso, capture, undo, redo)) v.layoutParams = itemParams()
        for (b in penButtons) b.layoutParams = penParams()
        val long = context.dp(20f).toInt()
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
        positioned = true
        clampToParent()
    }

    /** True después de [placeAt]: antes, un choque no puede pisar la fracción guardada. */
    private var positioned = false

    private fun clampToParent() {
        val parent = parent as? ViewGroup ?: return
        if (!positioned || width <= 0 || parent.width <= 0) return
        val maxX = (parent.width - width).coerceAtLeast(0).toFloat()
        val maxY = (parent.height - height).coerceAtLeast(0).toFloat()
        resolve(fx * maxX, fy * maxY, notify = false)
    }

    /** Deja (desiredX, desiredY) dentro del padre y fuera de [blocked]. */
    private fun resolve(desiredX: Float, desiredY: Float, notify: Boolean) {
        val parent = parent as? View ?: return
        // Sin medir, la fracción se iría a cero y la barra saltaría a la esquina al cambiar de modo.
        if (!positioned || !isLaidOut || width <= 0 || parent.width <= 0) return
        val maxX = (parent.width - width).coerceAtLeast(0).toFloat()
        val maxY = (parent.height - height).coerceAtLeast(0).toFloat()
        var nx = desiredX.coerceIn(0f, maxX)
        var ny = desiredY.coerceIn(0f, maxY)
        var guard = 0
        while (guard < 8) {
            guard++
            val hit = blocked.firstOrNull { nx + width > it.left && nx < it.right && ny + height > it.top && ny < it.bottom } ?: break
            val pushes = listOf(
                -((nx + width) - hit.left) to 0f,
                (hit.right - nx) to 0f,
                0f to -((ny + height) - hit.top),
                0f to (hit.bottom - ny),
            )
            val (dx, dy) = pushes.minBy { (dx, dy) ->
                val x2 = (nx + dx).coerceIn(0f, maxX)
                val y2 = (ny + dy).coerceIn(0f, maxY)
                abs(nx + dx - x2) + abs(ny + dy - y2) + hypot(dx, dy)
            }
            nx = (nx + dx).coerceIn(0f, maxX)
            ny = (ny + dy).coerceIn(0f, maxY)
        }
        x = nx
        y = ny
        val nfx = if (maxX > 0f) nx / maxX else 0f
        val nfy = if (maxY > 0f) ny / maxY else 0f
        val moved = abs(nfx - fx) > 0.001f || abs(nfy - fy) > 0.001f
        fx = nfx
        fy = nfy
        if (notify && moved) callbacks?.onMoved(fx, fy)
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
                        val maxX = (parent.width - width).coerceAtLeast(0).toFloat()
                        val maxY = (parent.height - height).coerceAtLeast(0).toFloat()
                        resolve((startX + dx).coerceIn(0f, maxX), (startY + dy).coerceIn(0f, maxY), notify = false)
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
