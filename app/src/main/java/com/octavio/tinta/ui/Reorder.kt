package com.octavio.tinta.ui

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.octavio.tinta.dp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Cambiar de lugar los elementos de una fila (los lápices de la barra, las herramientas de la barra
 * lateral): mantener apretado uno lo levanta, arrastrándolo los otros se corren para hacerle lugar,
 * y al soltarlo queda ahí ([onMoved]). Soltarlo sin moverlo es [onHeld] (las opciones); un toque
 * corto, el clic de siempre.
 */
class Reorder(
    /** Los elementos en el orden en que se ven. */
    private val items: () -> List<View>,
    private val vertical: () -> Boolean,
    /** De un elemento al siguiente, en px (su tamaño más el margen). */
    private val slot: (View) -> Float,
    private val onMoved: (from: Int, to: Int) -> Unit,
    private val onHeld: ((Int, View) -> Unit)? = null,
) {
    @SuppressLint("ClickableViewAccessibility")
    fun attach(v: View) {
        val slop = ViewConfiguration.get(v.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var lifted = false
        var moved = false
        var from = -1
        var target = -1
        val lift = Runnable {
            from = items().indexOf(v)
            if (from < 0) return@Runnable
            lifted = true
            target = from
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            v.translationZ = v.context.dp(8f)
            v.animate().scaleX(LIFT_SCALE).scaleY(LIFT_SCALE).setDuration(120).start()
            v.parent?.requestDisallowInterceptTouchEvent(true)
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    lifted = false
                    moved = false
                    v.isPressed = true
                    v.postDelayed(lift, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!lifted) {
                        if (hypot(e.rawX - downX, e.rawY - downY) > slop) {
                            v.removeCallbacks(lift)
                            v.isPressed = false
                        }
                    } else {
                        val all = items()
                        val step = slot(v).coerceAtLeast(1f)
                        val d = if (vertical()) e.rawY - downY else e.rawX - downX
                        // Sigue al dedo a lo largo de la fila, sin salirse de los elementos.
                        val off = d.coerceIn(-from * step, (all.lastIndex - from) * step)
                        if (vertical()) v.translationY = off else v.translationX = off
                        if (abs(d) > slop) moved = true
                        val now = (from + (off / step).roundToInt()).coerceIn(0, all.lastIndex)
                        if (now != target) {
                            target = now
                            makeRoom(all, from, target, step)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(lift)
                    v.isPressed = false
                    if (!lifted) {
                        if (hypot(e.rawX - downX, e.rawY - downY) <= slop) v.performClick()
                    } else {
                        settle()
                        when {
                            target != from -> onMoved(from, target)
                            !moved -> onHeld?.invoke(from, v)
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(lift)
                    v.isPressed = false
                    if (lifted) settle()
                }
            }
            true
        }
    }

    /** Corre los otros para dejar libre el lugar `to` al que se arrastra desde `from`. */
    private fun makeRoom(all: List<View>, from: Int, to: Int, step: Float) {
        for ((i, other) in all.withIndex()) {
            if (i == from) continue
            val shift = when {
                from < to && i in from + 1..to -> -step
                from > to && i in to until from -> step
                else -> 0f
            }
            if (vertical()) other.animate().translationY(shift).setDuration(120).start()
            else other.animate().translationX(shift).setDuration(120).start()
        }
    }

    /** Todo a su lugar: el orden nuevo lo pone quien recibe [onMoved]. */
    private fun settle() {
        for (other in items()) {
            other.animate().cancel()
            other.translationX = 0f
            other.translationY = 0f
            other.translationZ = 0f
            other.scaleX = 1f
            other.scaleY = 1f
        }
    }

    private companion object {
        const val LIFT_SCALE = 1.18f
    }
}
