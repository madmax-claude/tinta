package com.octavio.tinta

import com.octavio.tinta.model.Background
import com.octavio.tinta.model.Figure2D
import com.octavio.tinta.model.FigureKind
import com.octavio.tinta.model.Note
import com.octavio.tinta.model.NoteImage
import com.octavio.tinta.model.Solid
import com.octavio.tinta.model.SolidKind
import com.octavio.tinta.model.Viewport
import com.octavio.tinta.model.Widget
import com.octavio.tinta.storage.Imports
import com.octavio.tinta.storage.NoteFormat
import com.octavio.tinta.storage.NoteStore
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Una nota con la cabecera rota sigue en la lista, y un widget que esta versión no entiende
 * se reescribe con los mismos bytes. Si no, el archivo desaparece de la lista o el próximo
 * guardado lo pierde.
 */
class DamagedNoteTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun unreadableHeaderStaysInTheListAndIsNotDeleted() {
        val dir = tmp.newFolder("notes")
        val header = NoteFormat.Header("Clase", Background.GRID, 1L, 50L, Viewport(), 0)
        DataOutputStream(FileOutputStream(File(dir, "ok.tinta"))).use { NoteFormat.writeHeader(it, header) }
        val bad = File(dir, "rota.tinta").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
        bad.setLastModified(80L)
        File(dir, "nota.tinta.tmp").writeBytes(byteArrayOf(9))
        File(dir, "carpeta.tinta").mkdirs()
        val before = bad.readBytes()

        val listed = NoteStore.readList(dir) { id -> if (id == "ok") "fisica" else null }
        val again = NoteStore.readList(dir) { null }

