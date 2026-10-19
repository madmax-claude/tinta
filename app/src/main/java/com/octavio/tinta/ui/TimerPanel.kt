package com.octavio.tinta.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.octavio.tinta.R
import com.octavio.tinta.dp
import com.octavio.tinta.tip
import kotlin.math.abs
import kotlin.math.hypot

/**
 * El temporizador: cuenta regresiva o cronómetro. Vive mientras la app está abierta y es el mismo
 * en las dos mitades de la pantalla; se sigue contando aunque el panel esté escondido.
 */
object TimerState {
    var countdown = true
    var durationMs = 25 * 60_000L
    private var startedAt = 0L
    private var accumulated = 0L

    /** Ya sonó el final de esta cuenta (para que no suene en las dos mitades). */
    var rang = false

    val running get() = startedAt != 0L

    fun elapsed(now: Long = SystemClock.elapsedRealtime()) = accumulated + if (running) now - startedAt else 0L

    fun remaining(now: Long = SystemClock.elapsedRealtime()) = (durationMs - elapsed(now)).coerceAtLeast(0L)

    val finished get() = countdown && remaining() == 0L && elapsed() > 0L

    fun start() {
        if (running) return
        if (finished) reset()
        startedAt = SystemClock.elapsedRealtime()
        rang = false
    }

    fun pause() {
        if (!running) return
        accumulated = elapsed()
        startedAt = 0L
    }

    fun reset() {
        startedAt = 0L
        accumulated = 0L
        rang = false
    }

    /** Lo que se muestra: lo que falta en la cuenta regresiva, lo que pasó en el cronómetro. */
    fun text(): String {
        val ms = if (countdown) remaining() else elapsed()
        // La cuenta regresiva redondea para arriba: 0:00 recién cuando terminó.
        val s = if (countdown) (ms + 999) / 1000 else ms / 1000
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}

/**
 * Panel flotante del temporizador. Se arrastra desde la manija; tocar el tiempo muestra u oculta
 * las opciones (modo y duración). La cruz lo esconde (el tiempo sigue corriendo).
 */
@SuppressLint("ViewConstructor")
class TimerPanel(context: Context) : LinearLayout(context) {
    /** Cada vez que cambia el tiempo (para mostrarlo en la barra lateral si el panel está escondido). */
    var onTick: ((String) -> Unit)? = null

    /** Terminó la cuenta regresiva. */
    var onFinished: (() -> Unit)? = null

    var onHide: (() -> Unit)? = null

    /** Terminó de moverlo; (fx, fy) es su posición como fracción del espacio libre. */
    var onMoved: ((Float, Float) -> Unit)? = null

    private val time = TextView(context)

    /** Color del tiempo (cambia con el color elegido para las barras). */
    var textColor = context.getColor(R.color.ink)
        set(value) {
            field = value
            update()
        }
    private val play = ImageButton(context)
    private val options = LinearLayout(context)
    private val modeCountdown = chip("Cuenta regresiva")
    private val modeStopwatch = chip("Cronómetro")
    private val presets = LinearLayout(context)
    private var fx = 1f
    private var fy = 0f

    private val tick = object : Runnable {
        override fun run() {
            update()
            if (TimerState.countdown && TimerState.running && TimerState.remaining() == 0L) {
                TimerState.pause()
                if (!TimerState.rang) {
                    TimerState.rang = true
                    ring()
                    onFinished?.invoke()
                }
                update()
            }
            postDelayed(this, if (TimerState.running) 200L else 1000L)
        }
    }

    init {
        orientation = VERTICAL
        background = context.getDrawable(R.drawable.bg_toolbar)
        elevation = context.dp(8f)
        val pad = context.dp(6f).toInt()
        setPadding(pad, pad, pad, pad)

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val grip = ImageView(context).apply {
            setImageResource(R.drawable.ic_drag)
            alpha = 0.6f
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Mover el temporizador"
        }
        row.addView(grip, LayoutParams(context.dp(32f).toInt(), context.dp(44f).toInt()))
        time.apply {
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            fontFeatureSettings = "tnum" // números del mismo ancho: el panel no cambia de tamaño cada segundo
            setTextColor(context.getColor(R.color.ink))
            gravity = Gravity.CENTER
            minWidth = context.dp(110f).toInt()
            background = context.getDrawable(R.drawable.bg_tool)
            contentDescription = "Tiempo; tocalo para las opciones"
            setOnClickListener { options.visibility = if (options.visibility == VISIBLE) GONE else VISIBLE }
        }
        row.addView(time, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(48f).toInt()))
        play.apply {
            background = context.getDrawable(R.drawable.bg_tool)
            setOnClickListener {
                if (TimerState.running) TimerState.pause() else TimerState.start()
                update()
            }
        }
        row.addView(play, LayoutParams(context.dp(44f).toInt(), context.dp(44f).toInt()))
        row.addView(button(R.drawable.ic_undo, "Volver a empezar") {
            TimerState.reset()
            update()
        })
        row.addView(button(R.drawable.ic_close, "Esconder el temporizador (sigue contando)") { onHide?.invoke() })
        addView(row)

