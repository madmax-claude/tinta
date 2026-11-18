package com.octavio.tinta

import com.octavio.tinta.model.Lasso
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class LassoTest {
    private val square = Lasso(floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f))

    @Test
    fun halfThePointsInsideIsEnough() {
        // (10, 10) adentro, (10, 200) afuera: uno de dos alcanza.
        val pts = floatArrayOf(10f, 10f, 10f, 200f)
        assertTrue(square.containsMostOf(pts))
        assertTrue(square.containsMostOf(bytes(pts)))
    }

    @Test
    fun lessThanHalfStaysOut() {
        val pts = floatArrayOf(10f, 10f, 10f, 200f, 200f, 10f)
        assertFalse(square.containsMostOf(pts))
        assertFalse(square.containsMostOf(bytes(pts)))
    }

    @Test
    fun bytesAndArrayAgreeWithEveryExtraField() {
        val pts = floatArrayOf(10f, 10f, 50f, 50f, 150f, 10f)
        val encoded = bytes(pts, extras = 1 or 2 or 4)
        assertEquals(square.containsMostOf(pts), square.containsMostOf(encoded))
        assertTrue(square.containsMostOf(encoded))
    }

    /** Bytes como los escribe StrokeCodec, con `extras` campos de más por punto. */
    private fun bytes(xy: FloatArray, extras: Int = 0): ByteArray {
        val n = xy.size / 2
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            o.writeByte(3)
            o.writeByte(extras)
            o.writeFloat(-1f)
            o.writeInt(0)
            o.writeInt(n)
            val extraCount = Integer.bitCount(extras)
            for (i in 0 until n) {
                o.writeFloat(xy[2 * i])
                o.writeFloat(xy[2 * i + 1])
                o.writeInt(i)
                repeat(extraCount) { o.writeFloat(0.25f) }
            }
        }
        return out.toByteArray()
    }
}
