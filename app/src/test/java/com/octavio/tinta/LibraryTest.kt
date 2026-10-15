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
}
