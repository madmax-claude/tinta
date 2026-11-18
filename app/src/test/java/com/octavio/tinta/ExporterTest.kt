package com.octavio.tinta

import com.octavio.tinta.export.Exporter
import com.octavio.tinta.export.Render
import com.octavio.tinta.model.Background
import com.octavio.tinta.model.Figure2D
import com.octavio.tinta.model.FigureKind
import com.octavio.tinta.model.InkStroke
import com.octavio.tinta.model.Media
import com.octavio.tinta.model.MediaKind
import com.octavio.tinta.model.Note
import com.octavio.tinta.model.NoteImage
import com.octavio.tinta.model.NoteText
import com.octavio.tinta.model.PageLayout
import com.octavio.tinta.model.PageSize
import com.octavio.tinta.model.Sensitivity
import com.octavio.tinta.model.Tool
import com.octavio.tinta.model.Viewport
import com.octavio.tinta.storage.HandText
import com.octavio.tinta.storage.NoteFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max

class ExporterTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun fileNameStripsCharactersFilesCannotUse() {
        assertEquals("nota", Render.fileName(""))
        assertEquals("nota", Render.fileName("   "))
        assertEquals("Clase", Render.fileName("  Clase  "))
        assertEquals("a_b_c_d", Render.fileName("a/b\\c:d"))
        assertEquals("x_y_z", Render.fileName("x*y?z"))
        assertEquals("_hola_", Render.fileName("\"hola\""))
        assertEquals("a_b_c_d", Render.fileName("a<b>c|d"))
        assertEquals("linea_siguiente", Render.fileName("linea\nsiguiente"))
        val long = "á".repeat(90)
        assertEquals(80, Render.fileName(long).length)
        assertEquals("á".repeat(80), Render.fileName(long))
    }

    @Test
    fun textListsLinesInReadingOrderWithPageHeaders() {
        val pages = PageLayout(PageSize.A4)
        val note = Note("id", "Física", pages = pages)
        val lines = listOf(
            line(pages, 1, 80f, 10f, "segunda"),
            line(pages, 0, 100f, 40f, "después"),
            line(pages, 0, 100f, 10f, "antes"),
            HandText.Line("vacio", listOf("   "), 0f, 0f, 10f, 10f),
            HandText.Line("blanco", listOf(""), 0f, 1f, 10f, 10f),
            line(pages, 0, 200f, 10f, "  mejor  ", "otra"),
        )
        assertEquals(
            "Física\n\n— Hoja 1 —\nantes\ndespués\nmejor\n\n— Hoja 2 —\nsegunda\n",
            Exporter.text(note, lines),
        )
    }

    @Test
    fun textOfAnInfiniteNoteHasNoPageHeaders() {
        val note = Note("id", "Infinita")
        val lines = listOf(
            HandText.Line("b", listOf("abajo"), 0f, 40f, 10f, 50f),
            HandText.Line("a", listOf("arriba"), 0f, 10f, 10f, 20f),
        )
        assertEquals("Infinita\n\narriba\nabajo\n", Exporter.text(note, lines))
        assertEquals("Vacía\n\n", Exporter.text(Note("id", "Vacía", pages = PageLayout(PageSize.A5)), emptyList()))
    }

    @Test
    fun sheetsToExportKeepsTheRequestedSubset() {
        assertEquals(listOf(0, 1, 2), Render.sheetsToExport(3, null))
        assertEquals(listOf(2, 0), Render.sheetsToExport(4, listOf(2, 0, 2, 9, -1)))
        assertEquals(listOf(1), Render.sheetsToExport(3, listOf(1, 1)))
        // Nada de lo pedido existe: se exportan todas, como si no se hubiera elegido.
        assertEquals(listOf(0, 1, 2), Render.sheetsToExport(3, listOf(8, -1)))
        assertEquals(listOf(0, 1, 2), Render.sheetsToExport(3, emptyList()))
    }

    @Test
    fun a4PdfPageIs595PointsWide() {
        val (width, height) = Render.pdfPagePoints(PageLayout(PageSize.A4))
        assertEquals(595, width)
        assertEquals(841, height)
        val wide = Render.pdfPagePoints(PageLayout(PageSize.A4, landscape = true))
        assertEquals(height, wide.first)
        assertEquals(width, wide.second)
    }

    @Test
    fun pngExportDecodesImagesAtPageResolution() {
        val a4 = PageLayout(PageSize.A4)
        assertEquals(2116, Exporter.pngPageWidth(a4))
        assertEquals(2992, Render.pagePixelHeight(a4, 2116))
        // Una A4 a ~1.6 px/dp: el lado largo queda en 2992 px, cerca de los 3000 del PDF.
        // 256 es la tira de miniaturas; 2048 es el piso (el de un recorte).
        assertEquals(2992, Exporter.pagePngImageSide(a4))
        assertEquals(2048, Exporter.pagePngImageSide(PageLayout(PageSize.CUSTOM, customWidth = 20f, customHeight = 30f)))
        assertEquals(4000, Exporter.areaPngImageSide(2000f, 1000f, 2f))
        assertEquals(Render.AREA_IMAGE_SIDE, Render.pngImageSide(100, 80))
        assertEquals(4096, Render.pngImageSide(4096, 2000))
        assertTrue(Exporter.pagePngImageSide(a4) > Render.THUMB_IMAGE_SIDE)
        assertEquals(256, Render.THUMB_IMAGE_SIDE)
        assertEquals(2048, Render.AREA_IMAGE_SIDE)
        assertEquals(3000, Render.PDF_IMAGE_SIDE)
        assertEquals(
            max(Exporter.pngPageWidth(a4), Render.pagePixelHeight(a4, Exporter.pngPageWidth(a4)))
                .coerceAtLeast(Render.AREA_IMAGE_SIDE),
            Exporter.pagePngImageSide(a4),
        )
    }

    @Test
    fun tintaBundleRoundTrip() {
        val src = DirNotes(tmp.newFolder("src"))
        val id = "origen"
        val media = src.mediaDir(id)
        val photo = File(media, "foto.img").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val audio = File(media, "voz.m4a").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        File(media, "suelto.bin").writeBytes(byteArrayOf(6))
        File(media, "basura.tmp").writeBytes(byteArrayOf(0))
        val thumb = byteArrayOf(9, 9, 9)
        src.thumbnail(id).writeBytes(thumb)
        val stroke = InkStroke.unbuilt(
            Tool.PEN, 0xFF1F1F1F.toInt(), 2f, 0.1f,
            1f, 2f, 30f, 40f, byteArrayOf(1, 2, 3, 4),
        )
        val image = NoteImage(10f, 20f, 300f, 200f, 15f, photo, locked = true)
        val text = NoteText(12f, 40f, 200f, 30f, "integral", 18f, 0xFF1E5BD8.toInt())
        val figure = Figure2D(4f, 8f, 120f, 80f, FigureKind.HEXAGON, 0xFF1E5BD8.toInt(), filled = true)
        val voice = Media(10f, 400f, 260f, 64f, MediaKind.AUDIO, audio, null, 4200)
        val note = Note(
            id, "Clase de física", Background.LINES, 10L, 20L,
            Viewport(1f, 2f, 1.25f, 100f, 200f),
            PageLayout(PageSize.A4, left = 8f, top = 4f, minPages = 2),
            mutableListOf(stroke), mutableListOf(image), mutableListOf(text), mutableListOf(figure, voice),
            paperColor = 0xFFFFF6D5.toInt(),
        )
        writeStoredNote(src.file(id), note)

        val bundle = Exporter.writeBundle(tmp.newFolder("out"), src, note)
        assertEquals("Clase de física.tinta", bundle.name)
        assertTrue(Exporter.isBundle(bundle.name) { bundle.inputStream() })
        assertFalse(Exporter.isBundle("otro.zip") { null })

        val dst = DirNotes(tmp.newFolder("dst"))
        val back = bundle.inputStream().use { Exporter.readBundle(dst, it) }

        assertNotEquals(note.id, back.id)
        assertEquals(note.title, back.title)
        assertEquals(note.background, back.background)
        assertEquals(note.created, back.created)
        assertEquals(note.modified, back.modified)
        assertEquals(note.viewport, back.viewport)
        assertEquals(note.pages, back.pages)
        assertEquals(note.paperColor, back.paperColor)
        assertEquals(1, back.strokes.size)
        val s = back.strokes.single()
        assertEquals(stroke.tool, s.tool)
        assertEquals(stroke.color, s.color)
        assertEquals(stroke.size, s.size)
        assertEquals(stroke.epsilon, s.epsilon)
        assertEquals(stroke.left, s.left)
        assertEquals(stroke.top, s.top)
        assertEquals(stroke.right, s.right)
        assertEquals(stroke.bottom, s.bottom)
        assertEquals(stroke.sensitivity, s.sensitivity)
        assertEquals(0L, s.group)
        assertArrayEquals(stroke.encoded(), s.encoded())
        val img = back.images.single()
        assertEquals(image.left, img.left)
        assertEquals(image.top, img.top)
        assertEquals(image.width, img.width)
        assertEquals(image.height, img.height)
        assertEquals(image.rotation, img.rotation)
        assertEquals(image.locked, img.locked)
        assertEquals("foto.img", img.file.name)
        assertArrayEquals(photo.readBytes(), img.file.readBytes())
        val t = back.texts.single()
        assertEquals(text.left, t.left)
        assertEquals(text.top, t.top)
        assertEquals(text.width, t.width)
        assertEquals(text.height, t.height)
        assertEquals(text.text, t.text)
        assertEquals(text.size, t.size)
        assertEquals(text.color, t.color)
        assertEquals(text.font, t.font)
        assertEquals(text.clip, t.clip)
        assertEquals(figure, back.widgets[0])
        val heard = back.widgets[1] as Media
        assertEquals(voice.kind, heard.kind)
        assertEquals(voice.durationMs, heard.durationMs)
        assertEquals("voz.m4a", heard.file.name)
        assertNull(heard.thumb)
        assertArrayEquals(audio.readBytes(), heard.file.readBytes())
        assertArrayEquals(byteArrayOf(6), File(dst.mediaDir(back.id), "suelto.bin").readBytes())
        assertFalse(File(dst.mediaDir(back.id), "basura.tmp").exists())
        assertArrayEquals(thumb, dst.thumbnail(back.id).readBytes())
    }

    @Test(expected = IllegalStateException::class)
    fun importRejectsAZipThatIsNotTinta() {
        val zip = tmp.newFile("otro.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("hola.txt"))
            z.write("no".toByteArray())
            z.closeEntry()
        }
        zip.inputStream().use { Exporter.readBundle(DirNotes(tmp.newFolder("rej")), it) }
    }

    private fun line(pages: PageLayout, page: Int, top: Float, left: Float, vararg texts: String): HandText.Line {
        val y = pages.pageTop(page) + top
        return HandText.Line("k$top$left", texts.toList(), left, y, left + 80f, y + 20f)
    }

    /** El mismo archivo que escribe NoteStore, sin el sync del descriptor (eso es de Android). */
    private fun writeStoredNote(target: File, note: Note) {
        val richText = note.texts.any { it.font != NoteText.DEFAULT_FONT || it.clip }
        val version = NoteFormat.versionFor(
            note.strokes.any { it.group != 0L }, note.pages.horizontal, richText, note.pages.hasSheetMeta(),
        )
        val header = NoteFormat.Header(
            note.title, note.background, note.created, note.modified, note.viewport,
            note.strokes.size, note.pages, version, note.paperColor,
        )
        val writing = File(target.path + ".writing")
        val fos = FileOutputStream(writing)
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            val bos = BufferedOutputStream(fos, 1 shl 16)
            NoteFormat.writeHeader(DataOutputStream(bos), header)
            val compressed = DeflaterOutputStream(bos, deflater, 1 shl 16)
            val out = DataOutputStream(BufferedOutputStream(compressed, 1 shl 16))
            for (s in note.strokes) {
                NoteFormat.writeStroke(
                    out,
                    NoteFormat.RawStroke(
                        NoteFormat.packTool(s.tool.id, s.sensitivity), s.color, s.size, s.epsilon,
                        s.left, s.top, s.right, s.bottom, s.encoded(),
                    ),
                )
            }
            NoteFormat.writeElements(out, note.images, note.texts, note.widgets, version >= 11)
            if (version >= 9) NoteFormat.writeGroups(out, note.strokes.map { it.group })
            out.flush()
            compressed.finish()
            bos.flush()
        } finally {
            fos.close()
            deflater.end()
        }
        check(writing.renameTo(target))
    }

    private class DirNotes(private val root: File) : Exporter.NoteFiles {
        override fun newId(): String = java.util.UUID.randomUUID().toString()
        override fun file(id: String) = File(root, "$id.tinta")
        override fun thumbnail(id: String) = File(root, "$id.png")
        override fun mediaDir(id: String) = File(root, "$id.media").apply { mkdirs() }
        override fun delete(id: String) {
            file(id).delete()
            File(file(id).path + ".tmp").delete()
            thumbnail(id).delete()
            File(root, "$id.media").deleteRecursively()
        }
    }
}
