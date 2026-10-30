package com.octavio.tinta.ui

import android.content.Context
import android.graphics.RectF
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.octavio.tinta.R
import com.octavio.tinta.dp
import com.octavio.tinta.tip

/**
 * Escritura adentro del recuadro que se dibujó en la hoja: el campo ocupa ese recuadro y arriba
 * se elige la letra y el tamaño. No es un cuadro de diálogo.
 */
class TextBoxEditor(context: Context) : FrameLayout(context) {
    private val field = EditText(context)
    private val bar = LinearLayout(context)
    private val fonts = LinearLayout(context)
    private val sizeLabel = TextView(context)
    private val blocked = ArrayList<RectF>()
    private val target = RectF()
    private var worldSize = 22f
    private var pxPerWorld = 1f
    private var fontId = Fonts.Family.SANS.id
    private var imeBottom = 0
    private var onDone: ((String, Float, String) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null

    init {
        visibility = GONE
        isClickable = true
        setOnClickListener { finish(commit = field.text?.isNotBlank() == true) }
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        field.gravity = Gravity.TOP or Gravity.START
        field.setTextColor(context.getColor(R.color.ink))
        field.setHintTextColor(context.getColor(R.color.muted))
        field.hint = "Escribí acá"
        field.background = context.getDrawable(R.drawable.bg_toolbar)
        val pad = context.dp(8f).toInt()
        field.setPadding(pad, pad, pad, pad)
        field.setOnClickListener { } // el toque en el campo no cierra
        addView(field, LayoutParams(0, 0))

        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = context.getDrawable(R.drawable.bg_toolbar)
        bar.elevation = context.dp(8f)
        val bpad = context.dp(4f).toInt()
        bar.setPadding(bpad, bpad, bpad, bpad)
        bar.setOnClickListener { }
        fonts.orientation = LinearLayout.HORIZONTAL
        for (family in Fonts.Family.entries) {
            fonts.addView(TextView(context).apply {
                text = family.label
                textSize = 13f
                typeface = Fonts.typeface(family.id)
                gravity = Gravity.CENTER
                setTextColor(context.getColor(R.color.ink))
                background = context.getDrawable(R.drawable.bg_tool)
                setPadding(context.dp(8f).toInt(), context.dp(4f).toInt(), context.dp(8f).toInt(), context.dp(4f).toInt())
                contentDescription = family.label
                tip(family.label)
                setOnClickListener {
                    fontId = family.id
                    field.typeface = Fonts.typeface(fontId)
                    markFont()
                }
            }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(32f).toInt()).apply {
                marginEnd = context.dp(4f).toInt()
            })
        }
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(fonts)
        }
        bar.addView(scroll, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        sizeLabel.textSize = 13f
        sizeLabel.setTextColor(context.getColor(R.color.ink))
        sizeLabel.gravity = Gravity.CENTER
        bar.addView(sizeLabel, LinearLayout.LayoutParams(context.dp(36f).toInt(), LayoutParams.WRAP_CONTENT))
        bar.addView(step("Más chica", "−") { changeSize(-2f) })
        bar.addView(step("Más grande", "+") { changeSize(2f) })
        bar.addView(step("Listo", "Listo") { finish(commit = true) })
        addView(bar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (visibility == VISIBLE) place()
            insets
        }
    }

    fun setBlocked(rects: List<RectF>) {
        blocked.clear()
        for (r in rects) blocked.add(RectF(r))
        if (visibility == VISIBLE) place()
    }

    fun open(
        screen: RectF,
        initial: String,
        size: Float,
        font: String,
        color: Int,
        pxPerWorld: Float,
        obstacles: List<RectF>,
        onDone: (String, Float, String) -> Unit,
        onCancel: () -> Unit,
    ) {
        target.set(screen)
        worldSize = size.coerceIn(MIN_SIZE, MAX_SIZE)
        this.pxPerWorld = pxPerWorld.coerceAtLeast(0.2f)
        fontId = Fonts.fromId(font).id
        this.onDone = onDone
        this.onCancel = onCancel
        setBlocked(obstacles)
        field.setText(initial)
        field.setSelection(initial.length)
        field.setTextColor(color)
        field.typeface = Fonts.typeface(fontId)
        applySize()
        markFont()
        visibility = VISIBLE
        place()
        field.requestFocus()
        post {
            ViewCompat.requestApplyInsets(this)
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun step(description: String, label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.ink))
        background = context.getDrawable(R.drawable.bg_tool)
        contentDescription = description
        tip(description)
        setOnClickListener { onClick() }
    }.also {
        // El layout se pone al agregar.
    }.let { view ->
        // addView espera el view; se arma el LayoutParams en el caller... se devuelve el view
        // y addView usa el tamaño de abajo.
        view.layoutParams = LinearLayout.LayoutParams(context.dp(if (label.length > 2) 64f else 36f).toInt(), context.dp(32f).toInt()).apply {
            marginStart = context.dp(4f).toInt()
        }
        view
    }

    private fun changeSize(delta: Float) {
        worldSize = (worldSize + delta).coerceIn(MIN_SIZE, MAX_SIZE)
        applySize()
    }

    private fun applySize() {
        sizeLabel.text = worldSize.toInt().toString()
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, worldSize * pxPerWorld)
    }

    private fun markFont() {
        for (i in 0 until fonts.childCount) {
            val chip = fonts.getChildAt(i)
            val family = Fonts.Family.entries.getOrNull(i) ?: continue
            chip.isSelected = family.id == fontId
        }
    }

    private fun place() {
        ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom?.let { imeBottom = it }
        val w = target.width().toInt().coerceAtLeast(context.dp(48f).toInt())
        val h = target.height().toInt().coerceAtLeast(context.dp(36f).toInt())
        val lp = field.layoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            field.layoutParams = lp
        }
        field.x = target.left
        field.y = target.top
        val barW = bar.width.takeIf { it > 0 } ?: context.dp(360f).toInt()
        val barH = bar.height.takeIf { it > 0 } ?: context.dp(40f).toInt()
        var bx = target.left
        var by = target.top - barH - context.dp(8f)
        if (by < context.dp(8f)) by = target.bottom + context.dp(8f)
        val pushed = Clearance.push(bx, by, barW.toFloat(), barH.toFloat(), width.toFloat(), height.toFloat(), blocked)
        bar.x = pushed.first
        bar.y = pushed.second
        val cover = imeBottom.toFloat()
        if (cover > 0f && field.y + h > height - cover) {
            val shift = field.y + h - (height - cover) + context.dp(8f)
            field.y -= shift
            bar.y -= shift
        }
        if (bar.width == 0 || width == 0) post { if (visibility == VISIBLE) place() }
    }

    private fun finish(commit: Boolean) {
        val done = onDone
        val cancel = onCancel
        val value = field.text?.toString().orEmpty()
        val size = worldSize
        val font = fontId
        close()
        if (commit && value.isNotBlank()) done?.invoke(value, size, font) else cancel?.invoke()
    }

    private fun close() {
        onDone = null
        onCancel = null
        visibility = GONE
        field.clearFocus()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    private companion object {
        const val MIN_SIZE = 8f
        const val MAX_SIZE = 96f
    }
}
