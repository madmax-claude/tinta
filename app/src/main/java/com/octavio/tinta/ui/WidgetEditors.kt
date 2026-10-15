package com.octavio.tinta.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.octavio.tinta.R
import com.octavio.tinta.canvas.WidgetPainter
import com.octavio.tinta.dp
import com.octavio.tinta.model.Expr
import com.octavio.tinta.model.Graph2D
import com.octavio.tinta.model.Graph3D
import com.octavio.tinta.model.Solid
import com.octavio.tinta.model.SolidKind
import com.octavio.tinta.model.Widget

/**
 * Ventanas para crear o cambiar gráficos y figuras 3D, con una vista previa que se actualiza al
 * escribir. Las de 3D se giran arrastrando la vista previa. El rectángulo del resultado lo pone
 * quien llama (la posición en la hoja no se toca acá).
 */
object WidgetEditors {

    fun graph2d(activity: Activity, start: Graph2D, dark: Boolean, isNew: Boolean, onDone: (Graph2D) -> Unit) {
        val c: Context = activity
        var g = start
        val box = column(c)
        val preview = WidgetPreview(c, dark)
        box.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(240f).toInt()))
        box.addView(label(c, "Funciones (una por renglón): x^2 - 1, 2*sen(x), sqrt(x), e^(-x), abs(x), ln(x)…"), top(c, 10f))
        val functions = EditText(c).apply {
            setText(g.functions.joinToString("\n"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
        }
        box.addView(functions)
        box.addView(keys(c, functions, twoVariables = false))
        val error = label(c, "").apply { setTextColor(0xFFD93025.toInt()) }
        box.addView(error)
        val xMin = number(c, g.xMin)
        val xMax = number(c, g.xMax)
        box.addView(row(c, "x desde", xMin, "hasta", xMax), top(c, 6f))
        val auto = CheckBox(c).apply {
            text = "y automático"
            isChecked = g.autoY
            setTextColor(c.getColor(R.color.ink))
        }
        box.addView(auto)
        val yMin = number(c, g.yMin)
        val yMax = number(c, g.yMax)
        val yRow = row(c, "y desde", yMin, "hasta", yMax)
        box.addView(yRow)
        val grid = CheckBox(c).apply {
            text = "Cuadrícula"
            isChecked = g.grid
            setTextColor(c.getColor(R.color.ink))
        }
        box.addView(grid)

        fun update() {
            val lines = functions.text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val problems = lines.mapNotNull { f ->
                runCatching { Expr.parse(f) }.fold(
                    { e -> if ('y' in e.variables) "«$f»: en un gráfico 2D la única variable es x" else null },
                    { "«$f»: ${it.message}" },
                )
            }
            error.text = problems.joinToString("\n")
            error.visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
            yRow.alpha = if (auto.isChecked) 0.4f else 1f
            g = g.copy(
                functions = lines.ifEmpty { listOf("x") },
                xMin = value(xMin, g.xMin), xMax = value(xMax, g.xMax),
                yMin = value(yMin, g.yMin), yMax = value(yMax, g.yMax),
                autoY = auto.isChecked, grid = grid.isChecked,
            )
            preview.widget = g
        }
        listOf(functions, xMin, xMax, yMin, yMax).forEach { it.onChange(::update) }
        auto.setOnCheckedChangeListener { _, _ -> update() }
        grid.setOnCheckedChangeListener { _, _ -> update() }
        update()
        show(activity, if (isNew) "Gráfico" else "Cambiar el gráfico", box, isNew) {
            update()
            if (g.xMax <= g.xMin) g = g.copy(xMax = g.xMin + 1)
            onDone(g)
        }
    }

    fun graph3d(activity: Activity, start: Graph3D, dark: Boolean, isNew: Boolean, onDone: (Graph3D) -> Unit) {
        val c: Context = activity
        var g = start
        val box = column(c)
        val preview = WidgetPreview(c, dark)
        box.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(280f).toInt()))
        box.addView(label(c, "Arrastrá el dibujo para girarlo.").apply { gravity = Gravity.CENTER })
        box.addView(label(c, "z = f(x, y): x^2 + y^2, sen(x)*cos(y), e^(-(x^2+y^2)), sqrt(x^2+y^2)…"), top(c, 10f))
        val function = EditText(c).apply {
            setText(g.function)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        box.addView(function)
        box.addView(keys(c, function, twoVariables = true))
        val error = label(c, "").apply { setTextColor(0xFFD93025.toInt()) }
        box.addView(error)
        val xMin = number(c, g.xMin)
        val xMax = number(c, g.xMax)
        val yMin = number(c, g.yMin)
        val yMax = number(c, g.yMax)
        box.addView(row(c, "x desde", xMin, "hasta", xMax), top(c, 6f))
        box.addView(row(c, "y desde", yMin, "hasta", yMax))

        fun update() {
            val f = function.text.toString().trim().ifEmpty { "0" }
            error.text = runCatching { Expr.parse(f); "" }.getOrElse { it.message.orEmpty() }
            error.visibility = if (error.text.isEmpty()) View.GONE else View.VISIBLE
            g = g.copy(function = f, xMin = value(xMin, g.xMin), xMax = value(xMax, g.xMax), yMin = value(yMin, g.yMin), yMax = value(yMax, g.yMax))
            preview.widget = g
        }
        listOf(function, xMin, xMax, yMin, yMax).forEach { it.onChange(::update) }
        preview.onRotate = { yaw, pitch ->
            g = g.copy(yaw = yaw, pitch = pitch)
            preview.widget = g
        }
        update()
        show(activity, if (isNew) "Gráfico 3D" else "Cambiar el gráfico 3D", box, isNew) {
            update()
            if (g.xMax <= g.xMin) g = g.copy(xMax = g.xMin + 1)
            if (g.yMax <= g.yMin) g = g.copy(yMax = g.yMin + 1)
            onDone(g)
        }
    }

    fun solid(activity: Activity, start: Solid, dark: Boolean, isNew: Boolean, onDone: (Solid) -> Unit) {
        val c: Context = activity
        var s = start
        val box = column(c)
        val preview = WidgetPreview(c, dark)
        box.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(260f).toInt()))
        box.addView(label(c, "Arrastrá la figura para girarla.").apply { gravity = Gravity.CENTER })
        val kinds = GridLayout(c).apply { columnCount = 3 }
        box.addView(kinds, top(c, 8f))
        val sizes = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        box.addView(sizes, top(c, 6f))
        val hidden = CheckBox(c).apply {
            text = "Aristas de atrás punteadas"
            isChecked = s.hiddenEdges
            setTextColor(c.getColor(R.color.ink))
        }
        val shaded = CheckBox(c).apply {
            text = "Caras sombreadas"
            isChecked = s.shaded
            setTextColor(c.getColor(R.color.ink))
        }
        box.addView(hidden)
        box.addView(shaded)

        lateinit var refresh: () -> Unit
        fun sliders() {
            sizes.removeAllViews()
            val values = floatArrayOf(s.a, s.b, s.c)
            s.kind.labels.forEachIndexed { k, name ->
                val text = label(c, "")
                fun show(v: Float) {
                    text.text = "$name: ${"%.1f".format(v)}"
                }
                show(values[k])
                sizes.addView(text)
                sizes.addView(SeekBar(c).apply {
                    max = 100
                    progress = ((values[k] - 0.2f) / 2.8f * 100).toInt().coerceIn(0, 100)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                            if (!fromUser) return
                            val v = 0.2f + 2.8f * p / 100f
                            show(v)
                            s = when (k) {
                                0 -> s.copy(a = v)
                                1 -> s.copy(b = v)
                                else -> s.copy(c = v)
                            }
                            preview.widget = s
                        }

                        override fun onStartTrackingTouch(bar: SeekBar) = Unit
                        override fun onStopTrackingTouch(bar: SeekBar) = Unit
                    })
                })
            }
        }
        val chips = SolidKind.entries.associateWith { kind ->
            TextView(c).apply {
                text = kind.label
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(c.getColor(R.color.ink))
                background = c.getDrawable(R.drawable.bg_tool)
                val p = c.dp(6f).toInt()
                setPadding(p, p, p, p)
                setOnClickListener {
                    s = s.copy(kind = kind)
                    refresh()
                }
            }.also {
                kinds.addView(it, GridLayout.LayoutParams().apply {
                    width = c.dp(150f).toInt()
                    setMargins(0, 0, c.dp(4f).toInt(), c.dp(4f).toInt())
                })
            }
        }
        refresh = {
            for ((k, v) in chips) v.isSelected = k == s.kind
            sliders()
            preview.widget = s
        }
        hidden.setOnCheckedChangeListener { _, v ->
            s = s.copy(hiddenEdges = v)
            preview.widget = s
        }
        shaded.setOnCheckedChangeListener { _, v ->
            s = s.copy(shaded = v)
            preview.widget = s
        }
        preview.onRotate = { yaw, pitch ->
            s = s.copy(yaw = yaw, pitch = pitch)
            preview.widget = s
        }
        refresh()
        show(activity, if (isNew) "Figura 3D" else "Cambiar la figura", box, isNew) { onDone(s) }
    }

    // --- Armado ---

    private fun show(activity: Activity, title: String, content: View, isNew: Boolean, onOk: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton(if (isNew) "Insertar" else "Listo") { _, _ -> onOk() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Teclas para lo que cuesta encontrar en el teclado: se escriben donde está el cursor. Todo
     * con caracteres comunes (`*` para multiplicar, `sqrt(` para la raíz).
     */
    private fun keys(c: Context, target: EditText, twoVariables: Boolean): View {
        val texts = listOf("x") + (if (twoVariables) listOf("y") else emptyList()) +
            listOf("^", "*", "/", "(", ")", "sqrt(", "pi", "e", "sen(", "cos(", "tg(", "ln(", "abs(")
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        for (t in texts) {
            row.addView(
                TextView(c).apply {
                    text = t
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(c.getColor(R.color.ink))
                    background = c.getDrawable(R.drawable.bg_tool)
                    val p = c.dp(8f).toInt()
                    setPadding(p, 0, p, 0)
                    setOnClickListener {
                        val start = target.selectionStart.coerceAtLeast(0)
                        val end = target.selectionEnd.coerceAtLeast(start)
                        target.text.replace(start, end, t)
                        target.requestFocus()
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, c.dp(40f).toInt()).apply {
                    marginEnd = c.dp(4f).toInt()
                },
            )
        }
        return android.widget.HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun column(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        val p = c.dp(20f).toInt()
        setPadding(p, p / 2, p, 0)
    }

    private fun label(c: Context, text: String) = TextView(c).apply {
        this.text = text
        textSize = 13f
        setTextColor(c.getColor(R.color.muted))
    }

    private fun top(c: Context, dp: Float) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = c.dp(dp).toInt()
        }

    private fun number(c: Context, v: Float) = EditText(c).apply {
        setText(format(v))
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
        minEms = 4
        gravity = Gravity.CENTER
    }

    private fun format(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString().replace('.', ',')

    private fun value(e: EditText, fallback: Float) = e.text.toString().trim().replace(',', '.').toFloatOrNull() ?: fallback

    private fun row(c: Context, a: String, first: EditText, b: String, second: EditText) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(c).apply { text = a; setTextColor(c.getColor(R.color.ink)) })
        addView(first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(c).apply { text = b; setTextColor(c.getColor(R.color.ink)) })
        addView(second, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun EditText.onChange(action: () -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = action()
    })
}

/** Dibuja un widget ocupando toda la vista; si es 3D, arrastrarlo lo gira. */
@SuppressLint("ViewConstructor")
class WidgetPreview(context: Context, private val dark: Boolean) : View(context) {
    var widget: Widget? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Si no es null, arrastrar cambia el giro (yaw, pitch) en grados. */
    var onRotate: ((Float, Float) -> Unit)? = null

    private val painter = WidgetPainter()
    private var lastX = 0f
    private var lastY = 0f

    init {
        background = context.getDrawable(R.drawable.bg_card)
    }

    override fun onDraw(canvas: Canvas) {
        val w = widget ?: return
        val d = resources.displayMetrics.density
        val save = canvas.save()
        canvas.scale(d, d)
        painter.draw(canvas, w.placed(0f, 0f, width / d, height / d), dark)
        canvas.restoreToCount(save)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val rotate = onRotate ?: return false
        val (yaw, pitch) = when (val w = widget) {
            is Graph3D -> w.yaw to w.pitch
            is Solid -> w.yaw to w.pitch
            else -> return false
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                val dy = e.y - lastY
                rotate(((yaw + dx * 0.5f) % 360f + 360f) % 360f, (pitch + dy * 0.5f).coerceIn(-89f, 89f))
            }
        }
        lastX = e.x
        lastY = e.y
        return true
    }
}
