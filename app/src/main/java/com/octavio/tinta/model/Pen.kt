package com.octavio.tinta.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Un lápiz de la barra: herramienta, color fijo y grosor (dp del mundo). Con `straight`, cada trazo
 * es una línea recta del punto donde se apoya al punto donde se levanta. `sensitivity`: cuánto
 * responde a la presión (sólo la pluma; ver [Sensitivity]).
 */
data class Pen(
    val tool: Tool,
    val color: Int,
    val size: Float,
    val straight: Boolean = false,
    val sensitivity: Int = Sensitivity.DEFAULT,
) {

    fun toJson(): JSONObject = JSONObject()
        .put("tool", tool.id)
        .put("color", color)
        .put("size", size.toDouble())
        .put("straight", straight)
        .put("sensitivity", sensitivity)

    companion object {
        val DEFAULTS = listOf(
            Pen(Tool.PEN, Tool.INK_COLORS[0], 2f),
            Pen(Tool.PEN, Tool.INK_COLORS[1], 2f),
            Pen(Tool.PEN, Tool.INK_COLORS[2], 2f),
            Pen(Tool.HIGHLIGHTER, Tool.HIGHLIGHT_COLORS[0], 16f, straight = true),
            Pen(Tool.HIGHLIGHTER, Tool.HIGHLIGHT_COLORS[1], 16f),
        )

        fun fromJson(o: JSONObject): Pen {
            val tool = Tool.fromId(o.optInt("tool"))
            return Pen(
                tool,
                o.optInt("color", tool.colors[0]),
                o.optDouble("size", 2.0).toFloat().coerceIn(tool.minSize, tool.maxSize),
                o.optBoolean("straight"),
                o.optInt("sensitivity", Sensitivity.DEFAULT).coerceIn(0, Sensitivity.MAX),
            )
        }

        fun listToJson(pens: List<Pen>): String = JSONArray().apply { pens.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(text: String?): List<Pen> {
            if (text.isNullOrEmpty()) return DEFAULTS
            return runCatching {
                val a = JSONArray(text)
                (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: DEFAULTS
        }
    }
}
