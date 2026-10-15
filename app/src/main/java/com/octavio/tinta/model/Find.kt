package com.octavio.tinta.model

import java.text.Normalizer
import kotlin.math.min

/**
 * Comparar lo buscado con el texto reconocido de la letra a mano. Sin mayúsculas, sin tildes y
 * sin espacios (el reconocedor a veces junta o separa palabras), y con algún error permitido en
 * palabras largas: la letra reconocida casi nunca sale perfecta.
 */
object Find {
    private val MARKS = Regex("\\p{Mn}+")
    private val SPACES = Regex("\\s+")

    /** Minúsculas, sin tildes ni espacios. */
    fun normalize(s: String): String =
        MARKS.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "").replace(SPACES, "")

    /** Cuántos errores (letras de más, de menos o cambiadas) se aceptan para lo buscado. */
    fun allowed(query: String) = when {
        query.length >= 10 -> 2
        query.length >= 6 -> 1
        else -> 0
    }

    /** Si `text` contiene `query` (los dos ya pasados por [normalize]). */
    fun matches(text: String, query: String) = errors(text, query) >= 0

    /** Con cuántos errores aparece `query` en `text`: 0 si está tal cual, -1 si no aparece. */
    fun errors(text: String, query: String): Int {
        if (query.isEmpty()) return -1
        if (text.contains(query)) return 0
        val k = allowed(query)
        if (k == 0) return -1
        val d = distance(text, query)
        return if (d <= k) d else -1
    }

    /**
     * La menor cantidad de cambios para que `query` aparezca en algún lugar de `text` (algoritmo
     * de Sellers: como Levenshtein, pero empezar y terminar en cualquier lugar del texto es gratis).
     */
    fun distance(text: String, query: String): Int {
        val m = query.length
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        var best = prev[m]
        for (c in text) {
            cur[0] = 0
            for (j in 1..m) {
                val cost = if (query[j - 1] == c) 0 else 1
                cur[j] = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost)
            }
            best = min(best, cur[m])
            val t = prev
            prev = cur
            cur = t
        }
        return best
    }
}
