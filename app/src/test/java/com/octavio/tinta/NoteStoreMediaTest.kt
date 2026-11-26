package com.octavio.tinta

import com.octavio.tinta.storage.NoteStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Los medios de la nota salen enteros: mismo contenido, sin temporal colgado. */
class NoteStoreMediaTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun keepRepiteLaMismaImagenYReescribeUnaCortada() {
        val dir = tmp.newFolder()
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val file = NoteStore.keep(dir, bytes)
        assertTrue(file.name.matches(Regex("[0-9a-f]{20}\\.img")))
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(file, NoteStore.keep(dir, bytes))
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })

        file.writeBytes(byteArrayOf(1))
        val again = NoteStore.keep(dir, bytes)
        assertEquals(file, again)
        assertArrayEquals(bytes, again.readBytes())
        assertFalse(File(dir, again.name + ".tmp").exists())
    }

    @Test
    fun copyMediaCopiaUnaVezYNoPisaLoQueYaEsta() {
        val srcDir = tmp.newFolder("origen")
        val dstDir = tmp.newFolder("destino")
        val src = File(srcDir, "foto.img").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        assertSame(src, NoteStore.copyMedia(src, srcDir))

        val copied = NoteStore.copyMedia(src, dstDir)
        assertEquals(File(dstDir, "foto.img"), copied)
        assertArrayEquals(byteArrayOf(9, 8, 7), copied.readBytes())
        assertFalse(File(dstDir, "foto.img.tmp").exists())

        copied.writeBytes(byteArrayOf(1))
        val again = NoteStore.copyMedia(src, dstDir)
        assertEquals(copied, again)
        assertArrayEquals(byteArrayOf(1), again.readBytes())

        val missing = File(srcDir, "no-esta.img")
        val absent = NoteStore.copyMedia(missing, dstDir)
        assertEquals(File(dstDir, "no-esta.img"), absent)
        assertFalse(absent.exists())
    }
}
