package com.octavio.tinta.export

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.octavio.tinta.hint
import com.octavio.tinta.model.Note
import com.octavio.tinta.storage.HandText
import com.octavio.tinta.storage.NoteStore
import com.octavio.tinta.toast
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Los formatos para sacar una nota de Tinta, además del PDF: imágenes (una por hoja), el texto
 * de lo escrito a mano y la nota entera, para abrirla en Tinta en otra tablet.
 */
object Exporter {
    enum class Format(val label: String, val detail: String) {
        PDF("PDF", "Para imprimir o leer en la compu: cada hoja de su tamaño real."),
        IMAGES("Imágenes", "Una por hoja (PNG): para mandarla por WhatsApp o verla en el celular."),
        TEXT("Texto", "Lo escrito a mano pasado a letra (.txt): para copiarlo a un documento o hacer tarjetas de Anki."),
        NOTE("Nota de Tinta", "La nota entera, con imágenes y audios: para abrirla en Tinta en la otra tablet."),
    }

    private fun dir(context: Context) = File(context.cacheDir, "exports").apply { mkdirs() }

    /** Las hojas como PNG (en una nota infinita, una imagen con todo). Llamar fuera de la pantalla. */
    fun images(context: Context, n: Note): List<File> {
        val name = Render.fileName(n.title)
        val strokes = n.strokes
        val out = ArrayList<File>()
        val bounds = Render.bounds(strokes, n.images, n.texts, n.widgets)
        if (n.pages.paged) {
            val used = n.pages.used(bounds?.bottom ?: Float.NaN)
            // Unos 250 puntos por pulgada: se lee bien en el celular y no pesa tanto.
            val width = (n.pages.width * PAGE_PX_PER_DP).toInt()
            for (k in 0 until used) {
                val bitmap = Render.page(strokes, n.images, n.texts, n.widgets, n.background, n.pages, k, width, n.paperColor)
                out.add(save(context, bitmap, if (used == 1) name else "$name - hoja ${k + 1}"))
            }
        } else {
            val b = bounds ?: android.graphics.RectF(0f, 0f, 400f, 300f)
            val r = android.graphics.RectF(b.left - MARGIN, b.top - MARGIN, b.right + MARGIN, b.bottom + MARGIN)
            val scale = min(2f, min(MAX_SIDE / max(r.width(), r.height()), sqrt(MAX_PIXELS / (r.width() * r.height()))))
            val count = n.pages.count(b.bottom)
            out.add(save(context, Render.area(strokes, n.images, n.texts, n.widgets, n.background, n.pages, count, r, scale, n.paperColor), name))
        }
        return out
    }

    private fun save(context: Context, bitmap: Bitmap, name: String): File {
        val f = File(dir(context), "$name.png")
        f.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return f
    }

    /**
     * El texto de la nota: los renglones leídos de la letra (y los cuadros de texto), en orden de
     * lectura, con el número de hoja si tiene hojas.
     */
    fun text(n: Note, lines: List<HandText.Line>): String = buildString {
        append(n.title).append("\n\n")
        val sorted = lines.filter { it.texts.firstOrNull()?.isNotBlank() == true }.sortedWith(compareBy({ it.top }, { it.left }))
        var page = -1
        for (line in sorted) {
            if (n.pages.paged) {
                val k = n.pages.pageAt((line.top + line.bottom) / 2).coerceAtLeast(0)
                if (k != page) {
                    if (page >= 0) append('\n')
                    append("— Hoja ").append(k + 1).append(" —\n")
                    page = k
                }
            }
            append(line.texts.first().trim()).append('\n')
        }
    }

    fun textFile(context: Context, n: Note, text: String): File =
        File(dir(context), Render.fileName(n.title) + ".txt").apply { writeText(text) }