        assertEquals(setOf("ok", "rota"), listed.map { it.id }.toSet())
        val ok = listed.first { it.id == "ok" }
        assertFalse(ok.damaged)
        assertEquals("Clase", ok.title)
        assertEquals(50L, ok.modified)
        assertEquals(0, ok.strokeCount)
        assertEquals("fisica", ok.folder)
        val damaged = listed.first { it.id == "rota" }
        assertTrue(damaged.damaged)
        assertEquals("", damaged.title)
        assertEquals(0, damaged.strokeCount)
        assertNull(damaged.folder)
        assertEquals(bad.lastModified(), damaged.modified)
        assertArrayEquals(before, bad.readBytes())
        assertTrue(bad.exists())
        assertEquals(setOf("ok", "rota"), again.map { it.id }.toSet())
        assertTrue(again.first { it.id == "rota" }.damaged)
        assertTrue(File(dir, "nota.tinta.tmp").exists())
        assertTrue(File(dir, "carpeta.tinta").isDirectory)
    }

    @Test
    fun truncatedBodyIsNotRewritten() {
        val file = File(tmp.newFolder("corta"), "id.tinta")
        val header = NoteFormat.Header("Cortada", Background.LINES, 1L, 2L, Viewport(), 5)
        DataOutputStream(FileOutputStream(file)).use { NoteFormat.writeHeader(it, header) }
        val before = file.readBytes()
        assertThrows(IOException::class.java) {
            NoteStore.readNote(file, "id", tmp.newFolder("media"))
        }
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun knownWidgetRoundTripsAndUnknownJsonIsKeptVerbatim() {
        val figure = Figure2D(4f, 8f, 120f, 80f, FigureKind.HEXAGON, 0xFF1E5BD8.toInt(), filled = true)
        val cube = Solid(0f, 0f, 10f, 10f, SolidKind.CUBE, 1f, 1f, 1f, 30f, 20f, 1, true, true)
        assertEquals(cube, Widget.fromJson(cube.toJson(), File(".")))
        val unknownKind = """{"type":"solid","kind":"dodecaedro","left":0,"top":0,"width":10,"height":10,"a":1,"b":1,"c":1,"yaw":0,"pitch":0,"color":1,"hidden":true,"shaded":true}"""
            .toByteArray(Charsets.UTF_8)
        val extra = JSONObject(figure.toJson().toString()).put("nota", "de una versión nueva").toString().toByteArray(Charsets.UTF_8)
        val broken = byteArrayOf('{'.code.toByte())
        assertNull(Widget.fromJson(JSONObject(String(unknownKind, Charsets.UTF_8)), File(".")))

        val dir = tmp.newFolder("nota")
        val file = File(dir, "n.tinta")
        val media = File(dir, "n.media")
        val note = Note("n", "Clase", created = 1L, modified = 2L)
        note.widgets.add(figure)
        note.preservedWidgets.add(unknownKind)
        note.preservedWidgets.add(extra)
        note.preservedWidgets.add(broken)
        NoteStore.writeNote(file, header(note), note.strokes, note.images, note.texts, note.widgets, note.preservedWidgets)

        val back = NoteStore.readNote(file, "n", media)
        assertEquals(listOf(figure), back.widgets)
        assertEquals(3, back.preservedWidgets.size)
        assertArrayEquals(unknownKind, back.preservedWidgets[0])
        assertArrayEquals(extra, back.preservedWidgets[1])
        assertArrayEquals(broken, back.preservedWidgets[2])
        assertTrue(back.widgets.none { it is Solid })

        back.title = "Clase 2"
        NoteStore.writeNote(
            file, header(back), back.strokes, back.images, back.texts, back.widgets, back.preservedWidgets,
        )
        val again = NoteStore.readNote(file, "n", media)
        assertEquals("Clase 2", again.title)
        assertEquals(listOf(figure), again.widgets)
        assertArrayEquals(unknownKind, again.preservedWidgets[0])
        assertArrayEquals(extra, again.preservedWidgets[1])
        assertArrayEquals(broken, again.preservedWidgets[2])
        assertTrue(String(again.preservedWidgets[1], Charsets.UTF_8).contains("\"nota\""))
    }

    @Test
    fun preservedWidgetsAreNotDroppedWhenReadingElements() {
        val raw = """{"type":"reloj","left":1,"top":2,"width":3,"height":4}""".toByteArray(Charsets.UTF_8)
        val read = readWidgets(raw)
        assertTrue(read.widgets.isEmpty())
        assertArrayEquals(raw, read.preserved.single())

        val written = ByteArrayOutputStream().also { buf ->
            DataOutputStream(buf).use { NoteFormat.writeElements(it, emptyList(), emptyList(), read.widgets, preserved = read.preserved) }
        }.toByteArray()
        val again = readWidgetsBytes(written)
        assertArrayEquals(raw, again.preserved.single())
    }

    @Test
    fun damagedTextOrWidgetLengthFailsInsteadOfDroppingTheRest() {
        assertThrows(IOException::class.java) { readTextWithBlobSize(-1) }
        val bytes = ByteArrayOutputStream().also { buf ->
            DataOutputStream(buf).use {
                it.writeInt(0)
                it.writeInt(0)
                it.writeInt(1)
                it.writeInt(-1)
            }
        }.toByteArray()
        assertThrows(IOException::class.java) {
            DataInputStream(ByteArrayInputStream(bytes)).use {
                NoteFormat.readElements(it, 8, File("."), mutableListOf(), mutableListOf(), mutableListOf()) { error("no") }
            }
        }
    }

    @Test
    fun unreadableWidgetsKeepTheirMediaFiles() {
        val dir = tmp.newFolder("medios")
        File(dir, "voz.m4a").writeBytes(byteArrayOf(1))
        File(dir, "otro.img").writeBytes(byteArrayOf(2))
        File(dir, Imports.ORIGINAL).writeBytes(byteArrayOf(3))
        File(dir, "parcial.tmp").writeBytes(byteArrayOf(4))
        val used = File(dir, "foto.img").apply { writeBytes(byteArrayOf(5)) }
        val note = Note("n", "t")
        note.images.add(NoteImage(0f, 0f, 1f, 1f, 0f, used))
        note.preservedWidgets.add(
            """{"type":"media","kind":"holograma","left":0,"top":0,"width":1,"height":1,"file":"voz.m4a","thumb":"","duration":1}"""
                .toByteArray(Charsets.UTF_8),
        )
        assertTrue(NoteStore.unusedMedia(note, dir).isEmpty())
        assertTrue(File(dir, "voz.m4a").exists())
        assertTrue(File(dir, "otro.img").exists())

        note.preservedWidgets.clear()
        val unused = NoteStore.unusedMedia(note, dir).map { it.name }
        // Sin JSON crudo ya se puede saber qué sobra: la foto pegada y el PDF original se quedan.
        assertEquals(setOf("otro.img", "voz.m4a"), unused.toSet())
        assertFalse(unused.contains("foto.img"))
        assertFalse(unused.contains(Imports.ORIGINAL))
        assertFalse(unused.contains("parcial.tmp"))
    }

    private fun header(note: Note) = NoteFormat.Header(
        note.title, note.background, note.created, note.modified, note.viewport, note.strokes.size, note.pages,
        paperColor = note.paperColor,
    )

    private fun readTextWithBlobSize(size: Int) {
        val bytes = ByteArrayOutputStream().also { buf ->
            DataOutputStream(buf).use {
                it.writeInt(0)
                it.writeInt(1)
                it.writeFloat(0f)
                it.writeFloat(0f)
                it.writeFloat(10f)
                it.writeFloat(10f)
                it.writeFloat(12f)
                it.writeInt(0)
                it.writeInt(size)
            }
        }.toByteArray()
        DataInputStream(ByteArrayInputStream(bytes)).use {
            NoteFormat.readElements(it, 8, File("."), mutableListOf(), mutableListOf(), mutableListOf()) { error("no") }
        }
    }

    private class Read(val widgets: List<Widget>, val preserved: List<ByteArray>)

    private fun readWidgets(vararg raw: ByteArray): Read {
        val bytes = ByteArrayOutputStream().also { buf ->
            DataOutputStream(buf).use {
                it.writeInt(0)
                it.writeInt(0)
                it.writeInt(raw.size)
                for (one in raw) {
                    it.writeInt(one.size)
                    it.write(one)
                }
            }
        }.toByteArray()
        return readWidgetsBytes(bytes)
    }

    private fun readWidgetsBytes(bytes: ByteArray): Read {
        val widgets = mutableListOf<Widget>()
        val preserved = mutableListOf<ByteArray>()
        DataInputStream(ByteArrayInputStream(bytes)).use {
            NoteFormat.readElements(it, 8, File("."), mutableListOf(), mutableListOf(), widgets, preserved) { error("no") }
        }
        return Read(widgets, preserved)
    }
}
