package com.octavio.tinta

import com.octavio.tinta.model.Transform
import com.octavio.tinta.storage.StrokeCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class StrokeCodecTest {

    /** Dos puntos con presión e inclinación, como los escribe StrokeCodec.encode. */
    private fun sample(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { o ->
            o.writeByte(3)
            o.writeByte(1 or 2)
            o.writeFloat(-1f)
            o.writeInt(7)
            o.writeInt(2)
            for ((x, y, t) in listOf(Triple(10f, 20f, 0), Triple(30f, -5f, 8))) {
                o.writeFloat(x)
                o.writeFloat(y)
                o.writeInt(t)
                o.writeFloat(0.5f) // presión
                o.writeFloat(0.25f) // inclinación
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun pointsReadsOnlyCoordinates() {
        assertArrayEquals(floatArrayOf(10f, 20f, 30f, -5f), StrokeCodec.points(sample()), 0f)
    }

    @Test
    fun pointsWithEveryExtraStillLandOnTheCoordinates() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { o ->
            o.writeByte(3)
            o.writeByte(1 or 2 or 4)
            o.writeFloat(-1f)
            o.writeInt(1)
            o.writeInt(2)
            for ((x, y) in listOf(1f to 2f, 3f to 4f)) {
                o.writeFloat(x)
                o.writeFloat(y)
                o.writeInt(0)
                o.writeFloat(0.1f)
                o.writeFloat(0.2f)
                o.writeFloat(0.3f)
            }
        }
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), StrokeCodec.points(bytes.toByteArray()), 0f)
    }

    @Test
    fun truncatedBytesYieldNoPoints() {
        assertEquals(0, StrokeCodec.points(ByteArray(8)).size)
    }

    @Test
    fun transformScalesAndMovesKeepingTheRest() {
        val moved = StrokeCodec.transform(sample(), Transform(2f, 0f, 1f, -1f))
        assertArrayEquals(floatArrayOf(21f, 39f, 61f, -11f), StrokeCodec.points(moved), 0f)
        DataInputStream(moved.inputStream()).use { input ->
            assertEquals(3, input.readUnsignedByte())
            assertEquals(3, input.readUnsignedByte())
            assertEquals(-1f, input.readFloat())
            assertEquals(7, input.readInt())
            assertEquals(2, input.readInt())
            input.skipBytes(8)
            assertEquals(0, input.readInt())
            assertEquals(0.5f, input.readFloat())
            assertEquals(0.25f, input.readFloat())
        }
        assertEquals(sample().size, moved.size)
    }

    @Test
    fun quarterTurnAroundAPoint() {
        // 90° alrededor de (10, 20): (10, 20) queda y (30, -5) va a (35, 40).
        val turned = StrokeCodec.points(StrokeCodec.transform(sample(), Transform.rotation((Math.PI / 2).toFloat(), 10f, 20f)))
        assertArrayEquals(floatArrayOf(10f, 20f, 35f, 40f), turned, 1e-3f)
    }
}
