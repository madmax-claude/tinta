package com.octavio.tinta

import com.octavio.tinta.storage.Library
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LibraryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, "carpetas.json")

    @Test
    fun foldersAndPlacementSurviveReload() {
        val lib = Library(file())
        val math = lib.create("Análisis")
        val physics = lib.create("Física")
        lib.move("nota1", math.id)
        lib.move("nota2", physics.id)
        lib.rename(physics.id, "Física II")

        val again = Library(file())
        assertEquals(listOf("Análisis", "Física II"), again.folders().map { it.name })
        assertEquals(math.id, again.folderOf("nota1"))
        assertEquals(physics.id, again.folderOf("nota2"))
    }

    @Test
    fun deletingFolderLeavesNotesWithoutFolder() {
        val lib = Library(file())
        val f = lib.create("Borrar")
        lib.move("nota", f.id)
        lib.delete(f.id)
        assertNull(lib.folderOf("nota"))
        assertNull(Library(file()).folderOf("nota"))
    }

    @Test
    fun movingToUnknownFolderClearsPlacement() {
        val lib = Library(file())
        val f = lib.create("A")
        lib.move("nota", f.id)
        lib.move("nota", "no-existe")
        assertNull(lib.folderOf("nota"))
        lib.move("nota", f.id)
        lib.forget("nota")
        assertNull(lib.folderOf("nota"))
    }

    @Test
    fun brokenFileStartsEmpty() {
        // Se ve vacía en memoria, pero el archivo dañado no se pisa: si no, el próximo
        // create/move lo reemplazaría por un árbol vacío y se perderían las carpetas.
        val broken = "{ esto no es json"
        file().writeText(broken)
        val lib = Library(file())
        assertEquals(0, lib.folders().size)
        val denied = assertThrows(IllegalStateException::class.java) { lib.create("Nueva") }
        assertTrue(denied.message!!.contains("no se reescribe"))
        assertThrows(IllegalStateException::class.java) { lib.adopt("x", "X") }
        lib.move("nota", null)
        lib.move("nota", "cualquiera")
        lib.forget("nota")
        lib.rename("no", "Tampoco")
        lib.delete("no")
        assertEquals(emptySet<String>(), lib.deleteTree("no"))
        assertEquals(false, lib.moveFolder("no", null))
        assertEquals(broken, file().readText())
        assertEquals(0, Library(file()).folders().size)
        assertFalse(File(file().path + ".tmp").exists())
    }

    @Test
    fun wrongShapeIsNotTreatedAsAnEmptyLibrary() {
        val broken = """{"carpetas":"no","notas":[]}"""
        file().writeText(broken)
        val lib = Library(file())
        assertEquals(0, lib.folders().size)
        assertThrows(IllegalStateException::class.java) { lib.create("A") }
        assertEquals(broken, file().readText())
    }

    @Test
    fun emptyButValidFileCanStillGrow() {
        file().writeText("""{"carpetas":[],"notas":{}}""")
        val lib = Library(file())
        lib.create("A")
        assertEquals(listOf("A"), Library(file()).folders().map { it.name })
    }

    @Test
    fun lastGoodBakSurvivesACutFile() {
        val lib = Library(file())
        val a = lib.create("Análisis")
        val termo = lib.create("Termo", a.id)
        lib.move("n1", termo.id)
        val bak = File(file().path + ".bak")
        assertEquals(file().readText(), bak.readText())
        assertFalse(File(file().path + ".tmp").exists())

        val good = bak.readText()
        file().writeText(good.take(12))
        val recovered = Library(file())
        assertEquals(listOf("Análisis", "Termo"), recovered.path(termo.id).map { it.name })
        assertEquals(termo.id, recovered.folderOf("n1"))
        assertEquals(good, bak.readText())

        val quimica = recovered.create("Química")
        assertEquals(file().readText(), bak.readText())
        assertTrue(file().readText().contains("Química"))
        file().writeText("")
        val again = Library(file())
        assertEquals(quimica.id, again.folders().first { it.name == "Química" }.id)
        assertEquals(listOf("Análisis", "Termo"), again.path(termo.id).map { it.name })
        assertEquals(termo.id, again.folderOf("n1"))
    }

    @Test
    fun failedWriteRollsBackAndSurfaces() {
        val parent = File(tmp.root, "no-es-carpeta")
        parent.writeText("x")
        val f = File(parent, "carpetas.json")
        val lib = Library(f)
        assertThrows(IOException::class.java) { lib.create("A") }
        assertEquals(0, lib.folders().size)
        assertFalse(f.exists())
        assertFalse(File(f.path + ".tmp").exists())
    }

    @Test
    fun failedRenameKeepsMemoryAndBak() {
        val lib = Library(file())
        val a = lib.create("A")
        lib.move("n", a.id)
        val bak = File(file().path + ".bak")
        val bakText = bak.readText()
        check(file().delete())
        check(file().mkdir())
        File(file(), "bloqueo").writeText("x")
        val denied = assertThrows(IllegalStateException::class.java) { lib.create("B") }
        assertTrue(denied.message!!.contains("no se pudo guardar"))
        assertEquals(listOf("A"), lib.folders().map { it.name })
        assertEquals(a.id, lib.folderOf("n"))
        assertEquals(bakText, bak.readText())
        assertFalse(File(file().path + ".tmp").exists())
    }

    @Test
    fun backupReappliesEveryNoteAndFolderLocation() {
        val lib = Library(file())
        val ciencias = lib.create("Ciencias")
        val fisica = lib.create("Física", ciencias.id)
        val local = lib.create("Local")
        lib.move("vieja", ciencias.id)
        lib.move("queda", local.id)

        lib.applyBackup(
            JSONObject(
                """
                {"carpetas":[
                    {"id":"${fisica.id}","nombre":"Física II"},
                    {"id":"termo","nombre":"Termo","padre":"${fisica.id}"},
                    {"id":"${ciencias.id}","nombre":"Ciencias","padre":"termo"}
                ],"notas":{"vieja":"termo","nueva":"${fisica.id}","queda":"no-existe"}}
                """.trimIndent(),
            ),
        )

        assertEquals("Física II", lib.folder(fisica.id)!!.name)
        assertNull(lib.parentOf(fisica.id))
        assertEquals(fisica.id, lib.parentOf("termo"))
        assertEquals("termo", lib.parentOf(ciencias.id))
        assertEquals("termo", lib.folderOf("vieja"))
        assertEquals(fisica.id, lib.folderOf("nueva"))
        // Una nota que la copia manda a una carpeta que no existe no se desubica.
        assertEquals(local.id, lib.folderOf("queda"))
        assertEquals("Local", lib.folder(local.id)!!.name)

        val again = Library(file())
        assertEquals("termo", again.folderOf("vieja"))
        assertEquals(fisica.id, again.folderOf("nueva"))
        assertEquals(local.id, again.folderOf("queda"))
        assertEquals(listOf("Física II", "Termo", "Ciencias"), again.path(ciencias.id).map { it.name })
    }

    @Test
    fun backupResolvesABrokenLibraryFile() {
        val broken = "{ esto no es json"
        file().writeText(broken)
        val lib = Library(file())
        assertThrows(IllegalStateException::class.java) {
            lib.applyBackup(JSONObject("""{"carpetas":[],"notas":{}}"""))
        }
        assertEquals(broken, file().readText())

        lib.applyBackup(
            JSONObject(
                """{"carpetas":[{"id":"a","nombre":"A","padre":"p"},{"id":"p","nombre":"P"}],"notas":{"n":"a","m":"p"}}""",
            ),
        )
        assertEquals("p", lib.parentOf("a"))
        assertEquals("a", lib.folderOf("n"))
        assertEquals("p", lib.folderOf("m"))
        val again = Library(file())
        assertEquals("a", again.folderOf("n"))
        assertEquals("p", again.parentOf("a"))
        again.create("Más")
        assertEquals(3, Library(file()).folders().size)
    }

    @Test
    fun foldersInsideFolders() {
        val lib = Library(file())
        val fisica = lib.create("Física")
        val termo = lib.create("Termo", fisica.id)
        val gases = lib.create("Gases", termo.id)
        assertEquals(listOf("Física"), lib.children(null).map { it.name })
        assertEquals(listOf("Termo"), lib.children(fisica.id).map { it.name })
        assertEquals(listOf("Física", "Termo", "Gases"), lib.path(gases.id).map { it.name })
        assertEquals(setOf(fisica.id, termo.id, gases.id), lib.subtree(fisica.id))
        // Sobrevive a volver a leer el archivo.
        val again = Library(file())
        assertEquals(termo.id, again.parentOf(gases.id))
        assertEquals(listOf("Física", "Termo", "Gases"), again.path(gases.id).map { it.name })
    }

    @Test
    fun aFolderCannotGoInsideItself() {
        val lib = Library(file())
        val a = lib.create("A")
        val b = lib.create("B", a.id)
        assertEquals(false, lib.moveFolder(a.id, b.id))
        assertEquals(false, lib.moveFolder(a.id, a.id))
        assertEquals(true, lib.moveFolder(b.id, null))
        assertNull(lib.parentOf(b.id))
    }

    @Test
    fun deletingAFolderMovesItsContentsUp() {
        val lib = Library(file())
        val a = lib.create("A")
        val b = lib.create("B", a.id)
        val c = lib.create("C", b.id)
        lib.move("nota", b.id)
        lib.delete(b.id)
        assertEquals(a.id, lib.parentOf(c.id))
        assertEquals(a.id, lib.folderOf("nota"))
        // Con todo lo de adentro: se van las carpetas y las notas quedan sin carpeta.
        lib.move("otra", c.id)
        assertEquals(setOf(a.id, c.id), lib.deleteTree(a.id))
        assertNull(lib.folderOf("nota"))
        assertNull(lib.folderOf("otra"))
        assertEquals(0, lib.folders().size)
    }

    @Test
    fun unknownParentsAndCirclesCountAsTopLevel() {
        file().writeText(
            """{"carpetas":[{"id":"x","nombre":"X","padre":"no-existe"},{"id":"p","nombre":"P","padre":"q"},{"id":"q","nombre":"Q","padre":"p"}],"notas":{}}""",
        )
        val lib = Library(file())
        assertEquals(listOf("P", "Q", "X"), lib.children(null).map { it.name })
        // Un archivo viejo, sin "padre": todas arriba de todo.
        file().writeText("""{"carpetas":[{"id":"a","nombre":"A"}],"notas":{"n":"a"}}""")
        val old = Library(file())
        assertEquals(listOf("A"), old.children(null).map { it.name })
        assertEquals("a", old.folderOf("n"))
    }
}
