package com.octavio.tinta

import com.octavio.tinta.jnotes.JnotesFile
import com.octavio.tinta.jnotes.JnotesImporter
import com.octavio.tinta.model.Background
import com.octavio.tinta.model.PageLayout
import com.octavio.tinta.model.PageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Arma un .Jnotes chico con la estructura que documenta jnotes2pdf.py. */
class JnotesFileTest {

    private fun record(out: DataOutputStream, type: String, id: String, json: String, blob: ByteArray? = null) {
        val body = json.toByteArray()
        out.writeInt(1)
        out.writeUTF(type)
        out.writeUTF("DB")
        out.writeUTF(id)
        out.writeLong(body.size.toLong())
        out.writeUTF("")
        out.writeUTF("")
        out.writeUTF(blob?.size?.toString() ?: "")
        out.write(body)
        blob?.let { out.write(it) }
    }

    private fun sample(): ByteArray {
        val jzip = ByteArrayOutputStream()
        DataOutputStream(jzip).use { out ->
            out.writeUTF("TRY")
            out.writeInt(3)
            out.writeUTF("nota1")
            record(out, "NOTE", "nota1", """{"b":"Física II"}""")
            record(out, "PAGE", "p1", """{"k":1240,"l":1754}""")
            record(
                out, "NBSTROKE", "s",
                """[{"a":"p1","c":2,"h":10,"i":20,"b":{"a":1,"c":-16777216,"d":3,
                     "k":[{"x":110,"y":220,"p":0.5},{"x":130,"y":240,"p":0.7}]}},
                    {"a":"p1","c":1,"h":0,"i":0,"b":{"a":3,"c":-256,"d":8,"k":[{"x":20,"y":20,"p":0}]}}]""",
            )
            record(out, "IMAGE", "img", """{"b":"p1","x":110,"y":220,"i":10,"j":20,"c":300,"d":150,"e":90}""", byteArrayOf(9, 9, 9))
            record(out, "TEXT", "t", """[{"b":"p1","x":50,"y":60,"t":5,"u":10,"c":200,"d":40,"e":"hola\nchau","g":30,"f":-65536}]""")
            out.writeInt(0)
        }
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use {
            it.putNextEntry(ZipEntry("zip.Jzip"))
            it.write(jzip.toByteArray())
            it.closeEntry()
        }
        return zip.toByteArray()
    }

    @Test
    fun parsesNotePagesAndStrokes() {
        val note = JnotesFile.parse(sample())
        assertEquals("Física II", note.title)
        assertEquals(1, note.pages.size)
        val page = note.pages[0]
        val image = page.images.single()
        assertEquals(100f, image.x) // 110 - i
        assertEquals(200f, image.y) // 220 - j
        assertEquals(300f, image.width)
        assertEquals(90f, image.rotation)
        assertEquals(3, image.bytes.size)
        val text = page.texts.single()
        assertEquals("hola\nchau", text.text)
        assertEquals(45f, text.x) // 50 - t
        assertEquals(30f, text.size)
        assertEquals(0xFFFF0000.toInt(), text.color)
        // Ordenados por "c": primero el resaltador.
        assertEquals(listOf(3, 1), page.strokes.map { it.tool })
        val pen = page.strokes[1]
        assertEquals(0xFF000000.toInt(), pen.color)
        assertEquals(100f, pen.xs[0]) // 110 - h
        assertEquals(200f, pen.ys[0]) // 220 - i
        assertEquals(0.7f, pen.pressures[1])
    }

    /** Cuaderno con tapa: hojas declaradas fuera de orden, trazos STROKE y la imagen de tapa. */
    private fun notebook(kind: Int = 1, pdf: ByteArray? = null): ByteArray {
        val jzip = ByteArrayOutputStream()
        DataOutputStream(jzip).use { out ->
            out.writeUTF("TRY")
            out.writeInt(2)
            out.writeUTF("n2")
            val bg = """{\"bgColor\":-198413,\"paperBg\":\"PageBg/Beige_Dotted_Paper\"}"""
            record(out, "NOTE", "Cuaderno", """{"b":"Cuaderno","c":$kind,"i":"carpeta-1","p":"$bg"}""")
            record(out, "PAGE", "h2", """{"a":"h2","c":1,"i":false,"d":"","k":1240.0,"l":1754.0,"p":""}""")
            record(out, "PAGE", "tapa", """{"a":"tapa","c":0,"i":true,"d":"/Cover/1_2_-1_natural3","k":1240.0,"l":1754.0}""")
            record(
                out, "STROKE", "strokes",
                """[{"b":0,"a":"h2","c":{"a":2,"c":-16777216,"d":3.0,"r":-3.1,"s":56.6,"t":0.0,"l":[],
                     "k":[{"x":100,"y":200,"p":0.5},{"x":124,"y":200,"p":0.5}]},"e":238},
                    {"b":1,"a":"tapa","c":{"a":2,"c":-16777216,"d":3.0,"k":[{"x":10,"y":20,"p":0.4}]},"e":239}]""",
            )
            record(out, "COVER", "1_2_-1_natural3_coverThumbnail.jpg", "chica")
            record(out, "COVER", "1_2_-1_natural3.jpg", "tapa grande")
            if (pdf != null) record(out, "PDF", "Jnotas n2.pdf", String(pdf, Charsets.ISO_8859_1))
            out.writeInt(0)
        }
        return jzip.toByteArray()
    }

