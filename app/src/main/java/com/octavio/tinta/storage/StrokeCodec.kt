package com.octavio.tinta.storage

import androidx.ink.brush.ExperimentalInkCustomBrushApi
import com.octavio.tinta.model.Transform
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.StrokeInputBatch
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Puntos de un trazo en crudo. `StrokeInputBatch.encode` de ink-storage comprime cada trazo por
 * separado y decodificar 22.000 trazos llevaba 24 s; acá se guardan sin comprimir y el archivo
 * entero va comprimido una sola vez.
 *
 *     byte tipo de herramienta, byte banderas (1 presión, 2 inclinación, 4 orientación),
 *     float cm por unidad, int semilla de ruido, int n,
 *     n × (float x, float y, int ms, [float presión], [float inclinación], [float orientación])
 */
@OptIn(ExperimentalInkCustomBrushApi::class) // semilla de ruido
object StrokeCodec {
    private const val PRESSURE = 1
    private const val TILT = 2
    private const val ORIENTATION = 4

    // Perezoso: transform() y points() no tocan Ink y se prueban en la JVM.
    private val TOOL_TYPES by lazy { listOf(InputToolType.UNKNOWN, InputToolType.MOUSE, InputToolType.TOUCH, InputToolType.STYLUS) }

    fun encode(batch: StrokeInputBatch): ByteArray {
        val n = batch.size
        val flags = (if (batch.hasPressure()) PRESSURE else 0) or
            (if (batch.hasTilt()) TILT else 0) or
            (if (batch.hasOrientation()) ORIENTATION else 0)
        val perPoint = 12 + 4 * Integer.bitCount(flags)
        val bytes = ByteArrayOutputStream(14 + n * perPoint)
        DataOutputStream(bytes).use { out ->
            out.writeByte(TOOL_TYPES.indexOf(batch.getToolType()).coerceAtLeast(0))
            out.writeByte(flags)
            out.writeFloat(if (batch.hasStrokeUnitLength()) batch.getStrokeUnitLengthCm() else StrokeInput.NO_STROKE_UNIT_LENGTH)
            out.writeInt(batch.getNoiseSeed())
            out.writeInt(n)
            val p = StrokeInput()
            for (i in 0 until n) {
                batch.populate(i, p)
                out.writeFloat(p.x)
                out.writeFloat(p.y)
                out.writeInt(p.elapsedTimeMillis.toInt())
                if (flags and PRESSURE != 0) out.writeFloat(p.pressure)
                if (flags and TILT != 0) out.writeFloat(p.tiltRadians)
                if (flags and ORIENTATION != 0) out.writeFloat(p.orientationRadians)
            }
        }
        return bytes.toByteArray()
    }

    /** Puntos de lápiz sin tiempos reales (importados): uno cada `stepMs`. */
    fun encodePoints(n: Int, xs: FloatArray, ys: FloatArray, pressures: FloatArray?, stepMs: Int): ByteArray {
        val flags = if (pressures != null) PRESSURE else 0
        val bytes = ByteArrayOutputStream(14 + n * (12 + 4 * Integer.bitCount(flags)))
        DataOutputStream(bytes).use { out ->
            out.writeByte(TOOL_TYPES.indexOf(InputToolType.STYLUS))
            out.writeByte(flags)
            out.writeFloat(StrokeInput.NO_STROKE_UNIT_LENGTH)
            out.writeInt(0)
            out.writeInt(n)
            for (i in 0 until n) {
                out.writeFloat(xs[i])
                out.writeFloat(ys[i])
                out.writeInt(i * stepMs)
                if (pressures != null) out.writeFloat(pressures[i])
            }
        }
        return bytes.toByteArray()
    }

    /**
     * Los mismos puntos con p' = a·R(θ)·p + d (ver [Transform]). La orientación del lápiz, si la
     * tiene, gira con ellos. Se trabaja sobre los bytes, sin objetos de Ink.
     */
    fun transform(bytes: ByteArray, t: Transform): ByteArray {
        val input = DataInputStream(bytes.inputStream())
        val out = ByteArrayOutputStream(bytes.size)
        DataOutputStream(out).use { o ->
            o.writeByte(input.readUnsignedByte())
            val flags = input.readUnsignedByte()
            o.writeByte(flags)
            o.writeFloat(input.readFloat())
            o.writeInt(input.readInt())
            val n = input.readInt()
            o.writeInt(n)
            val twoPi = (2 * Math.PI).toFloat()
            repeat(n) {
                val x = input.readFloat()
                val y = input.readFloat()
                o.writeFloat(t.x(x, y))
                o.writeFloat(t.y(x, y))
                o.writeInt(input.readInt())
                if (flags and PRESSURE != 0) o.writeFloat(input.readFloat())
                if (flags and TILT != 0) o.writeFloat(input.readFloat())
                if (flags and ORIENTATION != 0) {
                    val r = input.readFloat()
                    o.writeFloat(if (t.angle == 0f) r else ((r + t.angle) % twoPi + twoPi) % twoPi)
                }
            }
        }
        return out.toByteArray()
    }

    /** Sólo las coordenadas, en pares (x, y), para elegir con el lazo sin armar la malla. */
    fun points(bytes: ByteArray): FloatArray {
        val input = DataInputStream(bytes.inputStream())
        input.readUnsignedByte()
        val flags = input.readUnsignedByte()
        input.readFloat()
        input.readInt()
        val n = input.readInt()
        val extra = Integer.bitCount(flags and (PRESSURE or TILT or ORIENTATION))
        val xy = FloatArray(2 * n)
        for (i in 0 until n) {
            xy[2 * i] = input.readFloat()
            xy[2 * i + 1] = input.readFloat()
            input.skipBytes(4 + 4 * extra)
        }
        return xy
    }

    fun decode(bytes: ByteArray): MutableStrokeInputBatch {
        val input = DataInputStream(bytes.inputStream())
        val type = TOOL_TYPES.getOrElse(input.readUnsignedByte()) { InputToolType.UNKNOWN }
        val flags = input.readUnsignedByte()
        val unitLength = input.readFloat()
        val seed = input.readInt()
        val n = input.readInt()
        val batch = MutableStrokeInputBatch()
        repeat(n) {
            val x = input.readFloat()
            val y = input.readFloat()
            val t = input.readInt().toLong()
            val pressure = if (flags and PRESSURE != 0) input.readFloat() else StrokeInput.NO_PRESSURE
            val tilt = if (flags and TILT != 0) input.readFloat() else StrokeInput.NO_TILT
            val orientation = if (flags and ORIENTATION != 0) input.readFloat() else StrokeInput.NO_ORIENTATION
            batch.add(type, x, y, t, unitLength, pressure, tilt, orientation)
        }
        batch.setNoiseSeed(seed)
        return batch
    }
}
