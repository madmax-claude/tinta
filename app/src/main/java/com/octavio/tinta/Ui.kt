package com.octavio.tinta

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density

fun Context.toast(text: CharSequence, long: Boolean = false) =
    Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

/**
 * Los mensajes de ayuda: las etiquetas de los botones (al pasar el lápiz por encima o al
 * mantenerlos apretados) y los avisos que explican algo o confirman que salió bien. Se apagan en
 * Configuración, en la pantalla principal. Los errores se muestran siempre.
 */
object Help {
    @Volatile var on = true
}

/** La etiqueta de un botón, si los mensajes de ayuda están prendidos. */
fun View.tip(text: CharSequence?) {
    tooltipText = if (Help.on) text else null
}

/** Un aviso de ayuda o de confirmación: con la ayuda apagada no se muestra (para errores, [toast]). */
fun Context.hint(text: CharSequence, long: Boolean = false) {
    if (Help.on) toast(text, long)
}

/**
 * Con edge-to-edge las barras del sistema quedan encima: se compensa con padding. Con `fullscreen`
 * en true no se deja lugar (si el sistema no llegó a esconder una barra, por ejemplo con la
 * pantalla dividida, se dibuja debajo de ella en vez de dejar una franja).
 */
fun View.padForSystemBars(fullscreen: () -> Boolean = { false }) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        if (fullscreen()) {
            v.setPadding(0, 0, 0, 0)
        } else {
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
        }
        WindowInsetsCompat.CONSUMED
    }
}

fun Activity.askText(title: CharSequence, initial: String, onOk: (String) -> Unit) {
    val input = EditText(this).apply {
        setText(initial)
        setSelectAllOnFocus(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        isSingleLine = true
    }
    val box = FrameLayout(this).apply {
        val pad = dp(20f).toInt()
        setPadding(pad, pad / 2, pad, 0)
        addView(input)
    }
    val dialog = AlertDialog.Builder(this)
        .setTitle(title)
        .setView(box)
        .setPositiveButton(R.string.ok) { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk) }
        .setNegativeButton(R.string.cancel, null)
        .show()
    // "Listo" en el teclado equivale a Aceptar.
    input.imeOptions = EditorInfo.IME_ACTION_DONE
    input.setOnEditorActionListener { _, _, _ ->
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        true
    }
    input.requestFocus()
}

fun Activity.confirm(message: CharSequence, action: CharSequence, onOk: () -> Unit) {
    AlertDialog.Builder(this)
        .setMessage(message)
        .setPositiveButton(action) { _, _ -> onOk() }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

/** Círculo de color o de tamaño en la barra; con anillo si está elegido. */
class DotView(context: Context, var color: Int, var radius: Float) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f)
        color = context.getColor(R.color.accent)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        fill.color = color
        canvas.drawCircle(cx, cy, radius, fill)
        if (isSelected) canvas.drawCircle(cx, cy, maxOf(radius, context.dp(9f)) + context.dp(4f), ring)
    }
}
