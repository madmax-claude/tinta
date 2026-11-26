package com.octavio.tinta.ui

/** Cómo entra la barra lateral en el alto que queda. */
enum class DockFit {
    /** Una columna, y entra entera. */
    ONE,

    /** Dos columnas, y entran enteras. */
    TWO,

    /** Una columna con scroll. */
    SCROLL,

    /** No entra ni el encabezado y una herramienta. */
    COLLAPSE,
}

/**
 * Elige cómo mostrar la barra lateral.
 *
 * @param available alto útil, en px
 * @param oneCol alto de una columna
 * @param twoCol alto de dos columnas
 * @param twoWidth ancho de las dos columnas
 * @param parentWidth ancho de la ventana
 * @param minUseful alto mínimo para el encabezado y una herramienta
 */
fun dockFit(
    available: Int,
    oneCol: Int,
    twoCol: Int,
    twoWidth: Int,
    parentWidth: Int,
    minUseful: Int,
): DockFit {
    if (available < minUseful) return DockFit.COLLAPSE
    if (oneCol <= available) return DockFit.ONE
    if (twoCol <= available && parentWidth > 0 && twoWidth <= parentWidth * 0.45f) return DockFit.TWO
    return DockFit.SCROLL
}

/**
 * Cuántos botones opcionales de la barra de arriba no entran y pasan al menú.
 * Si hace falta el menú, también se reserva el lugar de su botón. 0 si entran todos.
 */
fun topBarOverflowCount(width: Int, back: Int, slot: Int, minTabs: Int, optional: Int): Int {
    if (width <= 0 || optional <= 0 || slot <= 0) return 0
    val room = width - back - minTabs
    if (optional * slot <= room) return 0
    val withMenu = room - slot
    if (withMenu <= 0) return optional
    val fit = (withMenu / slot).coerceIn(0, optional)
    return optional - fit
}
