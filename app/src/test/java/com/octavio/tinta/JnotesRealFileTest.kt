package com.octavio.tinta

import com.octavio.tinta.jnotes.JnotesFile
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Lee una nota real si se pasa `-PjnotesSample=...`; los apuntes no se guardan en el repo. */
class JnotesRealFileTest {

    @Test
    fun parsesRealNote() {
        val path = System.getProperty("jnotes.sample").orEmpty()
        assumeTrue("sin -PjnotesSample", path.isNotEmpty())
        val start = System.nanoTime()
        val note = File(path).inputStream().use { JnotesFile.parse(it) }
        val ms = (System.nanoTime() - start) / 1_000_000
        val strokes = note.pages.sumOf { it.strokes.size }
        val points = note.pages.sumOf { p -> p.strokes.sumOf { it.xs.size } }
        println("«${note.title}»: ${note.pages.size} páginas, $strokes trazos, $points puntos, " +
            "${note.pages.sumOf { it.images.size }} imágenes, ${note.pages.sumOf { it.texts.size }} textos, $ms ms")
    }
}
