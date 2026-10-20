package com.octavio.tinta.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import com.octavio.tinta.DotView
import com.octavio.tinta.R
import com.octavio.tinta.canvas.Paper
import com.octavio.tinta.dp
import com.octavio.tinta.tip
import com.octavio.tinta.model.Appearance
import com.octavio.tinta.model.Background
import com.octavio.tinta.model.Dash
import com.octavio.tinta.model.LassoFilter
import com.octavio.tinta.model.PageLayout
import com.octavio.tinta.model.PageSize
import com.octavio.tinta.model.Pen
import com.octavio.tinta.model.Sensitivity
import com.octavio.tinta.model.Tool
import kotlin.math.sqrt

/** Ventanitas de opciones que se abren junto a la barra flotante y a la barra de arriba. */
object Popups {
    /** Colores de hoja para elegir (además del de siempre): claros, para que la tinta se lea. */
    private val PAPER_COLORS = listOf(
        0xFFFFFFFF.toInt(), // blanco
        0xFFFFF6D5.toInt(), // amarillo
        0xFFF4EBDC.toInt(), // sepia
        0xFFE8F4E6.toInt(), // verde
        0xFFE5F0FA.toInt(), // celeste
        0xFFFBE8EE.toInt(), // rosa
    )


    /**
     * Opciones de un lápiz: herramienta, grosor, color (de la paleta, guardado o cualquiera con el
     * selector) y línea recta. Cada cambio se aplica enseguida con `onChange`.
     */
    fun pen(
        activity: Activity,
        anchor: View,
        start: Pen,
        dark: Boolean,
        savedColors: () -> List<Int>,
        onSaveColor: (Int) -> Unit,
        onForgetColor: (Int) -> Unit,
        canDelete: Boolean,
        holdForShapes: Boolean,
        onHoldForShapes: (Boolean) -> Unit,
        onChange: (Pen) -> Unit,
        onDelete: () -> Unit,
    ) {
        val c: Context = activity
        var pen = start
        val box = column(c)
        lateinit var popup: PopupWindow

        // Herramienta
        val tools = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val toolViews = Tool.entries.associateWith { tool ->
            LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = c.getDrawable(R.drawable.bg_tool)
                val p = c.dp(6f).toInt()
                setPadding(p, p, p, p)
                addView(ImageView(c).apply { setImageResource(PenButton.iconFor(tool)) }, LinearLayout.LayoutParams(c.dp(24f).toInt(), c.dp(24f).toInt()))
                addView(label(c, tool.label, 13f).apply { gravity = Gravity.CENTER })
            }.also { tools.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        if (canDelete) {
            // Arriba de todo, para no tener que bajar hasta el final de la ventana.
            tools.addView(
                ImageView(c).apply {
                    setImageResource(R.drawable.ic_delete)
                    setColorFilter(0xFFD93025.toInt())
                    background = c.getDrawable(R.drawable.bg_tool)
                    scaleType = ImageView.ScaleType.CENTER
                    contentDescription = "Quitar este lápiz"
                    tip(contentDescription)
                    setOnClickListener {
                        popup.dismiss()
                        onDelete()
                    }
                },
                LinearLayout.LayoutParams(c.dp(44f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        box.addView(tools)

        // Grosor
        val sizeLabel = label(c, "", 14f)
        box.addView(sizeLabel, topMargin(c, 14f))
        val preview = StrokePreview(c, dark)
        val seek = SeekBar(c).apply { max = 1000 }
        box.addView(seek)
        box.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(44f).toInt()))

        // Sensibilidad a la presión (sólo la pluma)
        val sensitivityBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val sensitivityLabel = label(c, "", 14f)
        val sensitivitySeek = SeekBar(c).apply { max = Sensitivity.MAX }
        val pressurePreview = PressurePreview(c, dark)
        sensitivityBox.addView(sensitivityLabel)
        sensitivityBox.addView(sensitivitySeek)
        sensitivityBox.addView(pressurePreview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(40f).toInt()))
        sensitivityBox.addView(hint(c, "Cuánto cambia el ancho de la pluma entre apoyar suave y apoyar fuerte. En 0 el trazo sale siempre igual."))
        box.addView(sensitivityBox, topMargin(c, 4f))

        // Colores
        box.addView(label(c, "Color", 14f), topMargin(c, 6f))
        val grid = GridLayout(c).apply { columnCount = 8 }
        box.addView(grid)
        val picker = ColorPickerView(c)
        box.addView(picker, topMargin(c, 8f))

        // Tipo de línea: continua, rayas, puntos, raya y punto.
        box.addView(label(c, "Tipo de línea", 15f), topMargin(c, 12f))
        val dashRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        box.addView(dashRow, topMargin(c, 6f))
        val dashCells = Dash.entries.associateWith { d ->
            LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = c.getDrawable(R.drawable.bg_tool)
                val p = c.dp(4f).toInt()
                setPadding(p, p, p, p)
                addView(DashSample(c, d, dark), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(22f).toInt()))
                addView(label(c, d.label, 12f).apply { gravity = Gravity.CENTER })
                contentDescription = d.label
            }.also { dashRow.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        box.addView(hint(c, "Mientras escribís se ve continua; al levantar el lápiz queda con su forma."))

        // Línea recta
        @SuppressLint("UseSwitchCompatOrMaterialCode")
        val straight = Switch(c).apply {
            text = "Línea recta"
            textSize = 15f
            setTextColor(c.getColor(R.color.ink))
        }
        box.addView(straight, topMargin(c, 10f))
        box.addView(hint(c, "Cada trazo va derecho de donde apoyás a donde levantás; casi horizontal se endereza."))

        box.addView(
            switch(c, "Mantener al final para hacer figuras", holdForShapes, onHoldForShapes),
            topMargin(c, 10f),
        )
        box.addView(hint(c, "Para todos los lápices: al terminar una recta, un círculo, un rectángulo o una raíz, dejá el lápiz quieto un momento y se acomoda; sin levantarlo, podés estirarla."))

        if (canDelete) {
            box.addView(
                Button(c, null, 0, android.R.style.Widget_DeviceDefault_Button_Borderless_Colored).apply {
                    text = "Quitar este lápiz"
                    isAllCaps = false
                    setTextColor(0xFFD93025.toInt())
                    setOnClickListener {
                        popup.dismiss()
                        onDelete()
                    }
                },
                topMargin(c, 6f),
            )
        }

        fun shown(color: Int) = if (pen.tool == Tool.HIGHLIGHTER) color else Appearance.ink(color, pen.tool, dark)

        fun sensitivityText() = "Sensibilidad a la presión: ${pen.sensitivity}" + when (pen.sensitivity) {
            0 -> " (nada)"
            Sensitivity.DEFAULT -> " (la de siempre)"
            Sensitivity.MAX -> " (máxima)"
            else -> ""
        }

        fun refresh() {
            for ((tool, v) in toolViews) v.isSelected = tool == pen.tool
            sizeLabel.text = "Grosor: ${"%.1f".format(pen.size)}"
            preview.pen = pen
            sensitivityBox.visibility = if (pen.tool == Tool.PEN) View.VISIBLE else View.GONE
            sensitivityLabel.text = sensitivityText()
            sensitivitySeek.progress = pen.sensitivity
            pressurePreview.pen = pen
            straight.isChecked = pen.straight
            for ((d, v) in dashCells) {
                v.isSelected = d == pen.dash
                ((v as ViewGroup).getChildAt(0) as DashSample).color = shown(pen.color)
            }
            grid.removeAllViews()
            val colors = (pen.tool.colors.toList() + savedColors()).distinct()
            for (color in colors) {
                grid.addView(
                    DotView(c, shown(color), c.dp(11f)).apply {
                        isSelected = color == pen.color
                        setOnClickListener {
                            pen = pen.copy(color = color)
                            onChange(pen)
                            picker.setColor(shown(color))
                            refresh()
                        }
                        if (color !in pen.tool.colors) {
                            setOnLongClickListener {
                                onForgetColor(color)
                                refresh()
                                true
                            }
                        }
                    },
                    GridLayout.LayoutParams().apply {
                        width = c.dp(38f).toInt()
                        height = c.dp(38f).toInt()
                    },
                )
            }
            grid.addView(
                ImageView(c).apply {
                    setImageResource(R.drawable.ic_add)
                    background = c.getDrawable(R.drawable.bg_tool)
                    val p = c.dp(8f).toInt()
                    setPadding(p, p, p, p)
                    contentDescription = "Guardar este color en la paleta"
                    tip(contentDescription)
                    setOnClickListener {
                        onSaveColor(pen.color)
                        refresh()
                    }
                },
                GridLayout.LayoutParams().apply {
                    width = c.dp(38f).toInt()
                    height = c.dp(38f).toInt()
                },
            )
        }

        for ((tool, v) in toolViews) {
            v.setOnClickListener {
                if (tool == pen.tool) return@setOnClickListener
                val color = if (pen.tool == Tool.HIGHLIGHTER || tool == Tool.HIGHLIGHTER) tool.colors[0] else pen.color
                pen = pen.copy(tool = tool, color = color, size = defaultSize(tool), straight = pen.straight)
                onChange(pen)
                seek.progress = toProgress(pen)
                picker.setColor(shown(pen.color))
                refresh()
            }
        }
        sensitivitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                pen = pen.copy(sensitivity = progress)
                sensitivityLabel.text = sensitivityText()
                pressurePreview.pen = pen
            }

            override fun onStartTrackingTouch(s: SeekBar) = Unit
            override fun onStopTrackingTouch(s: SeekBar) = onChange(pen)
        })
        seek.progress = toProgress(pen)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val f = progress / 1000f
                pen = pen.copy(size = pen.tool.minSize + (pen.tool.maxSize - pen.tool.minSize) * f * f)
                sizeLabel.text = "Grosor: ${"%.1f".format(pen.size)}"
                preview.pen = pen
            }

            override fun onStartTrackingTouch(s: SeekBar) = Unit
            override fun onStopTrackingTouch(s: SeekBar) = onChange(pen)
        })
        picker.setColor(shown(pen.color))
        picker.onChange = { shownColor, done ->
            pen = pen.copy(color = Appearance.stored(shownColor, pen.tool, dark))
            preview.pen = pen
            if (done) {
                onChange(pen)
                refresh()
            }
        }
        for ((d, v) in dashCells) {
            v.setOnClickListener {
                if (pen.dash == d) return@setOnClickListener
                pen = pen.copy(dash = d)
                onChange(pen)
                refresh()
            }
        }
        straight.setOnCheckedChangeListener { _, checked ->
            if (checked != pen.straight) {
                pen = pen.copy(straight = checked)
                onChange(pen)
                preview.pen = pen
            }
        }
        refresh()
        popup = show(activity, anchor, box, 360f)
    }

    /** Opciones del borrador: tamaño, borrar con el lápiz quieto y borrar toda la nota. */
    fun eraser(
        activity: Activity,
        anchor: View,
        radius: Float,
        holdToErase: Boolean,
        partial: Boolean,
        onRadius: (Float) -> Unit,
        onHoldToErase: (Boolean) -> Unit,
        onPartial: (Boolean) -> Unit,
        onClearAll: () -> Unit,
    ) {
        val c: Context = activity
        val box = column(c)
        lateinit var popup: PopupWindow
        box.addView(label(c, "Qué borra", 14f))
        val kinds = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val whole = chip(c, "El trazo entero")
        val part = chip(c, "Sólo lo que toca")
        kinds.addView(whole, LinearLayout.LayoutParams(0, c.dp(40f).toInt(), 1f))
        kinds.addView(part, LinearLayout.LayoutParams(0, c.dp(40f).toInt(), 1f))
        box.addView(kinds, topMargin(c, 4f))
        val kindHint = hint(c, "")
        box.addView(kindHint)
        fun showKind(p: Boolean) {
            whole.isSelected = !p
            part.isSelected = p
            kindHint.text = if (p) "Como una goma: corta el trazo donde pasa y deja el resto." else "Toca un trazo y se va completo."
        }
        showKind(partial)
        whole.setOnClickListener {
            showKind(false)
            onPartial(false)
        }
        part.setOnClickListener {
            showKind(true)
            onPartial(true)
        }
        val sizeLabel = label(c, "", 14f)
        box.addView(sizeLabel, topMargin(c, 12f))
        val dot = DotView(c, c.getColor(R.color.muted), c.dp(radius))
        val seek = SeekBar(c).apply {
            max = 56
            progress = (radius - 4f).toInt().coerceIn(0, 56)
        }
        fun show(r: Float) {
            sizeLabel.text = "Tamaño del borrador: ${r.toInt()}"
            dot.radius = c.dp(r)
            dot.invalidate()
        }
        show(radius)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                show(4f + progress)
                if (fromUser) onRadius(4f + progress)
            }

            override fun onStartTrackingTouch(s: SeekBar) = Unit
            override fun onStopTrackingTouch(s: SeekBar) = Unit
        })
        box.addView(seek)
        box.addView(dot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(130f).toInt()))
        box.addView(switch(c, "Mantener apretado para borrar", holdToErase, onHoldToErase))
        box.addView(hint(c, "Con cualquier lápiz: apoyalo y dejalo quieto medio segundo; mientras no lo levantes, borra."))
        box.addView(
            Button(c, null, 0, android.R.style.Widget_DeviceDefault_Button_Borderless_Colored).apply {
                text = "Borrar toda la nota…"
                isAllCaps = false
                setTextColor(0xFFD93025.toInt())
                setOnClickListener {
                    popup.dismiss()
                    onClearAll()
                }
            },
        )
        popup = show(activity, anchor, box, 300f)
    }

    /**
     * Tamaño de hoja (o nota infinita), orientación y fondo, con una muestra de cada uno. El tamaño
     * y la orientación se aplican sin cerrar; el fondo cierra.
     */
    fun sheet(
        activity: Activity,
        anchor: View,
        current: PageLayout,
        currentBackground: Background,
        dark: Boolean,
        paperColor: Int?,
        onFormat: (PageSize, Boolean) -> Unit,
        onPaperColor: (Int?) -> Unit,
        onBackground: (Background) -> Unit,
    ) {
        val c: Context = activity
        val box = column(c)
        lateinit var popup: PopupWindow
        var size = current.size
        var landscape = current.landscape

        box.addView(label(c, "Tamaño de la hoja", 15f))
        val sizes = GridLayout(c).apply { columnCount = 4 }
        box.addView(sizes, topMargin(c, 8f))
        // "Del PDF" sólo en las notas que vienen de un PDF.
        val offered = PageSize.entries.filter { it != PageSize.CUSTOM || current.size == PageSize.CUSTOM }
        val sizeCells = offered.associateWith { ps ->
            LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = c.getDrawable(R.drawable.bg_tool)
                val p = c.dp(6f).toInt()
                setPadding(p, p, p, p)
                addView(PageShape(c, ps, dark, current.customWidth, current.customHeight), LinearLayout.LayoutParams(c.dp(104f).toInt(), c.dp(52f).toInt()))
                addView(label(c, ps.label, 12f).apply { gravity = Gravity.CENTER })
            }.also { sizes.addView(it, GridLayout.LayoutParams().apply { width = c.dp(122f).toInt() }) }
        }
        val turn = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val portrait = chip(c, "Vertical")
        val wide = chip(c, "Horizontal")
        turn.addView(portrait, LinearLayout.LayoutParams(0, c.dp(40f).toInt(), 1f))
        turn.addView(wide, LinearLayout.LayoutParams(0, c.dp(40f).toInt(), 1f))
        box.addView(turn, topMargin(c, 8f))

        fun refresh() {
            for ((ps, v) in sizeCells) {
                v.isSelected = ps == size
                (v.getChildAt(0) as PageShape).landscape = landscape
            }
            portrait.isSelected = !landscape
            wide.isSelected = landscape
            turn.alpha = if (size == PageSize.INFINITE) 0.35f else 1f
            portrait.isEnabled = size != PageSize.INFINITE
            wide.isEnabled = size != PageSize.INFINITE
        }
        for ((ps, v) in sizeCells) {
            v.setOnClickListener {
                if (ps == size) return@setOnClickListener
                size = ps
                onFormat(size, landscape)
                refresh()
            }
        }
        for ((v, value) in listOf(portrait to false, wide to true)) {
            v.setOnClickListener {
                if (value == landscape) return@setOnClickListener
                landscape = value
                onFormat(size, landscape)
                refresh()
            }
        }
        refresh()

        // El color de la hoja: tonos claros para que la tinta se siga leyendo.
        box.addView(label(c, "Color de la hoja", 15f), topMargin(c, 14f))
        if (dark) box.addView(hint(c, "En modo oscuro la hoja se ve oscura; el color se ve en modo claro y en el PDF."), topMargin(c, 2f))
        box.addView(colorChoices(activity, Paper.COLOR, PAPER_COLORS, paperColor, columns = 11, onPick = onPaperColor), topMargin(c, 6f))

        box.addView(label(c, "Fondo", 15f), topMargin(c, 14f))
        val grid = GridLayout(c).apply { columnCount = 4 }
        box.addView(grid, topMargin(c, 8f))
        for (bg in Background.entries) {
            val cell = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = c.getDrawable(R.drawable.bg_tool)
                isSelected = bg == currentBackground
                val p = c.dp(6f).toInt()
                setPadding(p, p, p, p)
                addView(PaperSample(c, bg, dark), LinearLayout.LayoutParams(c.dp(104f).toInt(), c.dp(72f).toInt()))
                addView(label(c, bg.label, 12f).apply { gravity = Gravity.CENTER })
                setOnClickListener {
                    popup.dismiss()
                    onBackground(bg)
                }
            }
            grid.addView(cell, GridLayout.LayoutParams().apply { width = c.dp(122f).toInt() })
        }
        popup = show(activity, anchor, box, 520f)
    }

    /** Qué agarra el lazo, y pegar lo copiado. */
    fun lasso(
        activity: Activity,
        anchor: View,
        filter: LassoFilter,
        canPaste: Boolean,
        onFilter: (LassoFilter) -> Unit,
        onPaste: () -> Unit,
    ) {
        val c: Context = activity
        val box = column(c)
        lateinit var popup: PopupWindow
        var f = filter
        box.addView(label(c, "Qué agarra el lazo", 15f))
        box.addView(switch(c, "Lápiz y marcador", f.pens) { f = f.copy(pens = it); onFilter(f) }, topMargin(c, 6f))
        box.addView(switch(c, "Resaltador", f.highlighters) { f = f.copy(highlighters = it); onFilter(f) })
        box.addView(switch(c, "Imágenes", f.images) { f = f.copy(images = it); onFilter(f) })
        box.addView(switch(c, "Textos", f.texts) { f = f.copy(texts = it); onFilter(f) })
        box.addView(switch(c, "Gráficos, figuras, audios y videos", f.widgets) { f = f.copy(widgets = it); onFilter(f) })
        box.addView(
            hint(c, "Rodeá lo que quieras elegir, o tocalo. Arrastralo para moverlo; el punto de la esquina le cambia el tamaño y el de arriba lo gira."),
            topMargin(c, 8f),
        )
        if (canPaste) {
            box.addView(
                Button(c, null, 0, android.R.style.Widget_DeviceDefault_Button_Borderless_Colored).apply {
                    text = "Pegar lo copiado"
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_paste, 0, 0, 0)
                    compoundDrawablePadding = c.dp(8f).toInt()
                    setOnClickListener {
                        popup.dismiss()
                        onPaste()
                    }
                },
                topMargin(c, 6f),
            )
        }
        popup = show(activity, anchor, box, 320f)
    }

    /**
     * Los stickers guardados, para pegar uno tocándolo; mantener apretado lo borra. `thumbs` son
     * sus miniaturas (null mientras cargan).
     */
    fun stickers(
        activity: Activity,
        anchor: View,
        thumbs: List<android.graphics.Bitmap?>,
        onPick: (Int) -> Unit,
        onDelete: (Int) -> Unit,
    ) {
        val c: Context = activity
        val box = column(c)
        lateinit var popup: PopupWindow
        box.addView(label(c, "Stickers", 15f))
        if (thumbs.isEmpty()) {
            box.addView(hint(c, "Todavía no hay. Elegí algo con el lazo y tocá la estrella para guardarlo como sticker."), topMargin(c, 8f))
        } else {
            box.addView(hint(c, "Tocá uno para pegarlo; mantenelo apretado para borrarlo."), topMargin(c, 4f))
        }
        val grid = GridLayout(c).apply { columnCount = 4 }
        box.addView(grid, topMargin(c, 8f))
        thumbs.forEachIndexed { i, bitmap ->
            grid.addView(
                ImageView(c).apply {
                    setImageBitmap(bitmap)
                    background = c.getDrawable(R.drawable.bg_tool)
                    val p = c.dp(6f).toInt()
                    setPadding(p, p, p, p)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setOnClickListener {
                        popup.dismiss()
                        onPick(i)
                    }
                    setOnLongClickListener {
                        popup.dismiss()
                        onDelete(i)
                        true
                    }
                },
                GridLayout.LayoutParams().apply {
                    width = c.dp(84f).toInt()
                    height = c.dp(84f).toInt()
                },
            )
        }
        popup = show(activity, anchor, box, 380f)
    }

    /** Agregar, insertar o borrar hojas; `current` y `count` para decir en cuál se está. */
    fun pages(
        activity: Activity,
        anchor: View,
        current: Int,
        count: Int,
        stripShown: Boolean,
        onToggleStrip: () -> Unit,
        onAddAtEnd: () -> Unit,
        onInsert: (after: Boolean) -> Unit,
        onDelete: () -> Unit,
    ) {
        val c: Context = activity
        val box = column(c)
        lateinit var popup: PopupWindow
        box.addView(label(c, "Hojas", 15f))
        box.addView(hint(c, "Estás en la hoja ${current + 1} de $count."), topMargin(c, 2f))
        fun action(text: String, icon: Int, color: Int? = null, run: () -> Unit) = Button(c, null, 0, android.R.style.Widget_DeviceDefault_Button_Borderless_Colored).apply {
            this.text = text
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            compoundDrawablePadding = c.dp(10f).toInt()
            color?.let { setTextColor(it) }
            setOnClickListener {
                popup.dismiss()
                run()
            }
        }
        box.addView(action(if (stripShown) "Esconder las miniaturas" else "Mostrar las miniaturas", R.drawable.ic_pages, run = onToggleStrip), topMargin(c, 6f))
        box.addView(action("Agregar una hoja al final", R.drawable.ic_add, run = onAddAtEnd))
        box.addView(action("Insertar una hoja después de esta", R.drawable.ic_add) { onInsert(true) })
        box.addView(action("Insertar una hoja antes de esta", R.drawable.ic_add) { onInsert(false) })
        box.addView(action("Borrar esta hoja…", R.drawable.ic_delete, 0xFFD93025.toInt(), onDelete))
        popup = show(activity, anchor, box, 340f)
    }

    /**
     * Para elegir un color: el de siempre (`original`, tachado), los de `palette`, el elegido a mano
     * si no está en la paleta, y un "+" que abre el selector libre. Cada toque llama a `onPick`
     * (null: el de siempre).
     */
    fun colorChoices(activity: Activity, original: Int, palette: List<Int>, current: Int?, columns: Int, onPick: (Int?) -> Unit): View {
        val c: Context = activity
        val grid = GridLayout(c).apply { columnCount = columns }
        val all = ArrayList<Pair<Int?, Swatch>>()
        var chosen = current
        lateinit var custom: Swatch
        fun select(value: Int?) {
            chosen = value
            val inPalette = value == null || value in palette
            custom.visibility = if (inPalette) View.GONE else View.VISIBLE
            if (!inPalette && value != null) custom.color = value
            for ((v, w) in all) {
                w.isSelected = when {
                    w === custom -> !inPalette
                    w.tag == PLUS -> false
                    else -> v == value
                }
                w.invalidate()
            }
        }
        fun add(value: Int?, sw: Swatch) {
            all.add(value to sw)
            grid.addView(sw, GridLayout.LayoutParams().apply {
                width = c.dp(40f).toInt()
                height = c.dp(40f).toInt()
            })
        }
        for (value in listOf<Int?>(null) + palette) {
            add(value, Swatch(c, value ?: original, original = value == null).apply {
                contentDescription = if (value == null) "El de siempre" else "Color"
                setOnClickListener {
                    select(value)
                    onPick(value)
                }
            })
        }
        custom = Swatch(c, current ?: original, original = false).apply {
            contentDescription = "El color elegido"
            setOnClickListener { chosen?.let(onPick) }
        }
        add(null, custom)
        add(null, Swatch(c, original, original = false, plus = true).apply {
            tag = PLUS
            contentDescription = "Otro color"
            tip(contentDescription)
            setOnClickListener {
                pickColor(activity, chosen ?: original) { picked ->
                    select(picked)
                    onPick(picked)
                }
            }
        })
        select(current)
        return grid
    }

    private const val PLUS = "otro-color"

    /** El selector libre de color en una ventanita, con una muestra; "Aceptar" lo elige. */
    fun pickColor(activity: Activity, initial: Int, onPick: (Int) -> Unit) {
        val c: Context = activity
        val box = column(c)
        val preview = View(c).apply { setBackgroundColor(initial) }
        val picker = ColorPickerView(c).apply { setColor(initial) }
        box.addView(picker)
        box.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(28f).toInt()).apply { topMargin = c.dp(10f).toInt() })
        var color = initial
        picker.onChange = { picked, _ ->
            color = picked
            preview.setBackgroundColor(picked)
        }
        android.app.AlertDialog.Builder(activity)
            .setTitle("Otro color")
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ -> onPick(color) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Exportar: en qué formato, con para qué sirve cada uno. */
    fun exportFormats(activity: Activity, anchor: View, onPick: (com.octavio.tinta.export.Exporter.Format) -> Unit) {
        val c: Context = activity
        val box = column(c)
        lateinit var popup: PopupWindow
        box.addView(label(c, "Exportar como", 15f))
        for (f in com.octavio.tinta.export.Exporter.Format.entries) {
            box.addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                background = c.getDrawable(R.drawable.bg_tool)
                val p = c.dp(10f).toInt()
                setPadding(p, p, p, p)
                addView(label(c, f.label, 15f).apply { setTextColor(c.getColor(R.color.accent)) })
                addView(hint(c, f.detail))
                setOnClickListener {
                    popup.dismiss()
                    onPick(f)
                }
            }, topMargin(c, 6f))
        }
        popup = show(activity, anchor, box, 380f)
    }

    /** Colores para pintar lo elegido: los de la paleta y los guardados. */
    fun colors(activity: Activity, anchor: View, colors: List<Int>, dark: Boolean, onPick: (Int) -> Unit) {
        val c: Context = activity
        val box = column(c)
        lateinit var popup: PopupWindow
        box.addView(label(c, "Color de lo elegido", 15f))
        val grid = GridLayout(c).apply { columnCount = 6 }
        box.addView(grid, topMargin(c, 8f))
        for (color in colors.distinct()) {
            grid.addView(
                DotView(c, Appearance.ink(color, Tool.PEN, dark), c.dp(13f)).apply {
                    setOnClickListener {
                        popup.dismiss()
                        onPick(color)
                    }
                },
                GridLayout.LayoutParams().apply {
                    width = c.dp(44f).toInt()
                    height = c.dp(44f).toInt()
                },
            )
        }
        popup = show(activity, anchor, box, 300f)
    }

    // --- Armado ---

    private fun column(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        val p = c.dp(16f).toInt()
        setPadding(p, p, p, p)
    }

    private fun label(c: Context, text: String, size: Float) = TextView(c).apply {
        this.text = text
        textSize = size
        setTextColor(c.getColor(R.color.ink))
    }

    private fun hint(c: Context, text: String) = label(c, text, 12f).apply { setTextColor(c.getColor(R.color.muted)) }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun switch(c: Context, text: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(c).apply {
        this.text = text
        textSize = 15f
        setTextColor(c.getColor(R.color.ink))
        isChecked = checked
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    private fun chip(c: Context, text: String) = TextView(c).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(c.getColor(R.color.ink))
        background = c.getDrawable(R.drawable.bg_tool)
    }

    private fun topMargin(c: Context, dp: Float) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = c.dp(dp).toInt()
        }

    private fun defaultSize(tool: Tool) = when (tool) {
        Tool.PEN -> 2f
        Tool.MARKER -> 3f
        Tool.HIGHLIGHTER -> 16f
    }

    private fun toProgress(pen: Pen): Int {
        val f = (pen.size - pen.tool.minSize) / (pen.tool.maxSize - pen.tool.minSize)
        return (sqrt(f.coerceIn(0f, 1f)) * 1000).toInt()
    }

    /**
     * Muestra `content` junto a `anchor`: abajo si entra, si no arriba, y si tampoco, al costado
     * (con la barra vertical). Siempre dentro de la pantalla.
     */
    private fun show(activity: Activity, anchor: View, content: View, widthDp: Float): PopupWindow {
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        return place(activity, anchor.rootView, content, widthDp, loc[0], loc[1], anchor.width, anchor.height)
    }

    /**
     * Muestra `content` junto al rectángulo (ax, ay, aw, ah) de la ventana: abajo si entra, si no
     * arriba, y si tampoco, al costado. Siempre dentro de lo que se ve (sin taparse con las barras
     * del sistema) y siempre deslizable: con la barra en vertical o la pantalla dividida puede no
     * entrar entera.
     */
    private fun place(activity: Activity, root: View, content: View, widthDp: Float, ax: Int, ay: Int, aw: Int, ah: Int): PopupWindow {
        val c: Context = activity
        val margin = c.dp(8f).toInt()
        val bars = root.rootWindowInsets?.let {
            WindowInsetsCompat.toWindowInsetsCompat(it, root)
                .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        }
        val top = (bars?.top ?: 0) + margin
        val bottom = root.height - (bars?.bottom ?: 0) - margin
        val width = c.dp(widthDp).toInt().coerceAtMost(root.width - 2 * margin)
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val height = content.measuredHeight.coerceAtMost((bottom - top).coerceAtLeast(c.dp(120f).toInt()))
        val scroll = ScrollView(c).apply {
            isVerticalScrollBarEnabled = true
            addView(content)
        }
        val popup = PopupWindow(scroll, width, height, true).apply {
            setBackgroundDrawable(c.getDrawable(R.drawable.bg_popup))
            elevation = c.dp(10f)
            isOutsideTouchable = true
        }
        val gap = c.dp(6f).toInt()
        var x = ax + aw / 2 - width / 2
        var y = ay + ah + gap
        if (y + height > bottom) y = ay - height - gap
        if (y < top) {
            // Ni abajo ni arriba: al costado, del lado con más lugar.
            y = (ay + ah / 2 - height / 2).coerceIn(top, (bottom - height).coerceAtLeast(top))
            x = if (ax > root.width / 2) ax - width - gap else ax + aw + gap
        }
        x = x.coerceIn(margin, (root.width - width - margin).coerceAtLeast(margin))
        popup.showAtLocation(root, Gravity.NO_GRAVITY, x, y)
        return popup
    }

    /**
     * Lo que se ofrece al mantener apretado con el lazo en (x, y) de la ventana: pegar lo copiado o
     * cortado, o pegar un sticker.
     */
    fun pasteHere(activity: Activity, root: View, x: Int, y: Int, canPaste: Boolean, onPaste: () -> Unit, onStickers: () -> Unit) {
        val c: Context = activity
        val box = column(c)
        lateinit var popup: PopupWindow
        fun action(text: String, icon: Int, enabled: Boolean, run: () -> Unit) = Button(c, null, 0, android.R.style.Widget_DeviceDefault_Button_Borderless_Colored).apply {
            this.text = text
            isAllCaps = false
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.4f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            compoundDrawablePadding = c.dp(10f).toInt()
            setOnClickListener {
                popup.dismiss()
                run()
            }
        }
        box.addView(action("Pegar acá", R.drawable.ic_paste, canPaste, onPaste))
        if (!canPaste) box.addView(hint(c, "No hay nada copiado: elegí algo con el lazo y tocá Copiar o Cortar."))
        box.addView(action("Pegar un sticker…", R.drawable.ic_sticker, true, onStickers))
        popup = place(activity, root, box, 260f, x, y, 0, 0)
    }

    /** Opciones rápidas de un lápiz al mantenerlo apretado. */
    fun penQuick(activity: Activity, anchor: View, canDelete: Boolean, onOptions: () -> Unit, onDelete: () -> Unit) {
        val c: Context = activity
        val box = column(c)
        lateinit var popup: PopupWindow
        box.addView(Button(c, null, 0, android.R.style.Widget_DeviceDefault_Button_Borderless_Colored).apply {
            text = "Opciones del lápiz"
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener {
                popup.dismiss()
                onOptions()
            }
        })
        box.addView(Button(c, null, 0, android.R.style.Widget_DeviceDefault_Button_Borderless_Colored).apply {
            text = if (canDelete) "Quitar este lápiz" else "Es el único lápiz: no se puede quitar"
            isAllCaps = false
            isEnabled = canDelete
            alpha = if (canDelete) 1f else 0.5f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(0xFFD93025.toInt())
            setOnClickListener {
                popup.dismiss()
                onDelete()
            }
        })
        popup = show(activity, anchor, box, 280f)
    }
}

