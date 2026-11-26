package com.octavio.tinta

import com.octavio.tinta.export.Exporter
import com.octavio.tinta.export.MIME_PDF
import com.octavio.tinta.export.MIME_PNG
import com.octavio.tinta.export.MIME_TEXT
import com.octavio.tinta.export.MIME_ZIP
import com.octavio.tinta.export.isInside
import com.octavio.tinta.export.saveTarget
import com.octavio.tinta.export.suggestedFileName
import com.octavio.tinta.export.uniqueZipNames
import com.octavio.tinta.export.zipFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipInputStream

class SaveAsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun mimeAndExtensionStayTheOnesShareAlreadyUses() {
        assertEquals("application/pdf", MIME_PDF)
        assertEquals("image/png", MIME_PNG)
        assertEquals("text/plain", MIME_TEXT)
        assertEquals("application/zip", MIME_ZIP)
        assertEquals("application/octet-stream", Exporter.BUNDLE_MIME)
        assertEquals(".tinta", Exporter.BUNDLE_EXT)
    }

    @Test
    fun eachFormatMapsToItsMimeAndSuggestedName() {
        val pdf = saveTarget("Física", Exporter.Format.PDF, listOf("Física.pdf"))
        assertEquals(MIME_PDF, pdf.mime)
        assertEquals("Física.pdf", pdf.suggestedName)
        assertFalse(pdf.zip)

        val text = saveTarget("Física", Exporter.Format.TEXT, emptyList())
        assertEquals(MIME_TEXT, text.mime)
        assertEquals("Física.txt", text.suggestedName)
        assertFalse(text.zip)

        val note = saveTarget("Física", Exporter.Format.NOTE, listOf("Física.tinta"))
        assertEquals(Exporter.BUNDLE_MIME, note.mime)
        assertEquals("Física.tinta", note.suggestedName)
        assertFalse(note.zip)

        val one = saveTarget("Física", Exporter.Format.IMAGES, listOf("Física.png"))
        assertEquals(MIME_PNG, one.mime)
        assertEquals("Física.png", one.suggestedName)
        assertFalse(one.zip)

        val many = saveTarget("Física", Exporter.Format.IMAGES, listOf("Física - hoja 1.png", "Física - hoja 2.png"))
        assertEquals(MIME_ZIP, many.mime)
        assertEquals("Física.zip", many.suggestedName)
        assertTrue(many.zip)
    }

    @Test
    fun suggestedNameStripsPathsAndFallsBackWhenBlank() {
        assertEquals("Clase.pdf", suggestedFileName("nota.pdf", "Clase.pdf"))
        assertEquals("Clase.pdf", suggestedFileName("nota.pdf", "/cache/exports/Clase.pdf"))
        assertEquals("Clase.pdf", suggestedFileName("nota.pdf", "C:\\exports\\Clase.pdf"))
        assertEquals("nota.pdf", suggestedFileName("nota.pdf", null))
        assertEquals("nota.pdf", suggestedFileName("nota.pdf", "   "))
        assertEquals("nota.pdf", suggestedFileName("nota.pdf", "/tmp/"))

        assertEquals("a_b_c.pdf", saveTarget("a/b:c", Exporter.Format.PDF, emptyList()).suggestedName)
        assertEquals("nota.png", saveTarget("   ", Exporter.Format.IMAGES, emptyList()).suggestedName)
        assertEquals("nota.zip", saveTarget("   ", Exporter.Format.IMAGES, listOf("a.png", "b.png")).suggestedName)
    }

    @Test
    fun zipNamesDropDirectoriesAndDoNotCollide() {
        assertEquals(listOf("a.png", "b.png"), uniqueZipNames(listOf("a.png", "dir/b.png")))
        assertEquals(listOf("a.png", "a-2.png"), uniqueZipNames(listOf("a.png", "a.png")))
        assertEquals(listOf("a.png", "a-2.png", "a-2-2.png"), uniqueZipNames(listOf("a.png", "a.png", "a-2.png")))
        assertEquals(listOf("archivo", "c.png"), uniqueZipNames(listOf("  ", "dir\\c.png")))
        assertEquals(listOf("hoja.png", "hoja-2.png"), uniqueZipNames(listOf("carpeta/hoja.png", "otra/hoja.png")))
    }

    @Test
    fun zipStoresEachPageUnderItsOwnName() {
        val first = tmp.newFile("Clase - hoja 1.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val second = tmp.newFile("Clase - hoja 2.png").apply { writeBytes(byteArrayOf(9, 8)) }
        val dest = File(tmp.root, "Clase.zip")
        zipFiles(dest, listOf(first, second))
        val entries = unzip(dest)
        assertEquals(listOf("Clase - hoja 1.png", "Clase - hoja 2.png"), entries.keys.toList())
        assertArrayEquals(byteArrayOf(1, 2, 3), entries.getValue("Clase - hoja 1.png"))
        assertArrayEquals(byteArrayOf(9, 8), entries.getValue("Clase - hoja 2.png"))
    }

    @Test
    fun duplicateZipEntriesGetASuffixAndKeepTheirBytes() {
        val first = File(tmp.newFolder("paginas"), "hoja.png").apply { writeBytes(byteArrayOf(1)) }
        val second = File(tmp.newFolder("otras"), "hoja.png").apply { writeBytes(byteArrayOf(2, 2)) }
        val dest = File(tmp.root, "hojas.zip")
        zipFiles(dest, listOf(first, second))
        val entries = unzip(dest)
        assertEquals(setOf("hoja.png", "hoja-2.png"), entries.keys)
        assertArrayEquals(byteArrayOf(1), entries.getValue("hoja.png"))
        assertArrayEquals(byteArrayOf(2, 2), entries.getValue("hoja-2.png"))
    }

    @Test
    fun isInsideRejectsPrefixSiblingsAndParentEscape() {
        val root = tmp.newFolder("exports")
        val child = File(root, "nota.pdf").apply { writeBytes(byteArrayOf(1)) }
        val nestedDir = File(root, "sub").apply { mkdirs() }
        val nested = File(nestedDir, "nota.pdf").apply { writeBytes(byteArrayOf(2)) }
        val siblingDir = File(tmp.root, "exports-otro").apply { mkdirs() }
        val sibling = File(siblingDir, "nota.pdf").apply { writeBytes(byteArrayOf(3)) }
        val outside = tmp.newFile("otra.pdf")

        assertTrue(isInside(root, child))
        assertTrue(isInside(root, nested))
        assertTrue(isInside(root, root))
        assertFalse(isInside(root, sibling))
        assertFalse(isInside(root, outside))
        assertFalse(isInside(root, File(root, "../otra.pdf")))
    }

    private fun unzip(file: File): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes()
            }
        }
        return out
    }
}
