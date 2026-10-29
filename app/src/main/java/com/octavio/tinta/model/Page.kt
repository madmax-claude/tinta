package com.octavio.tinta.model

import kotlin.math.floor
import kotlin.math.max

/** Tamaño de hoja, en mm en vertical. `id` es el valor que se guarda en el archivo; no cambiarlo. */
enum class PageSize(val id: Int, val label: String, val widthMm: Float, val heightMm: Float) {
    INFINITE(0, "Infinita", 0f, 0f),
    A4(1, "A4", 210f, 297f),
    A5(2, "A5", 148f, 210f),
    A3(3, "A3", 297f, 420f),
    B5(4, "B5", 176f, 250f),
    LETTER(5, "Carta", 215.9f, 279.4f),
    LEGAL(6, "Oficio", 215.9f, 355.6f),

    /** Del tamaño de las páginas de un PDF importado (ver [PageLayout.customWidth]). */
    CUSTOM(7, "Del PDF", 0f, 0f);

    companion object {
        fun fromId(id: Int): PageSize = entries.firstOrNull { it.id == id } ?: INFINITE
    }
}

/**
 * Cómo se reparten las hojas en el mundo. Con [horizontal] en false van una debajo de la otra; en
 * true, una al lado de la otra, como un cuaderno. Separadas [GAP], con la primera en (left, top).
 * El mundo está en dp, que en la P12 es tamaño real (160 por pulgada), así que una A4 a zoom 1 mide
 * lo mismo que en papel. En una nota infinita no hay hojas.
 */
data class PageLayout(
    val size: PageSize = PageSize.INFINITE,
    val landscape: Boolean = false,
    val left: Float = 0f,
    val top: Float = 0f,
    /** Con [PageSize.CUSTOM], el tamaño en mm en vertical. */
    val customWidth: Float = 0f,
    val customHeight: Float = 0f,
    /** Hojas que se agregaron a mano: hay al menos estas aunque estén en blanco. */
    val minPages: Int = 0,
    /** Hojas de costado, como un cuaderno. Si no, una debajo de la otra. */
    val horizontal: Boolean = false,
) {
    val paged get() = size != PageSize.INFINITE
    private val baseWidth get() = if (size == PageSize.CUSTOM) customWidth else size.widthMm
    private val baseHeight get() = if (size == PageSize.CUSTOM) customHeight else size.heightMm
    val width get() = mm(if (landscape) baseHeight else baseWidth)
    val height get() = mm(if (landscape) baseWidth else baseHeight)
    val right get() = left + width

    /** De una hoja a la siguiente, sobre el eje en que se apilan. */
    val step get() = (if (horizontal) width else height) + GAP

    private val origin get() = if (horizontal) left else top

    /** Dónde empieza la hoja `k` sobre ese eje. */
    fun pageStart(k: Int) = origin + k * step

    fun pageLeft(k: Int) = if (horizontal) pageStart(k) else left

    fun pageTop(k: Int) = if (horizontal) top else pageStart(k)

    /**
     * La hoja en la coordenada `along` del eje (x si es un cuaderno, y si no). El hueco entre dos
     * cuenta para la de antes. Puede ser negativa.
     */
    fun pageAt(along: Float): Int = floor((along - origin) / step).toInt()

    /** La hoja que contiene el punto. */
    fun pageOf(x: Float, y: Float) = pageAt(if (horizontal) x else y)

    /** Borde lejano del contenido: el de la derecha en el cuaderno, el de abajo si no. */
    fun endOf(right: Float, bottom: Float) = if (horizontal) right else bottom

    /**
     * Cuántas hojas mostrar con lo escrito hasta `contentEnd` (NaN si no hay nada): hasta la
     * última usada, más una en blanco para seguir escribiendo, y al menos las agregadas a mano.
     * `contentEnd` es el borde lejano sobre el eje de las hojas.
     */
    fun count(contentEnd: Float): Int =
        max(minPages, if (contentEnd.isNaN()) 1 else max(1, pageAt(contentEnd) + 2))

    /** Hojas para exportar: hasta la última escrita (al menos una) y las agregadas a mano. */
    fun used(contentEnd: Float): Int =
        max(minPages, if (contentEnd.isNaN()) 1 else max(1, pageAt(contentEnd) + 1))

    companion object {
        const val GAP = 32f

        /** mm a dp del mundo. */
        fun mm(v: Float) = v * 160f / 25.4f

        /** Margen que se deja entre lo escrito y el borde al pasar una nota infinita a hojas. */
        const val MARGIN = 48f

        /**
         * Hojas para una nota que ya tiene algo escrito dentro de (l, t, r, b): la primera empieza un
         * margen antes. Sin nada escrito, en el origen.
         */
        fun around(size: PageSize, landscape: Boolean, l: Float, t: Float): PageLayout =
            if (l.isNaN() || t.isNaN()) PageLayout(size, landscape) else PageLayout(size, landscape, l - MARGIN, t - MARGIN)
    }
}