        options.orientation = VERTICAL
        options.visibility = GONE
        val modes = LinearLayout(context).apply { orientation = HORIZONTAL }
        modes.addView(modeCountdown, LayoutParams(0, context.dp(36f).toInt(), 1f))
        modes.addView(modeStopwatch, LayoutParams(0, context.dp(36f).toInt(), 1f))
        options.addView(modes, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(4f).toInt()
        })
        modeCountdown.setOnClickListener { setMode(countdown = true) }
        modeStopwatch.setOnClickListener { setMode(countdown = false) }
        presets.orientation = HORIZONTAL
        for (min in listOf(5, 10, 15, 25, 45, 60)) {
            presets.addView(chip("$min").apply {
                setOnClickListener {
                    TimerState.durationMs = min * 60_000L
                    TimerState.reset()
                    update()
                }
            }, LayoutParams(0, context.dp(36f).toInt(), 1f))
        }
        for ((label, delta) in listOf("−1" to -1, "+1" to 1)) {
            presets.addView(chip(label).apply {
                contentDescription = if (delta > 0) "Un minuto más" else "Un minuto menos"
                setOnClickListener {
                    TimerState.durationMs = (TimerState.durationMs + delta * 60_000L).coerceIn(60_000L, 600 * 60_000L)
                    if (!TimerState.running) TimerState.reset()
                    update()
                }
            }, LayoutParams(0, context.dp(36f).toInt(), 1f))
        }
        options.addView(presets, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(4f).toInt()
        })
        addView(options)
        setupDrag(grip)
        update()
    }

    private fun button(icon: Int, description: String, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        background = context.getDrawable(R.drawable.bg_tool)
        contentDescription = description
        tip(description)
        setOnClickListener { onClick() }
        layoutParams = LayoutParams(context.dp(44f).toInt(), context.dp(44f).toInt())
    }

    private fun chip(text: String) = TextView(context).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.ink))
        background = context.getDrawable(R.drawable.bg_tool)
    }

    private fun setMode(countdown: Boolean) {
        if (TimerState.countdown == countdown) return
        TimerState.countdown = countdown
        TimerState.reset()
        update()
    }

    private fun update() {
        time.text = TimerState.text()
        val done = TimerState.finished
        time.setTextColor(if (done) 0xFFD93025.toInt() else textColor)
        play.setImageResource(if (TimerState.running) R.drawable.ic_pause else R.drawable.ic_play)
        play.contentDescription = if (TimerState.running) "Pausar" else "Empezar"
        modeCountdown.isSelected = TimerState.countdown
        modeStopwatch.isSelected = !TimerState.countdown
        presets.visibility = if (TimerState.countdown) VISIBLE else GONE
        onTick?.invoke(TimerState.text())
    }

    /** Tres pitidos con el volumen de las alarmas. */
    private fun ring() {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 90) }.getOrNull() ?: return
        var n = 0
        val beep = object : Runnable {
            override fun run() {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
                if (++n < 3) postDelayed(this, 700) else postDelayed({ tone.release() }, 800)
            }
        }
        post(beep)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tick)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(tick)
    }

    /** Ubica el panel según la fracción guardada. */
    fun placeAt(fx: Float, fy: Float) {
        this.fx = fx.coerceIn(0f, 1f)
        this.fy = fy.coerceIn(0f, 1f)
        clamp()
    }

    private fun clamp() {
        val parent = parent as? ViewGroup ?: return
        x = fx * (parent.width - width).coerceAtLeast(0)
        y = fy * (parent.height - height).coerceAtLeast(0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { clamp() }
    }

    private fun rememberPosition(parent: ViewGroup) {
        fx = if (abs(parent.width - width) > 0) x / (parent.width - width) else 0f
        fy = if (abs(parent.height - height) > 0) y / (parent.height - height) else 0f
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(handle: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        handle.setOnTouchListener { _, e ->
            val parent = parent as? ViewGroup ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = x
                    startY = y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        x = (startX + dx).coerceIn(0f, (parent.width - width).coerceAtLeast(0).toFloat())
                        y = (startY + dy).coerceIn(0f, (parent.height - height).coerceAtLeast(0).toFloat())
                        // Al día mientras se arrastra: si el panel cambia de tamaño en el medio
                        // (el número del reloj), se acomoda donde está y no donde estaba.
                        rememberPosition(parent)
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) {
                    rememberPosition(parent)
                    onMoved?.invoke(fx, fy)
                }
            }
            true
        }
    }
}