    /**
     * La nota entera en un archivo `.tinta`: un zip con la nota, sus medios y la miniatura. Llamar
     * desde el hilo de disco de [NoteStore], después de guardarla.
     */
    fun noteBundle(context: Context, store: NoteStore, n: Note): File {
        val f = File(dir(context), Render.fileName(n.title) + BUNDLE_EXT)
        ZipOutputStream(f.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(JSONObject().put("app", "Tinta").put("version", 1).put("titulo", n.title).toString(2).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(NOTE_ENTRY))
            store.file(n.id).inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            store.thumbnail(n.id).takeIf { it.exists() }?.let { t ->
                zip.putNextEntry(ZipEntry(THUMB_ENTRY))
                t.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            store.mediaDir(n.id).listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.forEach { m ->
                zip.putNextEntry(ZipEntry(MEDIA_DIR + m.name))
                m.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return f
    }

    /**
     * Si el archivo es una nota de Tinta exportada: por el nombre, o porque es un zip con la ficha de
     * Tinta adentro (los .Jnotes también son zip, pero sin esa ficha).
     */
    fun isBundle(name: String, open: () -> InputStream?): Boolean {
        if (name.endsWith(BUNDLE_EXT, ignoreCase = true)) return true
        return runCatching {
            open()?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    var k = 0
                    while (k++ < 50) {
                        val e = zip.nextEntry ?: break
                        if (e.name == MANIFEST) return@use true
                    }
                    false
                }
            } ?: false
        }.getOrDefault(false)
    }

    /**
     * Trae una nota de Tinta exportada como nota nueva (con otro id, así no pisa ninguna). Llamar
     * desde el hilo de disco. Devuelve la nota leída.
     */
    fun importBundle(store: NoteStore, input: InputStream): Note {
        val id = store.newId()
        val media = store.mediaDir(id)
        val target = store.file(id)
        val tmp = File(target.path + ".tmp")
        var hasNote = false
        var isTinta = false
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name
                    when {
                        name == MANIFEST -> isTinta = runCatching { JSONObject(zip.readBytes().decodeToString()).optString("app") == "Tinta" }.getOrDefault(false)
                        name == NOTE_ENTRY -> {
                            tmp.outputStream().use { zip.copyTo(it) }
                            hasNote = true
                        }
                        name == THUMB_ENTRY -> store.thumbnail(id).outputStream().use { zip.copyTo(it) }
                        name.startsWith(MEDIA_DIR) -> {
                            val file = File(media, name.removePrefix(MEDIA_DIR))
                            // Nada fuera de la carpeta de medios (un nombre con "../").
                            if (file.canonicalPath.startsWith(media.canonicalPath + File.separator)) file.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
            if (!isTinta || !hasNote) error("no es una nota de Tinta")
            val note = NoteStore.readNote(tmp, id, media)
            if (!tmp.renameTo(target)) error("no se pudo guardar la nota")
            return note
        } catch (e: Exception) {
            tmp.delete()
            store.delete(id)
            throw e
        }
    }

    /**
     * Exporta `n` (una copia de la nota, que se lee en otro hilo) en `format` y abre "Compartir".
     * `launch` envuelve el abrir otra pantalla (el editor lo usa para no creer que lo cerraron).
     */
    fun start(activity: Activity, store: NoteStore, n: Note, format: Format, launch: (() -> Unit) -> Unit) {
        fun fail(e: Throwable) = activity.runOnUiThread { activity.toastLong("No se pudo exportar: ${e.message}") }
        when (format) {
            Format.PDF -> store.io.execute {
                runCatching { Render.pdf(activity, n.title, n.strokes, n.images, n.texts, n.widgets, n.background, n.pages, n.paperColor) }
                    .onSuccess { f -> activity.runOnUiThread { launch { share(activity, listOf(f), "application/pdf") } } }
                    .onFailure(::fail)
            }
            Format.IMAGES -> Thread({
                runCatching { images(activity, n) }
                    .onSuccess { files -> activity.runOnUiThread { launch { share(activity, files, "image/png") } } }
                    .onFailure(::fail)
            }, "tinta-exportar").start()
            Format.TEXT -> HandText.get(activity).read(
                n.id, n.modified, n.strokes, n.texts,
                progress = { activity.hint(it) },
            ) { lines, error ->
                if (lines == null) {
                    activity.toastLong(error ?: "No se pudo leer la letra")
                    return@read
                }
                val text = text(n, lines)
                val f = textFile(activity, n, text)
                launch { share(activity, listOf(f), "text/plain", text) }
            }
            Format.NOTE -> store.io.execute {
                // En el hilo de disco: después de que se terminó de guardar.
                runCatching { noteBundle(activity, store, n) }
                    .onSuccess { f -> activity.runOnUiThread { launch { share(activity, listOf(f), BUNDLE_MIME) } } }
                    .onFailure(::fail)
            }
        }
    }

    private fun Activity.toastLong(text: String) = toast(text, long = true)

    /** Una copia de las listas de la nota, para leerla en otro hilo mientras se sigue escribiendo. */
    fun snapshot(n: Note) = Note(
        n.id, n.title, n.background, n.created, n.modified, n.viewport, n.pages,
        n.strokes.toMutableList(), n.images.toMutableList(), n.texts.toMutableList(), n.widgets.toMutableList(), n.paperColor,
    )

    /** Comparte los archivos con las apps de Android (LocalSend, Drive, WhatsApp…). */
    fun share(activity: Activity, files: List<File>, mime: String, text: String? = null) {
        if (files.isEmpty()) return
        val uris = files.map { FileProvider.getUriForFile(activity, "${activity.packageName}.files", it) }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = mime
        text?.let { send.putExtra(Intent.EXTRA_TEXT, it) }
        send.clipData = ClipData.newRawUri(files[0].name, uris[0]).apply { for (u in uris.drop(1)) addItem(ClipData.Item(u)) }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(send, files[0].nameWithoutExtension))
    }

    const val BUNDLE_EXT = ".tinta"
    const val BUNDLE_MIME = "application/octet-stream"
    private const val MANIFEST = "tinta-nota.json"
    private const val NOTE_ENTRY = "nota.tinta"
    private const val THUMB_ENTRY = "miniatura.png"
    private const val MEDIA_DIR = "medios/"
    private const val PAGE_PX_PER_DP = 1.6f
    private const val MARGIN = 40f
    private const val MAX_SIDE = 4096f
    private const val MAX_PIXELS = 16_000_000f
}
