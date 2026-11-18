package com.octavio.tinta

import com.octavio.tinta.storage.BackupDirectory
import com.octavio.tinta.storage.BackupDocument
import com.octavio.tinta.storage.BackupRotation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupRotationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val base = "Tinta - copia de las notas"

    @Test
    fun familyNames() {
        assertTrue(BackupRotation.isFamily("$base.zip", base))
        assertTrue(BackupRotation.isFamily("$base.170000.zip", base))
        assertTrue(BackupRotation.isFamily("$base (1).zip", base))
        assertTrue(BackupRotation.isFamily("$base.170000 (2).zip", base))
        assertFalse(BackupRotation.isFamily("$base.parcial.zip", base))
        assertFalse(BackupRotation.isFamily("foto.zip", base))
        assertFalse(BackupRotation.isFamily("$base.txt", base))
        assertFalse(BackupRotation.isFamily("$base.170000.zip.zip", base))
    }

    @Test
    fun aCutWhileWritingLeavesThePreviousBackupUntouched() {
        val dir = MemoryDirectory()
        val oldBytes = zip(1)
        val old = dir.seed("$base.zip", oldBytes)
        dir.crashNextWriteAfter = 8
        val order = ArrayList<String>()
        try {
            BackupRotation.publish(dir, file(zip(2)), base) { order.add("commit:${it.name}") }
            fail("tenía que cortarse")
        } catch (e: IOException) {
            assertEquals("corte", e.message)
        }
        assertTrue(order.isEmpty())
        assertEquals(0, old.writes)
        assertFalse(old.deleted)
        assertTrue(old.bytes.contentEquals(oldBytes))
        assertEquals(listOf(old.key), dir.documents().map { it.key })
        val best = BackupRotation.newest(dir, base)
        assertEquals(old.key, best?.key)
        assertEquals(1L, (BackupRotation.inspect(best!!) as BackupRotation.State.Valid).fecha)
    }

    @Test
    fun theOldBackupIsDeletedOnlyAfterTheNewOneIsRemembered() {
        val dir = MemoryDirectory()
        val old = dir.seed("$base.zip", zip(1))
        val order = ArrayList<String>()
        dir.onDeleted = { order.add("delete:${it.name}") }
        val published = BackupRotation.publish(dir, file(zip(5)), base) { order.add("commit:${it.name}") }
        assertEquals(listOf("commit:${published.name}", "delete:${old.name}"), order)
        assertTrue(published is MemoryDoc && published.bytes.contentEquals(zip(5)))
        assertEquals(0, old.writes)
        assertTrue(old.deleted)
        assertEquals(published.key, BackupRotation.newest(dir, base)?.key)
    }

    @Test
    fun rememberingCanFailAndTheOldBackupStays() {
        val dir = MemoryDirectory()
        val oldBytes = zip(1)
        val old = dir.seed("$base.zip", oldBytes)
        try {
            BackupRotation.publish(dir, file(zip(2)), base) { error("no se pudo anotar") }
            fail("tenía que fallar")
        } catch (e: IllegalStateException) {
            assertEquals("no se pudo anotar", e.message)
        }
        assertEquals(0, old.writes)
        assertFalse(old.deleted)
        assertTrue(old.bytes.contentEquals(oldBytes))
        val fechas = dir.documents().mapNotNull {
            (BackupRotation.inspect(it) as? BackupRotation.State.Valid)?.fecha
        }
        assertEquals(setOf(1L, 2L), fechas.toSet())
        assertEquals(2L, (BackupRotation.inspect(BackupRotation.newest(dir, base)!!) as BackupRotation.State.Valid).fecha)
    }

    @Test
    fun aMismatchedReadBackDeletesOnlyTheNewFile() {
        val dir = MemoryDirectory()
        val old = dir.seed("$base.zip", zip(1))
        dir.lieOnNextRead = true
        try {
            BackupRotation.publish(dir, file(zip(4)), base)
            fail("tenía que rechazar la copia")
        } catch (e: IllegalStateException) {
            assertEquals("la copia nueva no coincide con la que se armó", e.message)
        }
        assertFalse(old.deleted)
        assertEquals(0, old.writes)
        assertEquals(listOf(old.key), dir.documents().map { it.key })
    }

    @Test
    fun anUnreadableBackupIsNotDeleted() {
        val dir = MemoryDirectory()
        val old = dir.seed("$base.zip", zip(1))
        old.failRead = true
        val published = BackupRotation.publish(dir, file(zip(3)), base)
        assertFalse(old.deleted)
        assertEquals(0, old.writes)
        assertEquals(published.key, BackupRotation.newest(dir, base)?.key)
        assertEquals(2, dir.documents().size)
    }

    @Test
    fun aBrokenZipIsSkipped() {
        val dir = MemoryDirectory()
        dir.seed("$base.zip", "esto no es un zip".toByteArray())
        val cut = zip(9)
        dir.seed("$base.9.zip", cut.copyOf(20))
        val good = dir.seed("$base.4.zip", zip(4))
        val best = BackupRotation.newest(dir, base)
        assertEquals(good.key, best?.key)
        assertEquals(4L, (BackupRotation.inspect(best!!) as BackupRotation.State.Valid).fecha)
    }

    @Test
    fun aNewerBackupIsNotReplacedByAnOlderOne() {
        val dir = MemoryDirectory()
        val newer = dir.seed("$base.50.zip", zip(50))
        val published = BackupRotation.publish(dir, file(zip(4)), base)
        assertFalse(newer.deleted)
        assertEquals(0, newer.writes)
        assertTrue(dir.documents().any { it.key == published.key })
        assertEquals(newer.key, BackupRotation.newest(dir, base)?.key)
    }

    @Test
    fun theNewestManifestWinsOverTheCanonicalName() {
        val dir = MemoryDirectory()
        dir.seed("$base.zip", zip(3))
        val newer = dir.seed("$base.2.zip", zip(9))
        assertEquals(newer.key, BackupRotation.newest(dir, base)?.key)
    }

    @Test
    fun aProviderSuffixStillCountsAsTheNewBackup() {
        val inner = MemoryDirectory()
        val old = inner.seed("$base.zip", zip(1))
        val dir = object : BackupDirectory {
            override fun documents() = inner.documents()
            override fun create(name: String) = inner.create(name.removeSuffix(".zip") + " (1).zip")
        }
        val published = BackupRotation.publish(dir, file(zip(6)), base)
        assertTrue(published.name.endsWith(" (1).zip"))
        assertTrue(BackupRotation.isFamily(published.name, base))
        assertTrue(old.deleted)
        assertEquals(published.key, BackupRotation.newest(dir, base)?.key)
    }

    @Test
    fun anUnrelatedFileIsLeftAlone() {
        val dir = MemoryDirectory()
        val photo = dir.seed("foto.zip", zip(1))
        val notes = dir.seed("notas.txt", zip(1))
        BackupRotation.publish(dir, file(zip(2)), base)
        assertFalse(photo.deleted)
        assertFalse(notes.deleted)
        assertTrue(photo.bytes.contentEquals(zip(1)))
        assertEquals(0, photo.writes)
    }

    @Test
    fun theSameBytesAreNotWrittenAgain() {
        val bytes = zip(4)
        val dir = MemoryDirectory()
        val existing = dir.seed("$base.4.zip", bytes)
        val published = BackupRotation.publish(dir, file(bytes), base)
        assertEquals(existing.key, published.key)
        assertEquals(0, existing.writes)
        assertEquals(1, dir.documents().size)
    }

    @Test
    fun aZipThatIsNotTintaDoesNotTouchTheFolder() {
        val dir = MemoryDirectory()
        val old = dir.seed("$base.zip", zip(1))
        try {
            BackupRotation.publish(dir, file(byteArrayOf(1, 2, 3, 4)), base)
            fail("tenía que rechazar el zip")
        } catch (e: IllegalStateException) {
            assertEquals("la copia armada no es de Tinta", e.message)
        }
        assertEquals(0, old.writes)
        assertFalse(old.deleted)
        assertEquals(1, dir.documents().size)
    }

    @Test
    fun aKnownKeyIsFoundEvenWithAStrangeName() {
        val dir = MemoryDirectory()
        dir.seed("$base.zip", zip(1))
        val odd = dir.seed("copia renombrada por drive", zip(8))
        assertEquals(odd.key, BackupRotation.newest(dir, base, includeKey = odd.key)?.key)
        assertEquals(1L, (BackupRotation.inspect(BackupRotation.newest(dir, base)!!) as BackupRotation.State.Valid).fecha)
    }

    @Test
    fun manifestFechaIsReadFromAProductionZip() {
        val bytes = zip(1_700_000_000_000L)
        val fecha = bytes.inputStream().use { BackupRotation.readFecha(it) }
        assertEquals(1_700_000_000_000L, fecha)
        val fp = BackupRotation.fingerprint(bytes.inputStream())
        assertEquals(bytes.size.toLong(), fp.bytes)
    }

    private fun zip(fecha: Long, note: String = "nota"): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BackupRotation.MANIFEST))
            zip.write(
                JSONObject().put("version", 1).put("fecha", fecha).put("app", "Tinta").toString(2)
                    .toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("notes/a.tinta"))
            zip.write(note.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun file(bytes: ByteArray): File = tmp.newFile().apply { writeBytes(bytes) }
}