    @Test
    fun parsesNotebookWithCover() {
        val note = JnotesFile.parse(notebook())
        assertEquals(JnotesFile.NOTEBOOK, note.kind)
        assertEquals("carpeta-1", note.folder)
        assertEquals("PageBg/Beige_Dotted_Paper", note.paper)
        assertEquals(-198413, note.paperColor)
        // La tapa ("c": 0) primero aunque venga después.
        assertEquals("tapa grande", String(note.pages[0].cover!!))
        assertNull(note.pages[1].cover)
        // Puntos tal cual: r/s del pincel ya están aplicados.
        assertEquals(100f, note.pages[1].strokes.single().xs[0])
        assertEquals(200f, note.pages[1].strokes.single().ys[0])
        assertEquals(10f, note.pages[0].strokes.single().xs[0])
    }

    @Test
    fun importsNotebookAsA4Pages() {
        val media = Files.createTempDirectory("medios").toFile()
        val r = JnotesImporter.import(ByteArrayInputStream(notebook()), "id", "x", 2f, media, time = 1234L)
        val n = r.note
        assertEquals(PageSize.A4, n.pages.size)
        assertEquals(2, n.pages.minPages)
        assertEquals(Background.DOTS, n.background)
        assertEquals(0xFFFCF8F3.toInt(), n.paperColor)
        assertEquals(1234L, n.modified)
        assertEquals("carpeta-1", r.folder)
        assertTrue(r.pdfPages.isEmpty())
        // La tapa, como imagen fija del tamaño de la primera hoja.
        val cover = n.images.single()
        assertTrue(cover.locked)
        assertEquals(n.pages.width, cover.width, 0.5f)
        assertEquals(n.pages.pageTop(0), cover.top, 0.01f)
        // Un trazo de la segunda hoja: 150 ppp de Jnotes a 160 dp por pulgada.
        val s = minOf(n.pages.width / 1240f, n.pages.height / 1754f)
        val x0 = n.pages.left + (n.pages.width - 1240f * s) / 2
        val b = n.strokes.map { it.left }.sorted()
        assertEquals(x0 + 10f * s - 3f * s, b[0], 0.01f)
        val second = n.strokes.first { it.top > n.pages.pageTop(1) - 10f }
        assertEquals(x0 + 100f * s - 3f * s, second.left, 0.01f)
        assertEquals(n.pages.pageTop(1) + 200f * s - 3f * s, second.top, 0.01f)
        media.deleteRecursively()
    }

    @Test
    fun copiesPdfOfPdfNote() {
        val media = Files.createTempDirectory("medios").toFile()
        val out = File(media, "nota.pdf")
        val pdf = "%PDF-1.4 hojas".toByteArray()
        val r = JnotesImporter.import(ByteArrayInputStream(notebook(kind = 3, pdf = pdf)), "id", "x", 2f, media, out)
        assertEquals(pdf.toList(), out.readBytes().toList())
        assertEquals(listOf(0, 1), r.pdfPages.map { it.index })
        assertEquals(Background.BLANK, r.note.background)
        assertEquals(r.note.pages.pageTop(1), r.pdfPages[1].top, 0.01f)
        media.deleteRecursively()
    }

    @Test
    fun pageSizesFromJnotes() {
        assertEquals(PageLayout(PageSize.A4, false), JnotesImporter.layoutFor(1240f, 1754f))
        assertEquals(PageLayout(PageSize.A4, true), JnotesImporter.layoutFor(1754f, 1240f))
        assertEquals(PageLayout(PageSize.A3, true), JnotesImporter.layoutFor(2480f, 1754f))
        assertEquals(PageLayout(PageSize.LETTER, false), JnotesImporter.layoutFor(1275f, 1650f))
        val custom = JnotesImporter.layoutFor(1500f, 1125f)
        assertEquals(PageSize.CUSTOM, custom.size)
        assertEquals(254f, custom.customWidth, 0.01f)
        assertEquals(190.5f, custom.customHeight, 0.01f)
        assertEquals(Background.GRID, JnotesImporter.background("PageBg/White_Graph_Paper"))
        assertEquals(Background.GRID_LARGE, JnotesImporter.background("PageBg/White_Wide_Grid_Paper"))
        assertEquals(Background.LINES, JnotesImporter.background("PageBg/Beige_Law_Paper"))
        assertEquals(Background.BLANK, JnotesImporter.background("/storage/emulated/0/x.jpg"))
        assertNull(JnotesImporter.paperColor(-1))
    }

    @Test
    fun pageRectUsesSheetWhenContentFits() {
        val page = JnotesFile.parse(sample()).pages[0]
        assertEquals(JnotesImporter.Rect(0f, 0f, 1240f, 1754f), JnotesImporter.pageRect(page))
    }

    @Test
    fun pageRectUsesContentOnInfiniteCanvas() {
        val s = JnotesFile.JStroke(1, 0, 2f, 0, floatArrayOf(-50f, 3000f), floatArrayOf(10f, 20f), floatArrayOf(0f, 0f))
        val page = JnotesFile.JPage(1240f, 1754f, listOf(s))
        assertEquals(JnotesImporter.Rect(-52f, 8f, 3002f, 22f), JnotesImporter.pageRect(page))
        assertNull(JnotesImporter.pageRect(JnotesFile.JPage(1240f, 1754f, emptyList())))
    }
}