/** Una raya de muestra con el color y el grosor del lápiz. */
@SuppressLint("ViewConstructor")
private class StrokePreview(context: Context, private val dark: Boolean) : View(context) {
    var pen: Pen? = null
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val p = pen ?: return
        val stored = p.tool.storedColor(p.color)
        paint.color = Appearance.ink(stored, p.tool, dark)
        paint.strokeCap = if (p.tool == Tool.HIGHLIGHTER) Paint.Cap.SQUARE else Paint.Cap.ROUND
        // Al zoom normal: 1 dp del mundo = 1 dp en pantalla.
        paint.strokeWidth = (p.size * resources.displayMetrics.density).coerceAtMost(height * 0.9f)
        val pad = height / 2f
        canvas.drawLine(pad, height / 2f, width - pad, height / 2f, paint)
    }
}

/**
 * Cómo cambia el ancho de la pluma de apoyar suave (izquierda) a apoyar fuerte (derecha), agrandado
 * para que se vea aunque la pluma sea fina.
 */
@SuppressLint("ViewConstructor")
private class PressurePreview(context: Context, private val dark: Boolean) : View(context) {
    var pen: Pen? = null
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.dp(11f)
        color = context.getColor(R.color.muted)
    }
    private val path = android.graphics.Path()

    override fun onDraw(canvas: Canvas) {
        val p = pen ?: return
        paint.color = Appearance.ink(p.tool.storedColor(p.color), p.tool, dark)
        val (low, high) = Sensitivity.range(p.sensitivity)
        val labelW = context.dp(44f)
        val left = labelW
        val right = width - labelW
        val cy = height / 2f
        // Media altura máxima: el ancho más grande posible ocupa casi todo el alto.
        val unit = height * 0.42f / Sensitivity.range(Sensitivity.MAX).second
        path.rewind()
        path.moveTo(left, cy - unit * low)
        path.lineTo(right, cy - unit * high)
        path.lineTo(right, cy + unit * high)
        path.lineTo(left, cy + unit * low)
        path.close()
        canvas.drawPath(path, paint)
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("suave", 0f, cy + text.textSize / 3, text)
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText("fuerte", width.toFloat(), cy + text.textSize / 3, text)
    }
}

