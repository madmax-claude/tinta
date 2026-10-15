package com.octavio.tinta.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Lo que no es tinta ni imagen: gráficos de funciones, figuras 3D, notas de voz y videos. Cada uno
 * ocupa un rectángulo del mundo y se guarda como JSON, así agregar datos no cambia el formato.
 * No giran: al girar lo elegido con el lazo sólo se mueve su centro.
 */
sealed class Widget {
    abstract val left: Float
    abstract val top: Float
    abstract val width: Float
    abstract val height: Float

    val right get() = left + width
    val bottom get() = top + height
    val centerX get() = left + width / 2
    val centerY get() = top + height / 2

    /** El mismo, en otro rectángulo. */
    abstract fun placed(left: Float, top: Float, width: Float, height: Float): Widget

    fun transformed(t: Transform): Widget {
        val r = Transform.upright(t, left, top, width, height)
        return placed(r[0], r[1], r[2], r[3])
    }

    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    /** Archivos de la carpeta de medios que usa. */
    open val files: List<File> get() = emptyList()

    /** El mismo con sus archivos cambiados por los de `map` (al pegarlo en otra nota). */
    open fun withFiles(map: (File) -> File): Widget = this

    abstract fun toJson(): JSONObject

    protected fun rect(type: String) = JSONObject()
        .put("type", type)
        .put("left", left.toDouble())
        .put("top", top.toDouble())
        .put("width", width.toDouble())
        .put("height", height.toDouble())

    companion object {
        /** null si es de un tipo desconocido (de una versión más nueva) o está dañado. */
        fun fromJson(o: JSONObject, mediaDir: File): Widget? = runCatching {
            val l = o.getDouble("left").toFloat()
            val t = o.getDouble("top").toFloat()
            val w = o.getDouble("width").toFloat()
            val h = o.getDouble("height").toFloat()
            fun f(key: String, default: Float) = o.optDouble(key, default.toDouble()).toFloat()
            when (o.getString("type")) {
                "graph2d" -> Graph2D(
                    l, t, w, h,
                    o.getJSONArray("functions").let { a -> (0 until a.length()).map { a.getString(it) } },
                    f("xMin", -5f), f("xMax", 5f), f("yMin", -5f), f("yMax", 5f),
                    o.optBoolean("autoY", true), o.optBoolean("grid", true), o.optInt("color", Tool.INK_COLORS[1]),
                )
                "graph3d" -> Graph3D(
                    l, t, w, h, o.getString("function"),
                    f("xMin", -3f), f("xMax", 3f), f("yMin", -3f), f("yMax", 3f),
                    f("yaw", 35f), f("pitch", 25f), o.optInt("color", Tool.INK_COLORS[1]),
                )
                "solid" -> Solid(
                    l, t, w, h, SolidKind.fromId(o.getString("kind")),
                    f("a", 1f), f("b", 1f), f("c", 1f), f("yaw", 30f), f("pitch", 20f),
                    o.optInt("color", Tool.INK_COLORS[0]), o.optBoolean("hidden", true), o.optBoolean("shaded", true),
                )
                "media" -> Media(
                    l, t, w, h,
                    if (o.getString("kind") == "video") MediaKind.VIDEO else MediaKind.AUDIO,
                    File(mediaDir, o.getString("file")),
                    o.optString("thumb", "").takeIf { it.isNotEmpty() }?.let { File(mediaDir, it) },
                    o.optLong("duration"),
                )
                else -> null
            }
        }.getOrNull()
    }
}

/** Gráfico de funciones y = f(x); con `autoY` el rango de y sale de los valores. */
data class Graph2D(
    override val left: Float,
    override val top: Float,
    override val width: Float,
    override val height: Float,
    val functions: List<String>,
    val xMin: Float,
    val xMax: Float,
    val yMin: Float,
    val yMax: Float,
    val autoY: Boolean,
    val grid: Boolean,
    val color: Int,
) : Widget() {
    override fun placed(left: Float, top: Float, width: Float, height: Float) = copy(left = left, top = top, width = width, height = height)

    override fun toJson(): JSONObject = rect("graph2d")
        .put("functions", JSONArray(functions))
        .put("xMin", xMin.toDouble()).put("xMax", xMax.toDouble())
        .put("yMin", yMin.toDouble()).put("yMax", yMax.toDouble())
        .put("autoY", autoY).put("grid", grid).put("color", color)
}