private class MemoryDirectory : BackupDirectory {
    private val docs = LinkedHashMap<String, MemoryDoc>()
    private var seq = 0L
    var crashNextWriteAfter: Int? = null
    var lieOnNextRead = false
    var onDeleted: (MemoryDoc) -> Unit = {}

    override fun documents(): List<BackupDocument> = docs.values.toList()

    override fun create(name: String): BackupDocument {
        val doc = MemoryDoc("id-${seq++}", name)
        doc.failWriteAfter = crashNextWriteAfter
        crashNextWriteAfter = null
        doc.lieOnRead = lieOnNextRead
        lieOnNextRead = false
        doc.onDelete = {
            docs.remove(doc.key)
            onDeleted(doc)
        }
        docs[doc.key] = doc
        return doc
    }

    fun seed(name: String, bytes: ByteArray): MemoryDoc {
        val doc = create(name) as MemoryDoc
        doc.bytes = bytes
        doc.writes = 0
        doc.failWriteAfter = null
        doc.lieOnRead = false
        return doc
    }
}

private class MemoryDoc(
    override val key: String,
    override val name: String,
) : BackupDocument {
    var bytes: ByteArray = ByteArray(0)
    var writes: Int = 0
    var failWriteAfter: Int? = null
    var failRead: Boolean = false
    var lieOnRead: Boolean = false
    var deleted: Boolean = false
    var onDelete: () -> Unit = {}

    override fun openInput(): InputStream {
        if (failRead) throw IOException("no se pudo leer")
        val data = if (lieOnRead) ByteArray(bytes.size) { 7 } else bytes
        return ByteArrayInputStream(data)
    }

    override fun openOutput(): OutputStream {
        writes++
        val limit = failWriteAfter
        return object : OutputStream() {
            private val buf = ByteArrayOutputStream()
            override fun write(b: Int) {
                write(byteArrayOf(b.toByte()), 0, 1)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (limit != null && buf.size() + len > limit) {
                    val room = (limit - buf.size()).coerceAtLeast(0)
                    if (room > 0) buf.write(b, off, room)
                    bytes = buf.toByteArray()
                    throw IOException("corte")
                }
                buf.write(b, off, len)
                bytes = buf.toByteArray()
            }

            override fun close() {
                bytes = buf.toByteArray()
            }
        }
    }

    override fun delete() {
        deleted = true
        onDelete()
    }
}
