package com.octavio.tinta.storage

import android.content.Context
import com.octavio.tinta.jnotes.JnotesImporter
import org.json.JSONArray
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
 * están se saltean. Cada .Jnotes se borra al terminar de importarlo. Si la importación falla, el
 * archivo se conserva; si falta, queda anotado igual. En los dos casos `orden.json` queda sólo
 * con esas notas, el lote sigue pendiente y el próximo intento las retoma. El detalle queda en
 * `estado.txt`. Si no falla ninguna, el orden pasa a `orden-hecho.json`. Sin miniaturas: se arman
 * al abrir cada nota.
 */
object JnotesBatch {
    const val ACTION = "com.octavio.tinta.IMPORTAR_JNOTES"

    private const val ORDER = "orden.json"
    private const val DONE = "orden-hecho.json"

    class Summary(val imported: Int, val skipped: Int, val failed: List<String>)

    /** Qué hacer con una nota antes de leer el .Jnotes. */
    internal enum class Plan { SKIP, MISSING, IMPORT }

    fun dir(context: Context) = File(context.filesDir, "importar-jnotes")

    fun pending(context: Context) = pending(dir(context))

    /** Hay un lote nuevo, o uno para reintentar, si sigue estando el orden. */
    internal fun pending(dir: File) = File(dir, ORDER).exists()

    /**
     * Ya está en la biblioteca, no está el archivo, o hay que importarla. Si ya está, no importa
     * que el .Jnotes se haya borrado en un intento anterior: eso no es un fallo.
     */
    internal fun plan(alreadyThere: Boolean, sourceExists: Boolean): Plan = when {
        alreadyThere -> Plan.SKIP
        sourceExists -> Plan.IMPORT
        else -> Plan.MISSING
    }

    /** El .Jnotes se deja en la carpeta sólo cuando esta nota vuelve al orden. */
    internal fun keepsSource(plan: Plan, imported: Boolean): Boolean = when (plan) {
        Plan.SKIP -> false
        Plan.MISSING -> true
        Plan.IMPORT -> !imported
    }

    internal fun commitNote(
        src: File,
        note: JSONObject,
        plan: Plan,
        imported: Boolean,
        retry: MutableList<JSONObject>,
    ) {
        if (keepsSource(plan, imported)) retry.add(note) else src.delete()
    }

    /**
     * Cierra el lote. Sin fallos, `orden.json` pasa a `orden-hecho.json` tal cual está, sin
     * reescribirlo. Con fallos, `orden.json` queda con las mismas carpetas y sólo las notas que
     * hay que volver a intentar, así [pending] sigue siendo cierto.
     */
    internal fun settle(dir: File, order: JSONObject, failedNotes: List<JSONObject>) {
        if (failedNotes.isEmpty()) {
            File(dir, ORDER).renameTo(File(dir, DONE))
            return
        }
        val retry = JSONObject()
        val keys = order.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key != "notas") retry.put(key, order.get(key))
        }
        retry.put("notas", JSONArray().apply { failedNotes.forEach { put(it) } })
        val tmp = File(dir, "$ORDER.tmp")
        tmp.writeText(retry.toString(2))
        // Si el rename no puede pisar el orden viejo, se deja ese: el lote sigue pendiente.
        if (!tmp.renameTo(File(dir, ORDER))) tmp.delete()
    }

    /** Cómo cierra `estado.txt`. Con fallos, debajo de la línea FIN queda qué se reintenta. */
    internal fun closing(imported: Int, skipped: Int, failed: List<String>): String {
        val line = "FIN:$imported importadas, $skipped ya estaban, ${failed.size} con error"
        if (failed.isEmpty()) return line
        return line + "\nQuedan para reintentar:\n" + failed.joinToString("\n") { "- $it" }
    }

    /** Llamar desde [NoteStore.io]. `progress` recibe (nota, total, texto). */
    fun run(context: Context, store: NoteStore, density: Float, progress: (Int, Int, String) -> Unit): Summary {
        val dir = dir(context)
        val order = JSONObject(File(dir, ORDER).readText())
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
        val retry = ArrayList<JSONObject>()
        for (i in 0 until notes.length()) {
            val n = notes.getJSONObject(i)
            val id = n.getString("id")
            val src = File(dir, n.getString("archivo"))
            val folder = n.optString("carpeta").takeIf { it.isNotEmpty() && it != "null" }
            val title = n.optString("titulo").ifEmpty { src.name.substringBeforeLast('.') }
            val label = "${i + 1}/${notes.length()} «$title»"
            when (plan(store.file(id).exists(), src.exists())) {
                Plan.SKIP -> {
                    skipped++
                    commitNote(src, n, Plan.SKIP, imported = false, retry)
                    report("$label: ya estaba")
                }
                Plan.MISSING -> {
                    failed.add("$title: falta el archivo")
                    commitNote(src, n, Plan.MISSING, imported = false, retry)
                    report("$label: FALTA EL ARCHIVO")
                }
                Plan.IMPORT -> {
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
                        commitNote(src, n, Plan.IMPORT, imported = true, retry)
                        imported++
                        report("$label: ${note.strokes.size} trazos, ${note.pages.minPages} hojas, ${note.images.size} imágenes" +
                            if (r.failedStrokes > 0) " (${r.failedStrokes} trazos dañados)" else "")
                    }.onFailure { e ->
                        store.mediaDir(id).deleteRecursively()
                        failed.add("$title: ${e.message ?: e.javaClass.simpleName}")
                        commitNote(src, n, Plan.IMPORT, imported = false, retry)
                        report("$label: ERROR ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }
        }
        report(closing(imported, skipped, failed))
        settle(dir, order, retry)
        return Summary(imported, skipped, failed)
    }
}