/** Superficie z = f(x, y), vista desde (yaw, pitch) en grados. */
data class Graph3D(
    override val left: Float,
    override val top: Float,
    override val width: Float,
    override val height: Float,
    val function: String,
    val xMin: Float,
    val xMax: Float,
    val yMin: Float,
    val yMax: Float,
    val yaw: Float,
    val pitch: Float,
    val color: Int,
) : Widget() {
    override fun placed(left: Float, top: Float, width: Float, height: Float) = copy(left = left, top = top, width = width, height = height)

    override fun toJson(): JSONObject = rect("graph3d")
        .put("function", function)
        .put("xMin", xMin.toDouble()).put("xMax", xMax.toDouble())
        .put("yMin", yMin.toDouble()).put("yMax", yMax.toDouble())
        .put("yaw", yaw.toDouble()).put("pitch", pitch.toDouble()).put("color", color)
}

/** Cuerpos de geometría. Las medidas a, b, c significan lo que dice cada uno (ver [labels]). */
enum class SolidKind(val id: String, val label: String, val labels: List<String>) {
    CUBE("cubo", "Cubo", listOf("Lado")),
    BOX("prisma", "Prisma rectangular", listOf("Ancho", "Alto", "Profundidad")),
    TRIANGULAR("prisma3", "Prisma triangular", listOf("Lado de la base", "Alto")),
    HEXAGONAL("prisma6", "Prisma hexagonal", listOf("Lado de la base", "Alto")),
    PYRAMID("piramide", "Pirámide", listOf("Lado de la base", "Alto")),
    TETRAHEDRON("tetraedro", "Tetraedro", listOf("Lado")),
    CYLINDER("cilindro", "Cilindro", listOf("Radio", "Alto")),
    CONE("cono", "Cono", listOf("Radio", "Alto")),
    SPHERE("esfera", "Esfera", listOf("Radio"));

    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: CUBE
    }
}

/** Figura 3D, vista desde (yaw, pitch) en grados; con `hiddenEdges` las aristas de atrás van punteadas. */
data class Solid(
    override val left: Float,
    override val top: Float,
    override val width: Float,
    override val height: Float,
    val kind: SolidKind,
    val a: Float,
    val b: Float,
    val c: Float,
    val yaw: Float,
    val pitch: Float,
    val color: Int,
    val hiddenEdges: Boolean,
    val shaded: Boolean,
) : Widget() {
    override fun placed(left: Float, top: Float, width: Float, height: Float) = copy(left = left, top = top, width = width, height = height)

    override fun toJson(): JSONObject = rect("solid")
        .put("kind", kind.id)
        .put("a", a.toDouble()).put("b", b.toDouble()).put("c", c.toDouble())
        .put("yaw", yaw.toDouble()).put("pitch", pitch.toDouble())
        .put("color", color).put("hidden", hiddenEdges).put("shaded", shaded)
}

enum class MediaKind { AUDIO, VIDEO }

/** Nota de voz o video; se reproduce al tocarlo. `thumb`: un cuadro del video. */
data class Media(
    override val left: Float,
    override val top: Float,
    override val width: Float,
    override val height: Float,
    val kind: MediaKind,
    val file: File,
    val thumb: File?,
    val durationMs: Long,
) : Widget() {
    override fun placed(left: Float, top: Float, width: Float, height: Float) = copy(left = left, top = top, width = width, height = height)

    override val files get() = listOfNotNull(file, thumb)

    override fun withFiles(map: (File) -> File) = copy(file = map(file), thumb = thumb?.let(map))

    override fun toJson(): JSONObject = rect("media")
        .put("kind", if (kind == MediaKind.VIDEO) "video" else "audio")
        .put("file", file.name)
        .put("thumb", thumb?.name ?: "")
        .put("duration", durationMs)
}