/** Muestra de un fondo de hoja, al zoom normal. */
@SuppressLint("ViewConstructor")
private class PaperSample(context: Context, private val bg: Background, private val dark: Boolean) : View(context) {
    private val paper = Paper()

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, context.dp(6f))
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        // Un poco alejado para que se vea el dibujo del fondo en poco lugar; la hoja de errores,
        // más todavía, para que entre el título y las columnas.
        val zoom = if (bg == Background.ERRORS) 0.22f else 0.6f
        val scale = resources.displayMetrics.density * zoom
        paper.draw(canvas, bg, scale, 0f, 0f, width.toFloat(), height.toFloat(), dark, strong = true)
    }
}

/** Muestra de un tamaño de hoja: un rectángulo con sus proporciones (todos a la misma escala); la infinita, un ∞. */
@SuppressLint("ViewConstructor")
private class PageShape(
    context: Context,
    private val size: PageSize,
    private val dark: Boolean,
    private val customWidth: Float,
    private val customHeight: Float,
) : View(context) {
    var landscape = false
        set(value) {
            field = value
            invalidate()
        }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Paper.DARK else Paper.COLOR }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1f)
        color = context.getColor(R.color.muted)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = context.dp(28f)
        color = context.getColor(R.color.muted)
    }

    override fun onDraw(canvas: Canvas) {
        if (size == PageSize.INFINITE) {
            canvas.drawText("∞", width / 2f, height / 2f - (text.ascent() + text.descent()) / 2, text)
            return
        }
        val baseW = if (size == PageSize.CUSTOM) customWidth else size.widthMm
        val baseH = if (size == PageSize.CUSTOM) customHeight else size.heightMm
        val w = if (landscape) baseH else baseW
        val h = if (landscape) baseW else baseH
        // La más grande, la A3, ocupa todo el alto.
        val s = height * 0.92f / PageSize.A3.heightMm
        val l = (width - w * s) / 2
        val t = (height - h * s) / 2
        canvas.drawRect(l, t, l + w * s, t + h * s, fill)
        canvas.drawRect(l, t, l + w * s, t + h * s, edge)
    }
}

/** Una muestra del tipo de línea, con el patrón y el color del lápiz. */
@SuppressLint("ViewConstructor")
class DashSample(context: Context, private val dash: Dash, dark: Boolean) : View(context) {
    var color = if (dark) 0xFFE8E8E8.toInt() else 0xFF202124.toInt()
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = context.dp(2.5f)
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = color
        val w = paint.strokeWidth
        val lengths = dash.lengths(w)
        paint.pathEffect = lengths?.let { l ->
            // El trazo redondo agrega medio grosor a cada punta: se descuenta del tramo.
            android.graphics.DashPathEffect(FloatArray(l.size) { i -> if (i % 2 == 0) (l[i] - w).coerceAtLeast(0.01f) else l[i] + w }, 0f)
        }
        val y = height / 2f
        canvas.drawLine(w * 2, y, width - w * 2, y, paint)
    }
}
