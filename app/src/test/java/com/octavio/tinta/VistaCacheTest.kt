package com.octavio.tinta

import com.octavio.tinta.storage.VistaCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

class VistaCacheTest {

    @Test
    fun roundTrip() {
        val dir = File.createTempFile("vista", "").apply { delete(); mkdirs() }
        try {
            val file = File(dir, "nota.vista")
            val bands = listOf(
                VistaCache.Band(1f, 2f, 30f, 40f, byteArrayOf(1, 2, 3, 4)),
                VistaCache.Band(-8f, 0f, 12.5f, 9f, byteArrayOf(9, 8)),
            )
            VistaCache.write(file, 1_700_000_000_000L, 2400, dark = true, scale = 1.25f, bands)
            val saved = VistaCache.read(file)!!
            assertTrue(saved.matches(1_700_000_000_000L, 2400, dark = true))
            assertEquals(1.25f, saved.scale)
            assertEquals(2, saved.bands.size)
            assertEquals(1f, saved.bands[0].left)
            assertEquals(40f, saved.bands[0].bottom)
            assertEquals(4, saved.bands[0].png.size)
            assertEquals(12.5f, saved.bands[1].right)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun otraEpocaNoSirve() {
        val dir = File.createTempFile("vista", "").apply { delete(); mkdirs() }
        try {
            val file = File(dir, "nota.vista")
            DataOutputStream(FileOutputStream(file)).use {
                it.writeInt(VistaCache.MAGIC)
                it.writeInt(VistaCache.VERSION)
                it.writeInt(VistaCache.EPOCH + 1)
                it.writeLong(1L)
                it.writeInt(10)
                it.writeInt(0)
                it.writeFloat(1f)
                it.writeInt(0)
            }
            assertNull(VistaCache.read(file))
        } finally {
            dir.deleteRecursively()
        }
    }
}
