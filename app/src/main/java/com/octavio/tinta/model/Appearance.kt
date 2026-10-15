package com.octavio.tinta.model

import androidx.core.graphics.ColorUtils

/**
 * Colores en pantalla según el modo. Los trazos guardan siempre su color de modo claro (el del PDF);
 * en modo oscuro se invierte la luminosidad conservando el tono, así el negro se ve blanco y el
 * azul sigue siendo azul. Los resaltadores mantienen el color con un poco más de opacidad.
 */
object Appearance {
    fun ink(color: Int, tool: Tool, dark: Boolean): Int {
        if (!dark) return color
        if (tool == Tool.HIGHLIGHTER) return (color and 0x00FFFFFF) or DARK_HIGHLIGHT_ALPHA
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        // Nada queda tan oscuro como el papel: el blanco pasa a gris medio, no a negro.
        hsl[2] = 0.35f + 0.65f * (1f - hsl[2])
        return (ColorUtils.HSLToColor(hsl) and 0x00FFFFFF) or (color and 0xFF000000.toInt())
    }

    /**
     * Lo inverso de [ink]: el color a guardar para que en pantalla se vea `shown`. En modo oscuro
     * los colores más oscuros que el gris medio no se pueden lograr y quedan en blanco.
     */
    fun stored(shown: Int, tool: Tool, dark: Boolean): Int {
        if (!dark || tool == Tool.HIGHLIGHTER) return shown or 0xFF000000.toInt()
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(shown, hsl)
        hsl[2] = (1f - (hsl[2] - 0.35f) / 0.65f).coerceIn(0f, 1f)
        return ColorUtils.HSLToColor(hsl) or 0xFF000000.toInt()
    }

    private const val DARK_HIGHLIGHT_ALPHA = 0x99000000.toInt()
}
