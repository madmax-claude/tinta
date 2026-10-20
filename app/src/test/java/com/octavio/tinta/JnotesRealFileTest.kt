package com.octavio.tinta

import com.octavio.tinta.jnotes.JnotesFile
import com.octavio.tinta.jnotes.JnotesImporter
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

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

    /** Importa todas las notas de una carpeta (sin dibujar los PDF) y cuenta lo que salió. */
    @Test
    fun importsRealFolder() {
        val dir = System.getProperty("jnotes.dir").orEmpty()
        assumeTrue("sin -PjnotesDir", dir.isNotEmpty())
        val media = Files.createTempDirectory("medios").toFile()
        val files = File(dir).listFiles { f -> f.name.endsWith(".Jnotes") }.orEmpty().sortedBy { it.name }
        var errors = 0
        for (f in files) {
            val start = System.nanoTime()
            val pdf = File(media, "nota.pdf")
            runCatching {
                val r = f.inputStream().use { JnotesImporter.import(it, "id", f.name, 2f, media, pdf) }
                val n = r.note
                val ms = (System.nanoTime() - start) / 1_000_000
                println(
                    "OK\t${f.name}\t${n.strokes.size}\t${r.failedStrokes}\t${n.pages.size}\t${n.pages.minPages}\t" +
                        "${n.images.size}\t${n.texts.size}\t${r.pdfPages.size}\t${if (pdf.exists()) pdf.length() else 0}\t" +
                        "${n.background}\t${r.folder}\t$ms",
                )
            }.onFailure {
                errors++
                println("ERROR\t${f.name}\t$it")
            }
            pdf.delete()
            media.listFiles()?.forEach { it.delete() }
        }
        media.deleteRecursively()
        println("${files.size} notas, $errors con error")
    }
}
