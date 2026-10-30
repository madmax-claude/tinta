package com.octavio.tinta.ui

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Letras de la interfaz (herramientas, hojas, menú). No cambia lo escrito en la nota. */
object Fonts {
    enum class Family(val id: String, val label: String, val family: String) {
        SANS("sans", "Sans", "sans-serif"),
        MEDIUM("medium", "Mediana", "sans-serif-medium"),
        CONDENSED("condensed", "Condensada", "sans-serif-condensed"),
        SERIF("serif", "Serif", "serif"),
        MONO("mono", "Monoespacio", "monospace"),
        CURSIVE("cursive", "Cursiva", "cursive"),
        CASUAL("casual", "Informal", "casual"),
    }

    fun fromId(id: String?) = Family.entries.firstOrNull { it.id == id } ?: Family.SANS

    fun typeface(id: String?): Typeface = Typeface.create(fromId(id).family, Typeface.NORMAL)

    fun apply(view: View, id: String?) {
        val face = typeface(id)
        walk(view, face)
    }

    private fun walk(view: View, face: Typeface) {
        when (view) {
            // El lápiz se dibuja solo. El recuadro de texto elige su letra: no es la de la interfaz.
            is PenButton, is TextBoxEditor -> Unit
            is TextView -> {
                view.typeface = face
                if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), face)
            }
            is ViewGroup -> for (i in 0 until view.childCount) walk(view.getChildAt(i), face)
        }
    }
}
