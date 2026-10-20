package com.octavio.tinta.storage

import android.content.Context
import com.octavio.tinta.jnotes.JnotesImporter
import org.json.JSONObject
import java.io.File

/**
 * Pasa de una vez todas las notas de Jnotes, con sus carpetas y fechas. Por adb se dejan en
 * `files/importar-jnotes/` los .Jnotes y un `orden.json` (lo arma `tools/jnotes_a_tinta.py`):
 *
 *   {"carpetas": [{"id", "nombre", "padre"}],                      padres antes que hijas
 *    "notas":    [{"archivo", "id", "titulo", "carpeta", "modificada"}]}     modificada en ms
 *
 * Las notas y carpetas conservan el id de Jnotes: si se corta, se vuelve a empezar y las que ya
 * están se saltean. Cada .Jnotes se borra al terminar de importarlo. Sin miniaturas: se arman
 * al abrir cada nota. El avance queda en `estado.txt`, para seguirlo desde la PC.
 */
object JnotesBatch {
    const val ACTION = "com.octavio.tinta.IMPORTAR_JNOTES"

    class Summary(val imported: Int, val skipped: Int, val failed: List<String>)

    fun dir(context: Context) = File(context.filesDir, "importar-jnotes")

    fun pending(context: Context) = File(dir(context), "orden.json").exists()

    /** Llamar desde [NoteStore.io]. `progress` recibe (nota, total, texto). */
    fun run(context: Context, store: NoteStore, density: Float, progress: (Int, Int, String) -> Unit): Summary {
        val dir = dir(context)
        val order = JSONObject(File(dir, "orden.json").readText())
        val status = File(dir, "estado.txt")
        val log = StringBuilder()
        fun report(line: String) {
            log.append(line).append('\n')
            runCatching { status.writeText(log.toString()) }
        }

        val folders = order.optJSONArray("carpetas")
        for (i in 0 until (folders?.length() ?: 0)) {
            val f = folders!!.getJSONObject(i)
            val parent = f.optString("padre").takeIf { it.isNotEmpty() && it != "null" }
            store.library.adopt(f.getString("id"), f.getString("nombre"), parent)
        }

        val notes = order.getJSONArray("notas")
        var imported = 0
        var skipped = 0
        val failed = ArrayList<String>()
        for (i in 0 until notes.length()) {
            val n = notes.getJSONObject(i)
            val id = n.getString("id")
            val src = File(dir, n.getString("archivo"))
            val folder = n.optString("carpeta").takeIf { it.isNotEmpty() && it != "null" }
            val title = n.optString("titulo").ifEmpty { src.name.substringBeforeLast('.') }
            val label = "${i + 1}/${notes.length()} «$title»"
            if (store.file(id).exists()) {
                skipped++
                src.delete()
                report("$label: ya estaba")
                continue
            }
            if (!src.exists()) {
                failed.add("$title: falta el archivo")
                report("$label: FALTA EL ARCHIVO")
                continue
            }
            progress(i + 1, notes.length(), title)
            // Lo que haya quedado de un intento cortado.
            store.mediaDir(id).deleteRecursively()
            val media = store.mediaDir(id)
            val pdf = File(media, Imports.ORIGINAL)
            runCatching {
                val time = n.optLong("modificada").takeIf { it > 0 } ?: System.currentTimeMillis()
                val r = src.inputStream().use { JnotesImporter.import(it, id, title, density, media, pdf, time) }
                val note = r.note
                if (r.pdfPages.isNotEmpty()) {
                    val pages = Imports.pdfPages(pdf, r.pdfPages, media) { k, total ->
                        progress(i + 1, notes.length(), "$title — página $k de $total")
                    }
                    note.images.addAll(0, pages)
                }
                store.write(id, store.header(note), note.strokes, note.images, note.texts, note.widgets)
                store.library.move(id, folder)
                src.delete()
                imported++
                report("$label: ${note.strokes.size} trazos, ${note.pages.minPages} hojas, ${note.images.size} imágenes" +
                    if (r.failedStrokes > 0) " (${r.failedStrokes} trazos dañados)" else "")
            }.onFailure { e ->
                store.mediaDir(id).deleteRecursively()
                failed.add("$title: ${e.message ?: e.javaClass.simpleName}")
                report("$label: ERROR ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        report("FIN:$imported importadas, $skipped ya estaban, ${failed.size} con error")
        File(dir, "orden.json").renameTo(File(dir, "orden-hecho.json"))
        return Summary(imported, skipped, failed)
    }
}
