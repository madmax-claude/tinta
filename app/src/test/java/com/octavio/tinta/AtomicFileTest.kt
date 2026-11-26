package com.octavio.tinta

import com.octavio.tinta.storage.AtomicFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.SyncFailedException

/** El reemplazo atómico: temporal en la misma carpeta, fsync, rename, y el archivo vivo intacto si falla. */
class AtomicFileTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun reemplazaElArchivoYBorraElTemporal() {
        val target = File(tmp.newFolder(), "nota.png")
        target.writeBytes(byteArrayOf(9, 9, 9))
        val bytes = ByteArray(200_000) { (it * 31 and 0xff).toByte() }
        val order = ArrayList<String>()
        AtomicFile.commit(
            target,
            sync = {
                order.add("sync")
                val parcial = File(target.parentFile, target.name + ".tmp")
                assertEquals(target.parentFile, parcial.parentFile)
                assertEquals(bytes.size.toLong(), parcial.length())
            },
            rename = { from, to ->
                order.add("rename")
                from.renameTo(to)
            },
        ) {
            order.add("write")
            it.write(bytes)
        }
        assertEquals(listOf("write", "sync", "rename"), order)
        assertArrayEquals(bytes, target.readBytes())
        assertFalse(File(target.parentFile, target.name + ".tmp").exists())
    }

    @Test
    fun unArchivoMasCortoReemplazaPorCompleto() {
        val target = File(tmp.newFolder(), "foto.img")
        target.writeBytes(ByteArray(100) { 7 })
        AtomicFile.write(target) { it.write(byteArrayOf(1, 2, 3)) }
        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
    }

    @Test
    fun unTemporalViejoNoSeMezclaConLoNuevo() {
        val dir = tmp.newFolder()
        val target = File(dir, "nota.png")
        val stale = File(dir, "nota.png.tmp").apply { writeBytes(ByteArray(50) { 9 }) }
        AtomicFile.write(target) { it.write(byteArrayOf(1, 2, 3)) }
        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
        assertFalse(stale.exists())
    }

    @Test
    fun cerrarElStreamAdentroNoPierdeBytes() {
        val target = File(tmp.newFolder(), "nota.vista")
        val payload = ByteArray(20_000) { (it and 0xff).toByte() }
        AtomicFile.write(target) { raw ->
            DataOutputStream(raw).use { out ->
                out.writeInt(0x54565354)
                out.write(payload)
            }
            raw.write("!".toByteArray())
        }
        val all = target.readBytes()
        assertEquals(4 + payload.size + 1, all.size)
        assertEquals('!'.code.toByte(), all.last())
        assertArrayEquals(payload, all.copyOfRange(4, all.size - 1))
        assertFalse(File(target.parentFile, target.name + ".tmp").exists())
    }

    @Test
    fun siQuienEscribeFallaElOriginalQuedaIgual() {
        val dir = tmp.newFolder()
        val target = File(dir, "nota.texto")
        val original = "renglón que ya estaba".toByteArray()
        target.writeBytes(original)
        val boom = IllegalStateException("lleno")
        val caught = assertThrows(IllegalStateException::class.java) {
            AtomicFile.write(target) {
                it.write("a medias".toByteArray())
                throw boom
            }
        }
        assertSame(boom, caught)
        assertArrayEquals(original, target.readBytes())
        assertFalse(File(dir, "nota.texto.tmp").exists())

        val nuevo = File(dir, "nuevo.texto")
        assertThrows(IllegalStateException::class.java) {
            AtomicFile.write(nuevo) { throw boom }
        }
        assertFalse(nuevo.exists())
        assertFalse(File(dir, "nuevo.texto.tmp").exists())
    }

    @Test
    fun siElFsyncFallaNoRenombra() {
        val dir = tmp.newFolder()
        val target = File(dir, "nota.png")
        val original = byteArrayOf(4, 5, 6, 7)
        target.writeBytes(original)
        var renamed = false
        val error = assertThrows(SyncFailedException::class.java) {
            AtomicFile.commit(
                target,
                sync = { throw SyncFailedException("corte") },
                rename = { _, _ ->
                    renamed = true
                    false
                },
            ) { it.write(ByteArray(30_000) { 1 }) }
        }
        assertEquals("corte", error.message)
        assertFalse(renamed)
        assertArrayEquals(original, target.readBytes())
        assertFalse(File(dir, "nota.png.tmp").exists())

        val nuevo = File(dir, "mini.png")
        assertThrows(SyncFailedException::class.java) {
            AtomicFile.commit(nuevo, sync = { throw SyncFailedException("corte") }) { it.write(byteArrayOf(1)) }
        }
        assertFalse(nuevo.exists())
        assertFalse(File(dir, "mini.png.tmp").exists())
    }

    @Test
    fun siElRenameFallaNoPisaElOriginal() {
        val dir = tmp.newFolder()
        val target = File(dir, "nota.png")
        val original = byteArrayOf(8, 8, 8, 8, 8)
        target.writeBytes(original)
        val error = assertThrows(IOException::class.java) {
            AtomicFile.commit(target, rename = { _, _ -> false }) { it.write(byteArrayOf(1, 2, 3)) }
        }
        assertTrue(error.message!!.contains("no se pudo guardar"))
        assertTrue(error.message!!.contains("nota.png"))
        assertArrayEquals(original, target.readBytes())
        assertFalse(File(dir, "nota.png.tmp").exists())
    }

    @Test
    fun renameRealSobreUnDirectorioNoLoPisa() {
        val dir = tmp.newFolder()
        val target = File(dir, "nota.vista").apply { check(mkdir()) }
        val error = assertThrows(IOException::class.java) {
            AtomicFile.write(target) { it.write(byteArrayOf(1, 2, 3, 4)) }
        }
        assertTrue(error.message!!.contains("no se pudo guardar"))
        assertTrue(target.isDirectory)
        assertFalse(File(dir, "nota.vista.tmp").exists())
    }

    @Test
    fun sinCarpetaFallaYNoCreaNada() {
        val target = File(tmp.root, "no-esta/nota.png")
        assertThrows(IOException::class.java) {
            AtomicFile.write(target) { it.write(byteArrayOf(1)) }
        }
        assertFalse(target.exists())
        assertFalse(File(tmp.root, "no-esta/nota.png.tmp").exists())

        val name = "suelto-${System.nanoTime()}.bin"
        val bare = File(name)
        try {
            val missing = assertThrows(IOException::class.java) {
                AtomicFile.write(bare) { it.write(byteArrayOf(1)) }
            }
            assertTrue(missing.message!!.contains("sin carpeta"))
            assertFalse(bare.exists())
            assertFalse(File(name + ".tmp").exists())
        } finally {
            bare.delete()
            File(name + ".tmp").delete()
        }
    }
}
