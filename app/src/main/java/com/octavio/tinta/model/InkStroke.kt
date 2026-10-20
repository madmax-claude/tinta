package com.octavio.tinta.model

import androidx.ink.brush.Brush
import androidx.ink.strokes.Stroke
import com.octavio.tinta.storage.StrokeCodec

/**
 * Un trazo terminado. Guarda lo necesario para dibujarlo y guardarlo; la malla de Ink ([stroke]) se
 * arma recién cuando hace falta, porque en una nota de 20.000 trazos armarlas todas al abrir tarda
 * varios segundos. La caja (left, top, right, bottom) está en coordenadas del mundo y alcanza para
 * saber si el trazo está a la vista sin tener la malla.
 */
class InkStroke private constructor(
    val tool: Tool,
    val color: Int,
    val size: Float,
    val epsilon: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    encoded: ByteArray?,
    built: Stroke?,
    /** Sensibilidad a la presión con que se hizo (sólo cuenta en la pluma; ver [Sensitivity]). */
    val sensitivity: Int = Sensitivity.DEFAULT,
) {
    /** Recién dibujado: ya tiene malla. */
    constructor(tool: Tool, stroke: Stroke, sensitivity: Int = Sensitivity.DEFAULT) : this(
        tool, stroke.brush.colorIntArgb, stroke.brush.size, stroke.brush.epsilon,
        stroke.shape.computeBoundingBox(), null, stroke, sensitivity,
    )

    private constructor(
        tool: Tool,
        color: Int,
        size: Float,
        epsilon: Float,
        box: androidx.ink.geometry.Box?,
        encoded: ByteArray?,
        built: Stroke?,
        sensitivity: Int,
    ) : this(
        tool, color, size, epsilon,
        box?.xMin ?: Float.NaN, box?.yMin ?: Float.NaN, box?.xMax ?: Float.NaN, box?.yMax ?: Float.NaN,
        encoded, built, sensitivity,
    )

    /**
     * Las rayas de una misma línea a rayas o de puntos comparten este número (0: suelto). El
     * borrador de trazo entero las borra todas juntas.
     */
    var group: Long = 0L

    @Volatile private var cached = encoded
    @Volatile private var mesh = built
    @Volatile private var darkMesh: Stroke? = null
    @Volatile private var failed = false

    /** La malla, o null si todavía no se armó (o no se pudo armar). */
    val stroke: Stroke? get() = mesh

    /** Lo que se dibuja en pantalla: en modo oscuro, la misma malla con el color de [Appearance]. */
    fun display(dark: Boolean): Stroke? {
        val s = mesh ?: return null
        if (!dark) return s
        darkMesh?.let { return it }
        // copy() reutiliza la malla si sólo cambia el color.
        return s.copy(s.brush.copyWithColorIntArgb(Appearance.ink(color, tool, true))).also { darkMesh = it }
    }

    val isBuilt get() = mesh != null || failed

    fun intersects(l: Float, t: Float, r: Float, b: Float) = !(right < l || left > r || bottom < t || top > b)

    /** Arma la malla si hace falta. Se puede llamar desde cualquier hilo. */
    fun build(): Stroke? {
        mesh?.let { return it }
        if (failed) return null
        return runCatching {
            Stroke(Brush.createWithColorIntArgb(tool.family(sensitivity), color, size, epsilon), StrokeCodec.decode(encoded()))
        }.onFailure { failed = true }.getOrNull()?.also { mesh = it }
    }

    /**
     * El mismo trazo con p' = a·R(θ)·p + d. Sale sin malla; el grosor se escala y la caja es la
     * de este trazo transformada (con giro, la que contiene a la caja girada: un poco más grande).
     */
    fun transformed(t: Transform): InkStroke {
        val b = t.box(left, top, right, bottom)
        return InkStroke(tool, color, size * t.a, epsilon, b[0], b[1], b[2], b[3], StrokeCodec.transform(encoded(), t), null, sensitivity)
            .also { it.group = group }
    }

    /**
     * Como [transformed], pero con la malla ya armada y la caja justa de la malla. Al girar, la caja
     * girada de la caja vieja es más grande que el trazo; girar varias veces la hacía crecer sin
     * parar (y con ella el cuadro de lo elegido). Se puede llamar desde cualquier hilo.
     */
    fun transformedBuilt(t: Transform): InkStroke {
        val bytes = StrokeCodec.transform(encoded(), t)
        val built = runCatching {
            Stroke(Brush.createWithColorIntArgb(tool.family(sensitivity), color, size * t.a, epsilon), StrokeCodec.decode(bytes))
        }.getOrNull()
        if (built == null) {
            val b = t.box(left, top, right, bottom)
            return InkStroke(tool, color, size * t.a, epsilon, b[0], b[1], b[2], b[3], bytes, null, sensitivity).also { it.group = group }
        }
        return InkStroke(tool, color, size * t.a, epsilon, built.shape.computeBoundingBox(), bytes, built, sensitivity).also { it.group = group }
    }

    /** El mismo trazo con otro color (ya como se guarda); si tiene malla, la reutiliza. */
    fun recolored(newColor: Int): InkStroke {
        val s = mesh ?: return InkStroke(tool, newColor, size, epsilon, left, top, right, bottom, encoded(), null, sensitivity).also { it.group = group }
        return InkStroke(tool, s.copy(s.brush.copyWithColorIntArgb(newColor)), sensitivity).also { it.group = group }
    }

    /** Las coordenadas de sus puntos, en pares (x, y). */
    fun points(): FloatArray = StrokeCodec.points(encoded())

    /** Puntos en el formato de [StrokeCodec]. Los trazos no cambian: se calcula una sola vez. */
    fun encoded(): ByteArray = cached ?: StrokeCodec.encode(checkNotNull(mesh).inputs).also { cached = it }

    companion object {
        /** Un número de línea nuevo para [group] (distinto de 0). */
        fun newGroup(): Long {
            var g = 0L
            while (g == 0L) g = java.util.concurrent.ThreadLocalRandom.current().nextLong()
            return g
        }

        /** Trazo leído de un archivo o importado: sin malla todavía. */
        fun unbuilt(
            tool: Tool,
            color: Int,
            size: Float,
            epsilon: Float,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            encoded: ByteArray,
            sensitivity: Int = Sensitivity.DEFAULT,
        ) = InkStroke(tool, color, size, epsilon, left, top, right, bottom, encoded, null, sensitivity)
    }
}
