package com.octavio.tinta.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.octavio.tinta.R
import com.octavio.tinta.dp
import com.octavio.tinta.tip

/**
 * Buscar en lo escrito: un recuadro arriba a la derecha con lo buscado, cómo va la lectura de la
 * letra y la lista de renglones encontrados. Las flechas (o tocar uno de la lista) llevan a cada uno.
 */
@SuppressLint("ViewConstructor")
class SearchPanel(context: Context) : LinearLayout(context) {
    interface Callbacks {
        /** Cambió lo buscado (ya con una pausa al escribir). */
        fun onSearch(query: String)
        fun onPick(k: Int)
        fun onSearchClose()
    }

    class Result(val where: String, val text: String)

    var callbacks: Callbacks? = null

    private val input = EditText(context)
    private val status = TextView(context)
    private val list = LinearLayout(context)
    private val scroll = ScrollView(context)
    private val prev = ImageButton(context)
    private val next = ImageButton(context)
    private var count = 0
    private var current = -1
    private val debounce = Runnable { callbacks?.onSearch(query) }

    val query: String get() = input.text.toString().trim()

    init {
        orientation = VERTICAL
        background = context.getDrawable(R.drawable.bg_popup)
        elevation = context.dp(10f)
        val pad = context.dp(10f).toInt()
        setPadding(pad, context.dp(6f).toInt(), pad, pad)
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_search)
            alpha = 0.6f
        }, LayoutParams(context.dp(24f).toInt(), context.dp(24f).toInt()))
        input.apply {
            hint = "Buscar en lo escrito"
            textSize = 16f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setTextColor(context.getColor(R.color.ink))
            setHintTextColor(context.getColor(R.color.muted))
            background = null
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    removeCallbacks(debounce)
                    postDelayed(debounce, DEBOUNCE_MS)
                }
            })
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    removeCallbacks(debounce)
                    callbacks?.onSearch(query)
                    hideKeyboard()
                    true
                } else {
                    false
                }
            }
        }
        row.addView(input, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(6f).toInt() })
        button(prev, R.drawable.ic_chevron, "Anterior", -90f) { step(-1) }
        button(next, R.drawable.ic_chevron, "Siguiente", 90f) { step(1) }
        row.addView(prev, LayoutParams(context.dp(40f).toInt(), context.dp(40f).toInt()))
        row.addView(next, LayoutParams(context.dp(40f).toInt(), context.dp(40f).toInt()))
        row.addView(ImageButton(context).also { button(it, R.drawable.ic_close, "Cerrar la búsqueda", 0f) { callbacks?.onSearchClose() } },
            LayoutParams(context.dp(40f).toInt(), context.dp(40f).toInt()))
        addView(row)
        status.apply {
            textSize = 12f
            setTextColor(context.getColor(R.color.muted))
            setPadding(context.dp(4f).toInt(), 0, context.dp(4f).toInt(), context.dp(4f).toInt())
        }
        addView(status)
        list.orientation = VERTICAL
        scroll.addView(list)
        scroll.isVerticalScrollBarEnabled = true
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        visibility = GONE
        updateArrows()
    }

    private fun button(b: ImageButton, icon: Int, description: String, rotation: Float, onClick: () -> Unit) {
        b.setImageResource(icon)
        b.rotation = rotation
        b.background = context.getDrawable(R.drawable.bg_tool)
        b.scaleType = ImageView.ScaleType.CENTER
        b.contentDescription = description
        b.tip(description)
        b.setOnClickListener { onClick() }
    }

    /** Lo muestra (con `query`, si se busca algo ya) y abre el teclado si no hay nada escrito. */
    fun open(query: String? = null) {
        visibility = VISIBLE
        if (query != null) {
            input.setText(query)
            input.setSelection(input.text.length)
        }
        if (this.query.isEmpty()) {
            input.requestFocus()
            post { showKeyboard() }
        } else {
            // Ya viene con algo para buscar: se ven los resultados, sin el teclado encima.
            callbacks?.onSearch(this.query)
        }
    }

    fun close() {
        removeCallbacks(debounce)
        hideKeyboard()
        input.clearFocus()
        visibility = GONE
    }

    val isOpen get() = visibility == VISIBLE

    /** Una línea de estado (lectura, cantidad), o nada. */
    fun setStatus(text: String?) {
        status.text = text
        status.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
    }

    /** Color elegido para el panel: los renglones nuevos, con textos que se lean sobre él. */
    var barColor: Int? = null

    fun setResults(results: List<Result>) {
        list.removeAllViews()
        count = results.size
        current = -1
        for ((k, r) in results.withIndex()) {
            list.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = context.getDrawable(R.drawable.bg_tool)
                val p = context.dp(6f).toInt()
                setPadding(p, p, p, p)
                addView(TextView(context).apply {
                    text = r.where
                    textSize = 12f
                    setTextColor(context.getColor(R.color.muted))
                    visibility = if (r.where.isEmpty()) GONE else VISIBLE
                }, LayoutParams(context.dp(58f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(TextView(context).apply {
                    text = r.text
                    textSize = 14f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(context.getColor(R.color.ink))
                }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener { pick(k) }
            })
        }
        barColor?.let { Chrome.contrast(list, it) }
        updateArrows()
    }

    /** Marca el elegido en la lista (lo hace visible si quedó fuera). */
    fun setCurrent(k: Int) {
        list.getChildAt(current)?.isSelected = false
        current = k
        val v = list.getChildAt(k) ?: return
        v.isSelected = true
        v.post { if (v.top < scroll.scrollY || v.bottom > scroll.scrollY + scroll.height) scroll.smoothScrollTo(0, v.top) }
        updateArrows()
    }

    private fun pick(k: Int) {
        hideKeyboard()
        callbacks?.onPick(k)
    }

    private fun step(d: Int) {
        if (count == 0) return
        pick(((if (current < 0) (if (d > 0) -1 else 0) else current) + d + count) % count)
    }

    private fun updateArrows() {
        prev.isEnabled = count > 0
        next.isEnabled = count > 0
        prev.alpha = if (count > 0) 1f else 0.35f
        next.alpha = prev.alpha
    }

    /** Alto máximo de la lista, según el lugar que hay. */
    fun setMaxListHeight(px: Int) {
        val lp = scroll.layoutParams
        scroll.measure(MeasureSpec.makeMeasureSpec(context.dp(WIDTH_DP).toInt(), MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
        lp.height = if (scroll.measuredHeight > px) px else ViewGroup.LayoutParams.WRAP_CONTENT
        scroll.layoutParams = lp
    }

    private fun showKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) removeCallbacks(debounce)
    }

    companion object {
        const val WIDTH_DP = 380f
        private const val DEBOUNCE_MS = 350L
    }
}
