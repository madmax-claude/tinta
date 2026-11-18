package com.octavio.tinta.input

/**
 * Qué dedos entran a mover la hoja.
 *
 * Un contacto ancho, o pegado a la punta con el lápiz cerca, es la palma y no entra.
 * Con el lápiz cerca, un dedo solo tampoco: se queda esperando. Cuando llega otro,
 * los dos entran, y así el pellizco, el paneo y el pase de hoja andan con la punta en el aire.
 */
class FingerAdmit {
    var waitingId: Int = NONE
        private set

    fun onDown(id: Int, palm: Boolean, penNear: Boolean, partnerDown: Boolean, fingersEmpty: Boolean): Down {
        if (palm) return Down(accept = false, promote = false)
        if (!fingersEmpty) {
            waitingId = NONE
            return Down(accept = true, promote = false)
        }
        // Primer dedo del gesto. Con el lápiz cerca no mueve la hoja hasta que haya otro.
        if (penNear && !(waitingId != NONE && partnerDown)) {
            waitingId = id
            return Down(accept = false, promote = false)
        }
        val promote = waitingId != NONE && partnerDown
        waitingId = NONE
        return Down(accept = true, promote = promote)
    }

    fun onUp(id: Int) {
        if (waitingId == id) waitingId = NONE
    }

    fun clear() {
        waitingId = NONE
    }

    data class Down(val accept: Boolean, val promote: Boolean)

    companion object {
        const val NONE = -1

        /** Más ancho que un dedo, en dp de pantalla. */
        const val PALM_TOUCH_DP = 64f

        /** Pegado a la punta, en dp de pantalla, con el lápiz cerca. */
        const val NEAR_TIP_DP = 80f

        fun isPalm(touchMajorPx: Float, density: Float, distToTipPx: Float?, penNear: Boolean): Boolean {
            if (touchMajorPx > 0f && density > 0f && touchMajorPx >= PALM_TOUCH_DP * density) return true
            if (!penNear || distToTipPx == null) return false
            return distToTipPx < NEAR_TIP_DP * density
        }
    }
}
