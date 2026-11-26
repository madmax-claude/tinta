package com.octavio.tinta

import com.octavio.tinta.export.CacheEntry
import com.octavio.tinta.export.ExportCache
import com.octavio.tinta.export.filesToDelete
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExportCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_700_000_000_000L
    private val age = ExportCache.STALE_AFTER_MS

    @Test
    fun deletesOnlyFilesOlderThanTheThreshold() {
        val young = entry("nuevo.pdf", now - age)
        val old = entry("viejo.pdf", now - age - 1)
        val future = entry("reloj.pdf", now + 5_000)
        val unknown = entry("sin-fecha.pdf", 0L)
        val folder = CacheEntry("carpeta", isFile = false, lastModified = now - age - 1, key = "/exports/carpeta")
        assertEquals(
            listOf("/exports/viejo.pdf"),
            filesToDelete(listOf(young, old, future, unknown, folder), now, age, emptyMap(), emptySet()),
        )
    }

    @Test
    fun keepsAFileAShareMayStillBeReading() {
        val old = entry("nota.pdf", now - age - 1)
        val leased = mapOf(old.key to now + 1)
        assertEquals(emptyList<String>(), filesToDelete(listOf(old), now, age, leased, emptySet()))
        // El plazo ya venció (until == now): si además es viejo, se borra.
        assertEquals(listOf(old.key), filesToDelete(listOf(old), now, age, mapOf(old.key to now), emptySet()))
    }

    @Test
    fun keepsAFileWhileItIsBeingWritten() {
        val old = entry("nota.pdf", now - age - 1)
        assertEquals(emptyList<String>(), filesToDelete(listOf(old), now, age, emptyMap(), setOf(old.key)))
        assertEquals(listOf(old.key), filesToDelete(listOf(old), now, age, emptyMap(), setOf("/otro")))
    }

    @Test
    fun negativeAgeDeletesNothing() {
        val old = entry("nota.pdf", now - 1)
        assertEquals(emptyList<String>(), filesToDelete(listOf(old), now, -1L, emptyMap(), emptySet()))
    }

    @Test
    fun cleanupOnDiskHonorsAgeLeaseAndWrite() {
        val dir = tmp.newFolder("exports")
        val old = file(dir, "viejo.pdf", now - age - 10_000)
        val young = file(dir, "nuevo.pdf", now - 1_000)
        val shared = file(dir, "compartido.pdf", now - age - 10_000)
        val busy = file(dir, "escribiendo.pdf", now - age - 10_000)
        val folder = File(dir, "sub").apply {
            mkdirs()
            assertTrue(setLastModified(now - age - 10_000))
        }

        ExportCache.markShared(listOf(shared), now)
        ExportCache.keeping(busy) {
            ExportCache.cleanup(dir, now)
            assertFalse(old.exists())
            assertTrue(young.exists())
            assertTrue(shared.exists())
            assertTrue(busy.exists())
            assertTrue(folder.exists())
        }

        assertTrue(young.setLastModified(now + ExportCache.SHARE_LEASE_MS))
        ExportCache.cleanup(dir, now + ExportCache.SHARE_LEASE_MS)
        assertFalse(shared.exists())
        assertFalse(busy.exists())
        assertTrue(young.exists())
        assertTrue(folder.exists())
    }

    @Test
    fun missingDirectoryIsLeftAlone() {
        val missing = File(tmp.root, "no-esta")
        ExportCache.cleanup(missing, now)
        assertFalse(missing.exists())
    }

    private fun entry(name: String, modified: Long) =
        CacheEntry(name, isFile = true, lastModified = modified, key = "/exports/$name")

    private fun file(dir: File, name: String, modified: Long): File =
        File(dir, name).apply {
            writeText(name)
            assertTrue(setLastModified(modified))
        }
}
