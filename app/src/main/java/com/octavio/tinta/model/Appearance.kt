package com.octavio.tinta.model

/**
 * Colores en pantalla según el modo. Los trazos guardan siempre su color de modo claro (el del PDF).
 * En modo oscuro, un color que ya se lee sobre el papel oscuro (los claros) queda igual; los que
 * no (los oscuros) se invierten conservando el tono, así el negro se ve blanco y el azul marino,
 * celeste. Si aun así no se leyera, se aclara hasta que se lea. Los resaltadores mantienen el color
 * con un poco más de opacidad.
 */
object Appearance {
    /** El papel en modo oscuro (el mismo que `Paper.DARK`). */
    private const val DARK_PAPER = 0xFF1E1F22.toInt()

    /** Contraste mínimo de la tinta con el papel oscuro. */
    private const val READABLE = 4.5

    fun ink(color: Int, tool: Tool, dark: Boolean): Int {
        if (!dark) return color
        if (tool == Tool.HIGHLIGHTER) return (color and 0x00FFFFFF) or DARK_HIGHLIGHT_ALPHA
        val alpha = (color ushr 24) and 0xFF
        val opaque = color or 0xFF000000.toInt()
        if (readable(opaque)) return color
        val hsl = Colors.toHsl(opaque)
        hsl[2] = 1f - hsl[2]
        var shown = Colors.fromHsl(hsl)
        while (!readable(shown) && hsl[2] < 1f) {
            hsl[2] = (hsl[2] + 0.03f).coerceAtMost(1f)
            shown = Colors.fromHsl(hsl)
        }
        return (shown and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun readable(c: Int) = Colors.contrast(c, DARK_PAPER) >= READABLE

    /**
     * Lo inverso de [ink]: el color a guardar para que en pantalla, en modo oscuro, se vea `shown`.
     * Si el invertido de `shown` es oscuro (se lee en la hoja clara), se guarda ese: así lo elegido
     * claro en modo oscuro sale oscuro en el PDF. Si no, se guarda tal cual.
     */
    fun stored(shown: Int, tool: Tool, dark: Boolean): Int {
        if (!dark || tool == Tool.HIGHLIGHTER) return shown or 0xFF000000.toInt()
        val hsl = Colors.toHsl(shown or 0xFF000000.toInt())
        hsl[2] = 1f - hsl[2]
        val mirrored = Colors.fromHsl(hsl)
        return if (!readable(mirrored)) mirrored else shown or 0xFF000000.toInt()
    }

    private const val DARK_HIGHLIGHT_ALPHA = 0x99000000.toInt()
}
