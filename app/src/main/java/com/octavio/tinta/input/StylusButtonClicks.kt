package com.octavio.tinta.input

/**
 * Un clic del botón lateral alterna una sola vez.
 *
 * El mismo toque puede llegar por el evento del lápiz, por una tecla y por el control
 * Bluetooth del Pen Plus: si caen dentro de [GAP_MS], son un solo clic. Soltar no cuenta,
 * aunque se haya sostenido el botón, y el scroll del control tampoco.
 */
class StylusButtonClicks(private val gapMs: Long = GAP_MS) {
    private var lastAt = Long.MIN_VALUE

    /** Flanco de los botones que vienen en el evento del lápiz. Sólo al pasar a apretado. */
    fun onButtonEdge(wasPressed: Boolean, pressed: Boolean, timeMs: Long): Boolean {
        if (!pressed || wasPressed) return false
        return press(timeMs)
    }

    /** Tecla del lápiz o del control Bluetooth. Sólo al bajar; la repetición y el soltar no. */
    fun onKey(down: Boolean, timeMs: Long): Boolean = if (down) press(timeMs) else false

    /** Qué hace el control Bluetooth con esa acción de [android.view.MotionEvent]. */
    fun onDevice(actionMasked: Int, timeMs: Long): Boolean =
        if (deviceKind(actionMasked) == Kind.PRESS) press(timeMs) else false

    private fun press(timeMs: Long): Boolean {
        if (lastAt != Long.MIN_VALUE && timeMs >= lastAt && timeMs - lastAt < gapMs) return false
        lastAt = timeMs
        return true
    }

    enum class Kind { PRESS, RELEASE, SCROLL, OTHER }

    companion object {
        const val GAP_MS = 350L

        // Mismos números que MotionEvent. Así se puede probar sin el aparato.
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_SCROLL = 8
        const val ACTION_BUTTON_PRESS = 11
        const val ACTION_BUTTON_RELEASE = 12

        fun deviceKind(actionMasked: Int): Kind = when (actionMasked) {
            ACTION_DOWN, ACTION_BUTTON_PRESS -> Kind.PRESS
            ACTION_UP, ACTION_BUTTON_RELEASE -> Kind.RELEASE
            ACTION_SCROLL -> Kind.SCROLL
            else -> Kind.OTHER
        }
    }
}
