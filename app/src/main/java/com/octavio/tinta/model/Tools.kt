package com.octavio.tinta.model

import android.annotation.SuppressLint
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes

/**
 * Pluma con más respuesta a la presión que `StockBrushes.pressurePen()`, que sólo engrosa por
 * encima del 80 %. La sensibilidad va de 0 (el ancho no cambia con la presión) a 10; con la de
 * siempre, 5, el ancho va de 0,4 a 1,3 veces el tamaño, parecido a Jnotes. El constructor de
 * BrushFamily es API restringida de Ink; se fija la versión 1.0.0 en el build.
 */
private val sensitivePens = arrayOfNulls<BrushFamily>(Sensitivity.MAX + 1)

private fun sensitivePen(level: Int): BrushFamily {
    val k = level.coerceIn(0, Sensitivity.MAX)
    return sensitivePens[k] ?: synchronized(sensitivePens) {
        sensitivePens[k] ?: createSensitivePen(k).also { sensitivePens[k] = it }
    }
}

/** La sensibilidad a la presión de la pluma: cuánto cambia el ancho entre apoyar suave y fuerte. */
object Sensitivity {
    const val MAX = 10
    const val DEFAULT = 5

    /** Ancho (como fracción del tamaño) con presión mínima y máxima. */
    fun range(level: Int): Pair<Float, Float> {
        val k = level.coerceIn(0, MAX)
        return (1f - 0.12f * k).coerceAtLeast(0.08f) to (1f + 0.06f * k)
    }
}

@SuppressLint("RestrictedApi")
@OptIn(ExperimentalInkCustomBrushApi::class)
private fun createSensitivePen(level: Int): BrushFamily {
    val (low, high) = Sensitivity.range(level)
    val behaviors = mutableListOf(
        StockBrushes.predictionFadeOutBehavior,
        // Afina el final del trazo, como la pluma de Ink.
        BrushBehavior(
            BrushBehavior.Source.DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE,
            BrushBehavior.Target.SIZE_MULTIPLIER,
            sourceValueRangeStart = 3f,
            sourceValueRangeEnd = 0f,
            targetModifierRangeStart = 1f,
            targetModifierRangeEnd = 0.75f,
        ),
    )
    // Sin sensibilidad no hay comportamiento de presión: Ink no acepta un rango de 1 a 1.
    if (high > low) {
        behaviors.add(
            BrushBehavior(
                BrushBehavior.Source.NORMALIZED_PRESSURE,
                BrushBehavior.Target.SIZE_MULTIPLIER,
                sourceValueRangeStart = 0f,
                sourceValueRangeEnd = 1f,
                targetModifierRangeStart = low,
                targetModifierRangeEnd = high,
                responseTimeMillis = 20L,
                enabledToolTypes = setOf(InputToolType.STYLUS),
            ),
        )
    }
    return BrushFamily(
        tip = BrushTip(behaviors = behaviors),
        // La de siempre conserva su nombre: los trazos viejos son de esa.
        clientBrushFamilyId = if (level == Sensitivity.DEFAULT) "tinta-pluma-v1" else "tinta-pluma-v1-s$level",
        inputModel = BrushFamily.SlidingWindowModel(),
    )
}

/** Herramientas del lápiz. `id` es el valor que se guarda en el archivo; no cambiarlo. */
enum class Tool(val id: Int, val label: String, val minSize: Float, val maxSize: Float) {
    PEN(0, "Pluma", 0.5f, 8f),
    MARKER(1, "Marcador", 1f, 14f),
    HIGHLIGHTER(2, "Resaltador", 6f, 48f);

    /** `sensitivity` sólo cuenta para la pluma (ver [Sensitivity]). */
    fun family(sensitivity: Int = Sensitivity.DEFAULT): BrushFamily =
        when (this) {
            PEN -> sensitivePen(sensitivity)
            MARKER -> StockBrushes.marker()
            // DISCARD: un resaltador que pasa dos veces por el mismo lugar no se oscurece.
            HIGHLIGHTER -> StockBrushes.highlighter(SelfOverlap.DISCARD)
        }

    /** Colores que se ofrecen de entrada. */
    val colors: IntArray
        get() = if (this == HIGHLIGHTER) HIGHLIGHT_COLORS else INK_COLORS

    fun brush(color: Int, size: Float, epsilon: Float, sensitivity: Int = Sensitivity.DEFAULT): Brush =
        Brush.createWithColorIntArgb(family(sensitivity), storedColor(color), size, epsilon)

    /** El resaltador guarda su color ya transparente. */
    fun storedColor(color: Int) = if (this == HIGHLIGHTER) (color and 0x00FFFFFF) or HIGHLIGHT_ALPHA else color

    companion object {
        fun fromId(id: Int): Tool = entries.firstOrNull { it.id == id } ?: PEN

        private const val HIGHLIGHT_ALPHA = 0x66000000

        val INK_COLORS = intArrayOf(
            0xFF1F1F1F.toInt(), // negro
            0xFF1E5BD8.toInt(), // azul
            0xFFD93025.toInt(), // rojo
            0xFF188038.toInt(), // verde
            0xFFE37400.toInt(), // naranja
            0xFF8E44AD.toInt(), // violeta
            0xFF00838F.toInt(), // turquesa
            0xFF7A7A7A.toInt(), // gris
        )

        val HIGHLIGHT_COLORS = intArrayOf(
            0xFFFFE600.toInt(), // amarillo
            0xFF7CE07C.toInt(), // verde
            0xFFFF8AC4.toInt(), // rosa
            0xFF7FC4FF.toInt(), // celeste
            0xFFFFB25B.toInt(), // naranja
            0xFFC9A0FF.toInt(), // lila
            0xFF9FF5EC.toInt(), // aguamarina
            0xFFD0D0D0.toInt(), // gris
        )
    }
}
