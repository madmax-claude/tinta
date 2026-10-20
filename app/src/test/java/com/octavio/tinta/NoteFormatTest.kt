package com.octavio.tinta

import com.octavio.tinta.model.Background
import com.octavio.tinta.model.PageLayout
import com.octavio.tinta.model.PageSize
import com.octavio.tinta.model.Viewport
import com.octavio.tinta.storage.NoteFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class NoteFormatTest {

    @Test
    fun roundTrip() {
        val header = NoteFormat.Header(
            "Análisis II — clase 3", Background.ERRORS, 1L, 2L, Viewport(-10f, 20f, 1.5f, 1472f, 1700f), 1,
            PageLayout(PageSize.CUSTOM, landscape = true, left = -48f, top = 12.5f, customWidth = 254f, customHeight = 190.5f, minPages = 7),
            paperColor = 0xFFFFF6D5.toInt(),
        )
        val stroke = NoteFormat.RawStroke(2, 0x66FFE600, 16f, 0.1f, -5f, 10f, 20f, 30.5f, byteArrayOf(1, 2, 3))
        val bytes = ByteArrayOutputStream().also { buf ->
            DataOutputStream(buf).use {
                NoteFormat.writeHeader(it, header)
                NoteFormat.writeStroke(it, stroke)
            }
        }.toByteArray()

        DataInputStream(ByteArrayInputStream(bytes)).use {
            assertEquals(header, NoteFormat.readHeader(it))
            val s = NoteFormat.readStroke(it)
            assertEquals(2, s.tool)
            assertEquals(0x66FFE600, s.color)
            assertEquals(16f, s.size)
            assertEquals(30.5f, s.bottom)
            assertArrayEquals(byteArrayOf(1, 2, 3), s.inputs)
        }
    }

    /** Imágenes como archivos y widgets como JSON (versión 4). */
    @Test
    fun elementsRoundTrip() {
        val dir = java.io.File("/tmp/medios")
        val image = com.octavio.tinta.model.NoteImage(1f, 2f, 30f, 40f, 15f, java.io.File(dir, "abc.img"), locked = true)
        val graph = com.octavio.tinta.model.Graph2D(0f, 0f, 600f, 400f, listOf("x^2", "sen(x)"), -5f, 5f, -1f, 1f, autoY = false, grid = true, color = 0xFF1E5BD8.toInt())
        val audio = com.octavio.tinta.model.Media(10f, 20f, 260f, 64f, com.octavio.tinta.model.MediaKind.AUDIO, java.io.File(dir, "voz.m4a"), null, 4200)
        val bytes = ByteArrayOutputStream().also { buf ->
            DataOutputStream(buf).use { NoteFormat.writeElements(it, listOf(image), emptyList(), listOf(graph, audio)) }
        }.toByteArray()
        val images = mutableListOf<com.octavio.tinta.model.NoteImage>()
        val widgets = mutableListOf<com.octavio.tinta.model.Widget>()
        DataInputStream(ByteArrayInputStream(bytes)).use {
            NoteFormat.readElements(it, NoteFormat.VERSION, dir, images, mutableListOf(), widgets) { error("no hay imágenes adentro") }
        }
        assertEquals("abc.img", images.single().file.name)
        assertEquals(true, images.single().locked)
        assertEquals(15f, images.single().rotation)
        assertEquals(graph, widgets[0])
        assertEquals(audio, widgets[1])
    }

    /** Una nota de la versión 2 (sin hojas) se sigue leyendo, como infinita. */
    @Test
    fun readsVersion2() {
        val bytes = ByteArrayOutputStream().also { buf ->
            DataOutputStream(buf).use {
                it.writeUTF("TINTA")
                it.writeInt(2)
                it.writeUTF("vieja")
                it.writeInt(Background.LINES.id)
                it.writeLong(1L)
                it.writeLong(2L)
                it.writeFloat(0f)
                it.writeFloat(0f)
                it.writeFloat(1f)
                it.writeInt(0)
            }
        }.toByteArray()
        val h = NoteFormat.readHeader(DataInputStream(ByteArrayInputStream(bytes)))
        assertEquals("vieja", h.title)
        assertEquals(PageLayout(), h.pages)
        assertEquals(2, h.version)
    }

    @Test(expected = IOException::class)
    fun rejectsOtherFiles() {
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).writeUTF("TRY") }.toByteArray()
        NoteFormat.readHeader(DataInputStream(ByteArrayInputStream(bytes)))
    }

    @Test
    fun withoutPaperColorReadsNull() {
        val header = NoteFormat.Header("Sin color", Background.GRID, 1L, 2L, Viewport(), 0)
        val bytes = ByteArrayOutputStream().also { buf -> DataOutputStream(buf).use { NoteFormat.writeHeader(it, header) } }.toByteArray()
        val back = DataInputStream(ByteArrayInputStream(bytes)).use { NoteFormat.readHeader(it) }
        assertEquals(null, back.paperColor)
        assertEquals(header, back)
    }

    @Test
    fun lineGroupsRoundTrip() {
        val groups = listOf(0L, 42L, 42L, 0L, -7L)
        val bytes = ByteArrayOutputStream().also { buf -> DataOutputStream(buf).use { NoteFormat.writeGroups(it, groups) } }.toByteArray()
        val back = DataInputStream(ByteArrayInputStream(bytes)).use { NoteFormat.readGroups(it, groups.size) }
        assertEquals(mapOf(1 to 42L, 2 to 42L, 4 to -7L), back)
    }
}
