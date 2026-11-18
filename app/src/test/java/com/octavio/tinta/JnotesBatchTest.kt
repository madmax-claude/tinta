package com.octavio.tinta

import com.octavio.tinta.storage.JnotesBatch
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** El orden del lote de Jnotes: qué queda pendiente, qué archivo se conserva y cómo se cierra. */
class JnotesBatchTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun pendingOnlyWhileTheOrderFileExists() {
        val dir = tmp.newFolder("importar-jnotes")
        assertFalse(JnotesBatch.pending(dir))
        File(dir, "orden-hecho.json").writeText("{}")
        assertFalse(JnotesBatch.pending(dir))
        File(dir, "orden.json").writeText("{}")
        assertTrue(JnotesBatch.pending(dir))
    }

    @Test
    fun alreadyImportedIsNotAFailureEvenIfTheSourceIsGone() {
        assertEquals(JnotesBatch.Plan.SKIP, JnotesBatch.plan(alreadyThere = true, sourceExists = true))
        assertEquals(JnotesBatch.Plan.SKIP, JnotesBatch.plan(alreadyThere = true, sourceExists = false))
        assertEquals(JnotesBatch.Plan.IMPORT, JnotesBatch.plan(alreadyThere = false, sourceExists = true))
        assertEquals(JnotesBatch.Plan.MISSING, JnotesBatch.plan(alreadyThere = false, sourceExists = false))
    }

    @Test
    fun sourceIsKeptOnlyForNotesThatWillBeRetried() {
        assertFalse(JnotesBatch.keepsSource(JnotesBatch.Plan.SKIP, imported = false))
        assertFalse(JnotesBatch.keepsSource(JnotesBatch.Plan.SKIP, imported = true))
        assertTrue(JnotesBatch.keepsSource(JnotesBatch.Plan.MISSING, imported = false))
        assertTrue(JnotesBatch.keepsSource(JnotesBatch.Plan.MISSING, imported = true))
        assertFalse(JnotesBatch.keepsSource(JnotesBatch.Plan.IMPORT, imported = true))
        assertTrue(JnotesBatch.keepsSource(JnotesBatch.Plan.IMPORT, imported = false))
    }

    @Test
    fun successRenamesTheOrderWithoutRewritingIt() {
        val dir = tmp.newFolder("lote")
        val text = """
            {"carpetas":[{"id":"f","nombre":"Física","padre":null}],"notas":[{"archivo":"a.Jnotes","id":"a"}]}
        """.trimIndent() + "\n"
        val order = File(dir, "orden.json")
        order.writeText(text)
        JnotesBatch.settle(dir, JSONObject(text), emptyList())
        assertFalse(JnotesBatch.pending(dir))
        assertFalse(order.exists())
        assertEquals(text, File(dir, "orden-hecho.json").readText())
        assertFalse(File(dir, "orden.json.tmp").exists())
    }

    @Test
    fun failuresStayPendingWithOnlyThoseNotesAndTheirFiles() {
        val dir = tmp.newFolder("lote")
        val original = """
            {
             "carpetas": [
              {"id": "fac", "nombre": "Facultad", "padre": null},
              {"id": "fis", "nombre": "Física", "padre": "fac"}
             ],
             "notas": [
              {"archivo": "a.Jnotes", "id": "a", "titulo": "Álgebra", "carpeta": "fis", "modificada": 1700000000000},
              {"archivo": "b.Jnotes", "id": "b", "titulo": "Óptica", "carpeta": null, "modificada": 1700000001000},
              {"archivo": "c.Jnotes", "id": "c", "titulo": "Cálculo", "carpeta": "fac", "modificada": 0},
              {"archivo": "d.Jnotes", "id": "d", "titulo": "Ya estaba", "carpeta": "fis", "modificada": 5}
             ]
            }
        """.trimIndent()
        File(dir, "orden.json").writeText(original)
        File(dir, "a.Jnotes").writeText("algebra")
        File(dir, "b.Jnotes").writeText("optica-rota")
        File(dir, "d.Jnotes").writeText("duplicada")
        // c.Jnotes no está: es el caso «falta el archivo».

        val order = JSONObject(original)
        val notes = order.getJSONArray("notas")
        val retry = ArrayList<JSONObject>()
        val outcomes = listOf(
            JnotesBatch.Plan.IMPORT to true, // a, importada
            JnotesBatch.Plan.IMPORT to false, // b, falló y el archivo tiene que quedar
            JnotesBatch.Plan.MISSING to false, // c, falta el archivo
            JnotesBatch.Plan.SKIP to false, // d, ya estaba
        )
        for (i in outcomes.indices) {
            val note = notes.getJSONObject(i)
            val (plan, imported) = outcomes[i]
            JnotesBatch.commitNote(File(dir, note.getString("archivo")), note, plan, imported, retry)
        }
        val reasons = listOf("Óptica: zip dañado", "Cálculo: falta el archivo")
        JnotesBatch.settle(dir, order, retry)

        assertTrue(JnotesBatch.pending(dir))
        assertFalse(File(dir, "orden-hecho.json").exists())
        assertFalse(File(dir, "orden.json.tmp").exists())
        assertFalse(File(dir, "a.Jnotes").exists())
        assertEquals("optica-rota", File(dir, "b.Jnotes").readText())
        assertFalse(File(dir, "c.Jnotes").exists())
        assertFalse(File(dir, "d.Jnotes").exists())

        val again = JSONObject(File(dir, "orden.json").readText())
        val left = again.getJSONArray("notas")
        assertEquals(2, left.length())
        assertEquals("b", left.getJSONObject(0).getString("id"))
        assertEquals("Óptica", left.getJSONObject(0).getString("titulo"))
        assertEquals("b.Jnotes", left.getJSONObject(0).getString("archivo"))
        assertTrue(left.getJSONObject(0).isNull("carpeta"))
        assertEquals(1700000001000L, left.getJSONObject(0).getLong("modificada"))
        assertEquals("c", left.getJSONObject(1).getString("id"))
        assertEquals("Cálculo", left.getJSONObject(1).getString("titulo"))
        assertEquals("fac", left.getJSONObject(1).getString("carpeta"))

        val folders = again.getJSONArray("carpetas")
        assertEquals(2, folders.length())
        assertEquals("fac", folders.getJSONObject(0).getString("id"))
        assertEquals("Facultad", folders.getJSONObject(0).getString("nombre"))
        assertTrue(folders.getJSONObject(0).isNull("padre"))
        assertEquals("fis", folders.getJSONObject(1).getString("id"))
        assertEquals("Física", folders.getJSONObject(1).getString("nombre"))
        assertEquals("fac", folders.getJSONObject(1).getString("padre"))

        val status = JnotesBatch.closing(1, 1, reasons)
        assertTrue(status.startsWith("FIN:1 importadas, 1 ya estaban, 2 con error\n"))
        assertTrue(status.contains("Quedan para reintentar:"))
        assertTrue(status.contains("- Óptica: zip dañado"))
        assertTrue(status.contains("- Cálculo: falta el archivo"))

        // Un intento posterior, ya con esas dos importadas, cierra el lote como uno que salió bien.
        JnotesBatch.settle(dir, again, emptyList())
        assertFalse(JnotesBatch.pending(dir))
        assertTrue(File(dir, "orden-hecho.json").exists())
        assertFalse(File(dir, "orden.json").exists())
    }

    @Test
    fun aCleanBatchClosesWithTheSameStatusLineAsBefore() {
        assertEquals(
            "FIN:4 importadas, 2 ya estaban, 0 con error",
            JnotesBatch.closing(4, 2, emptyList()),
        )
    }

    @Test
    fun everyNoteFailingKeepsTheWholeOrderPending() {
        val dir = tmp.newFolder("lote")
        val text = """{"carpetas":[],"notas":[{"archivo":"a.Jnotes","id":"a","titulo":"Una"},{"archivo":"b.Jnotes","id":"b","titulo":"Otra"}]}"""
        File(dir, "orden.json").writeText(text)
        File(dir, "a.Jnotes").writeText("a")
        File(dir, "b.Jnotes").writeText("b")
        val order = JSONObject(text)
        val notes = order.getJSONArray("notas")
        val retry = ArrayList<JSONObject>()
        for (i in 0 until notes.length()) {
            val note = notes.getJSONObject(i)
            JnotesBatch.commitNote(File(dir, note.getString("archivo")), note, JnotesBatch.Plan.IMPORT, imported = false, retry)
        }
        JnotesBatch.settle(dir, order, retry)
        assertTrue(JnotesBatch.pending(dir))
        val left = JSONObject(File(dir, "orden.json").readText()).getJSONArray("notas")
        assertEquals(listOf("a", "b"), (0 until left.length()).map { left.getJSONObject(it).getString("id") })
        assertEquals("a", File(dir, "a.Jnotes").readText())
        assertEquals("b", File(dir, "b.Jnotes").readText())
    }
}
