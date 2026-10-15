package com.octavio.tinta

import com.octavio.tinta.jnotes.JnotesFile
import com.octavio.tinta.jnotes.JnotesImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
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
