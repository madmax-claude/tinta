package com.octavio.tinta.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import com.octavio.tinta.dp

/**
 * Selector de cualquier color: arriba un cuadro de saturación (horizontal) y brillo (vertical);
 * abajo la barra de tonos, que se recorre para girar por todo el círculo de colores.
 */
class ColorPickerView(context: Context) : View(context) {
    /** Se llama mientras se arrastra; `done` es true al soltar. */
    var onChange: ((color: Int, done: Boolean) -> Unit)? = null

    private val hsv = floatArrayOf(0f, 1f, 1f)
    private val square = RectF()
    private val hueBar = RectF()
    private val squarePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2.5f)
        color = Color.WHITE
    }
    private val thumbShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(4.5f)
        color = 0x55000000
    }
    private val corner = context.dp(8f)
    private var dragging = NONE

    val color: Int get() = Color.HSVToColor(hsv)

    fun setColor(c: Int) {
        Color.colorToHSV(c, hsv)
        updateSquareShader()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, context.dp(200f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val barHeight = context.dp(30f)
        val gap = context.dp(14f)
        val pad = context.dp(13f)
        square.set(pad, pad, w - pad, h - pad - barHeight - gap)
        hueBar.set(pad, h - pad - barHeight, w - pad, h - pad)
        val hues = IntArray(13) { Color.HSVToColor(floatArrayOf(it * 30f, 1f, 1f)) }
        huePaint.shader = LinearGradient(hueBar.left, 0f, hueBar.right, 0f, hues, null, Shader.TileMode.CLAMP)
        shadePaint.shader = LinearGradient(0f, square.top, 0f, square.bottom, 0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
        updateSquareShader()
    }

    private fun updateSquareShader() {
        if (square.width() <= 0f) return
        val pure = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        squarePaint.shader = LinearGradient(square.left, 0f, square.right, 0f, Color.WHITE, pure, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(square, corner, corner, squarePaint)
        canvas.drawRoundRect(square, corner, corner, shadePaint)
        canvas.drawRoundRect(hueBar, corner, corner, huePaint)
        val r = context.dp(9f)
        val sx = square.left + hsv[1] * square.width()
        val sy = square.top + (1 - hsv[2]) * square.height()
        canvas.drawCircle(sx, sy, r, thumbShadow)
        canvas.drawCircle(sx, sy, r, thumb)
        val hx = hueBar.left + hsv[0] / 360f * hueBar.width()
        canvas.drawCircle(hx, hueBar.centerY(), r + context.dp(2f), thumbShadow)
        canvas.drawCircle(hx, hueBar.centerY(), r + context.dp(2f), thumb)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = when {
                    e.y >= hueBar.top - context.dp(8f) -> HUE
                    else -> SQUARE
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                apply(e.x, e.y)
                onChange?.invoke(color, false)
            }
            MotionEvent.ACTION_MOVE -> {
                apply(e.x, e.y)
                onChange?.invoke(color, false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                apply(e.x, e.y)
                dragging = NONE
                onChange?.invoke(color, true)
            }
        }
        return true
    }

    private fun apply(x: Float, y: Float) {
        when (dragging) {
            HUE -> {
                hsv[0] = ((x - hueBar.left) / hueBar.width()).coerceIn(0f, 1f) * 359.9f
                updateSquareShader()
            }
            SQUARE -> {
                hsv[1] = ((x - square.left) / square.width()).coerceIn(0f, 1f)
                hsv[2] = 1 - ((y - square.top) / square.height()).coerceIn(0f, 1f)
            }
        }
        invalidate()
    }

    private companion object {
        const val NONE = 0
        const val SQUARE = 1
        const val HUE = 2
    }
}
