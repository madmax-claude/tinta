package com.octavio.tinta.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.octavio.tinta.R
import com.octavio.tinta.dp
import kotlin.math.abs
import kotlin.math.min

/** Para elegir qué parte de una imagen queda: se arrastran los bordes, las esquinas o el recuadro. */
object CropDialog {
    /** `onCrop` recibe el recorte como fracción de la imagen (0 a 1 en cada eje). */
    fun show(activity: Activity, bitmap: Bitmap, onCrop: (RectF) -> Unit) {
        val view = CropView(activity, bitmap)
        val box = FrameLayout(activity).apply {
            val p = activity.dp(16f).toInt()
            setPadding(p, p, p, 0)
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(420f).toInt()))
        }
        AlertDialog.Builder(activity)
            .setTitle("Recortar la imagen")
            .setView(box)
            .setPositiveButton("Recortar") { _, _ -> if (!view.crop.isFull()) onCrop(RectF(view.crop)) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun RectF.isFull() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f
}

@SuppressLint("ViewConstructor")
private class CropView(context: Context, private val bitmap: Bitmap) : View(context) {
    /** El recorte, en fracciones de la imagen. */
    val crop = RectF(0f, 0f, 1f, 1f)

    private val image = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint().apply { color = 0x88000000.toInt() }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f)
        color = 0xFFFFFFFF.toInt()
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val path = Path()

    // Qué se arrastra: bordes (combinables en las esquinas) o todo el recuadro.
    private var dragLeft = false
    private var dragTop = false
    private var dragRight = false
    private var dragBottom = false
    private var dragAll = false
    private var lastX = 0f
    private var lastY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val s = min(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
        val bw = bitmap.width * s
        val bh = bitmap.height * s
        image.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2)
    }

    private fun sx(f: Float) = image.left + f * image.width()
    private fun sy(f: Float) = image.top + f * image.height()

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(bitmap, null, image, bitmapPaint)
        val r = RectF(sx(crop.left), sy(crop.top), sx(crop.right), sy(crop.bottom))
        // Lo que se va queda oscurecido.
        path.rewind()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(image, Path.Direction.CW)
        path.addRect(r, Path.Direction.CW)
        canvas.drawPath(path, shade)
        canvas.drawRect(r, frame)
        val k = context.dp(7f)
        for (x in floatArrayOf(r.left, r.right)) for (y in floatArrayOf(r.top, r.bottom)) canvas.drawCircle(x, y, k, knob)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val near = context.dp(28f)
                val l = sx(crop.left)
                val t = sy(crop.top)
                val r = sx(crop.right)
                val b = sy(crop.bottom)
                val insideY = e.y in t - near..b + near
                val insideX = e.x in l - near..r + near
                dragLeft = abs(e.x - l) < near && insideY
                dragRight = !dragLeft && abs(e.x - r) < near && insideY
                dragTop = abs(e.y - t) < near && insideX
                dragBottom = !dragTop && abs(e.y - b) < near && insideX
                dragAll = !(dragLeft || dragRight || dragTop || dragBottom) && e.x in l..r && e.y in t..b
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.x - lastX) / image.width()
                val dy = (e.y - lastY) / image.height()
                val minSize = 0.05f
                if (dragAll) {
                    val mx = dx.coerceIn(-crop.left, 1f - crop.right)
                    val my = dy.coerceIn(-crop.top, 1f - crop.bottom)
                    crop.offset(mx, my)
                } else {
                    if (dragLeft) crop.left = (crop.left + dx).coerceIn(0f, crop.right - minSize)
                    if (dragRight) crop.right = (crop.right + dx).coerceIn(crop.left + minSize, 1f)
                    if (dragTop) crop.top = (crop.top + dy).coerceIn(0f, crop.bottom - minSize)
                    if (dragBottom) crop.bottom = (crop.bottom + dy).coerceIn(crop.top + minSize, 1f)
                }
                invalidate()
            }
        }
        lastX = e.x
        lastY = e.y
        return true
    }
}
