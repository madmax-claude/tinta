package com.octavio.tinta

import com.octavio.tinta.storage.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
        file().writeText("{ esto no es json")
        assertEquals(0, Library(file()).folders().size)
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
