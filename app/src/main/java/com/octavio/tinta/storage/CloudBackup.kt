package com.octavio.tinta.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.edit
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Copia de todas las notas en la nube, a través del selector de archivos de Android: se elige
 * un archivo en Google Drive (o en cualquier otro lugar) y la app lo reescribe cuando hay cambios.
 * La app de Drive se encarga de subirlo. No hace falta ninguna cuenta ni clave en la app.
 *
 * La copia es un .zip con `notes/` (las notas, sus miniaturas, sus medios y `carpetas.json`),
 * `stickers/` y `tinta-copia.json` (versión y fecha). Restaurar trae las notas que faltan y las
 * que en la copia están más nuevas; nunca borra.
 */
class CloudBackup private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("tinta-nube", Context.MODE_PRIVATE)
    private val files = context.filesDir
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "tinta-nube") }
    private val main = Handler(Looper.getMainLooper())

    /** Para mostrar el estado: se llama en el hilo de la pantalla. */
    var onStatus: (() -> Unit)? = null

    /** El archivo de la copia, o null si no se configuró. */
    val target: Uri? get() = prefs.getString("uri", null)?.let(Uri::parse)

    /** Nombre del archivo, para mostrarlo. */
    val targetName: String? get() = prefs.getString("name", null)

    /** Cuándo terminó la última copia (ms), 0 si nunca. */
    val lastBackup: Long get() = prefs.getLong("last", 0L)

    /** El último error, o null si la última copia salió bien. */
    val lastError: String? get() = prefs.getString("error", null)

    @Volatile var running = false
        private set

    /** Empieza a usar este archivo (recién elegido con el selector) y hace la primera copia. */
    fun connect(uri: Uri, name: String?) {
        // Para poder volver a escribirlo después de reiniciar la tablet.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        prefs.edit {
            putString("uri", uri.toString())
            putString("name", name)
            putLong("last", 0L)
            remove("error")
        }
        backup(force = true)
    }

    fun disconnect() {
        target?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        prefs.edit { clear() }
        notifyStatus()
    }

    /**
     * Sube la copia si hay cambios desde la última (o siempre, con `force`). Sin `force`, a lo
     * sumo una vez cada [MIN_INTERVAL_MS]. Las notas se empaquetan en el hilo de disco de
     * [NoteStore], después de los guardados pendientes, así la copia nunca tiene una nota a medias.
     */
    fun backup(force: Boolean = false, minIntervalMs: Long = MIN_INTERVAL_MS) {
        val uri = target ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastBackup < minIntervalMs) return
        if (running) return
        running = true
        notifyStatus()
        worker.execute {
            val zip = File(context.cacheDir, "copia-tinta.zip")
            val result = runCatching {
                val store = NoteStore.get(context)
                val changed = store.io.submit<Boolean> {
                    val latest = latestChange()
                    if (!force && latest <= lastBackup) return@submit false
                    zip.outputStream().buffered(1 shl 16).use { write(it) }
                    true
                }.get()
                if (changed) {
                    // Primero en el disco de la tablet y después de una vez al archivo de Drive.
                    val out = openForWrite(uri) ?: error("no se pudo abrir el archivo de la copia")
                    out.use { o -> zip.inputStream().use { it.copyTo(o, 1 shl 16) } }
                }
                changed
            }
            zip.delete()
            result.onSuccess { changed ->
                prefs.edit {
                    if (changed) putLong("last", now)
                    remove("error")
                }
            }.onFailure { e ->
                Log.w(TAG, "copia en la nube", e)
                prefs.edit { putString("error", e.message ?: e.javaClass.simpleName) }
            }
            running = false
            notifyStatus()
        }
    }

    private fun openForWrite(uri: Uri): OutputStream? {
        val r = context.contentResolver
        // "wt" vacía el archivo antes de escribir; hay proveedores que sólo aceptan "w".
        return runCatching { r.openOutputStream(uri, "wt") }.getOrNull() ?: r.openOutputStream(uri, "w")
    }

    /** La fecha del archivo más nuevo de notas y stickers. */
    private fun latestChange(): Long {
        var latest = 0L
        for (dir in listOf(File(files, NOTES), File(files, STICKERS))) {
            dir.walkTopDown().forEach { f -> if (f.isFile && !f.name.endsWith(".tmp")) latest = maxOf(latest, f.lastModified()) }
        }
        return latest
    }

    /** Escribe la copia completa en `out`. Llamar desde el hilo de disco. */
    fun write(out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            zip.setLevel(1) // las notas ya van comprimidas: alcanza con lo rápido
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(
                JSONObject().put("version", 1).put("fecha", System.currentTimeMillis()).put("app", "Tinta").toString(2)
                    .toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
            for (top in listOf(NOTES, STICKERS)) {
                val dir = File(files, top)
                if (!dir.exists()) continue
                dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
                    zip.putNextEntry(ZipEntry(top + "/" + f.relativeTo(dir).invariantSeparatorsPath).apply { time = f.lastModified() })
                    FileInputStream(f).use { it.copyTo(zip, 1 shl 16) }
                    zip.closeEntry()
                }
            }
        }
    }

    class Restored(val added: Int, val updated: Int, val skipped: Int, val busy: List<String>, val ids: List<String>)

    /**
     * Trae lo que hay en la copia: notas que no están acá y notas que en la copia son más nuevas
     * (según la fecha guardada en cada nota). Las que están abiertas en el editor (`isOpen`) no se
     * tocan. Carpetas y stickers se suman. Llamar fuera del hilo de la pantalla.
     */
    fun restore(input: InputStream, isOpen: (String) -> Boolean): Restored {
        val store = NoteStore.get(context)
        val stage = File(context.cacheDir, "restaurar").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            unzip(input, stage)
            if (!File(stage, MANIFEST).exists()) error("ese archivo no es una copia de Tinta")
            return store.io.submit<Restored> { merge(stage, store, isOpen) }.get()
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun unzip(input: InputStream, stage: File) {
        val root = stage.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(input, 1 shl 16)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name
                if (name != MANIFEST && !name.startsWith("$NOTES/") && !name.startsWith("$STICKERS/")) continue
                val f = File(stage, name)
                // Nada fuera de la carpeta de trabajo (un nombre con "../").
                if (!f.canonicalPath.startsWith(root)) continue
                f.parentFile?.mkdirs()
                f.outputStream().use { zip.copyTo(it, 1 shl 16) }
            }
        }
    }

    private fun merge(stage: File, store: NoteStore, isOpen: (String) -> Boolean): Restored {
        val notesIn = File(stage, NOTES)
        val notesOut = File(files, NOTES).apply { mkdirs() }
        var added = 0
        var updated = 0
        var skipped = 0
        val busy = ArrayList<String>()
        val restoredIds = ArrayList<String>()
        for (f in notesIn.listFiles { x -> x.name.endsWith(".tinta") }.orEmpty()) {
            val id = f.name.removeSuffix(".tinta")
            val incoming = runCatching { header(f) }.getOrNull() ?: continue
            val local = store.file(id).takeIf { it.exists() }?.let { runCatching { header(it) }.getOrNull() }
            if (local != null && local.modified >= incoming.modified) {
                skipped++
                continue
            }
            if (isOpen(id)) {
                busy.add(incoming.title)
                continue
            }
            // Primero los medios y la miniatura; la nota al final, de forma atómica.
            File(notesIn, "$id.media").takeIf { it.isDirectory }?.copyRecursively(File(notesOut, "$id.media"), overwrite = true)
            File(notesIn, "$id.png").takeIf { it.exists() }?.copyTo(store.thumbnail(id), overwrite = true)
            val tmp = File(notesOut, "$id.tinta.tmp")
            f.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(store.file(id))) {
                tmp.delete()
                continue
            }
            if (local == null) added++ else updated++
            restoredIds.add(id)
        }
        // Las carpetas de la copia: se crean las que falten y se ubican las notas traídas.
        File(notesIn, "carpetas.json").takeIf { it.exists() }?.let { json ->
            runCatching {
                val o = JSONObject(json.readText())
                val list = o.optJSONArray("carpetas")
                for (i in 0 until (list?.length() ?: 0)) {
                    val c = list!!.getJSONObject(i)
                    store.library.adopt(c.getString("id"), c.getString("nombre"), c.optString("padre").ifEmpty { null })
                }
                val notes = o.optJSONObject("notas")
                for (id in restoredIds) notes?.optString(id)?.takeIf { it.isNotEmpty() }?.let { store.library.move(id, it) }
            }
        }
        // Stickers que no estén.
        File(stage, STICKERS).takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }?.forEach { f ->
            val out = File(File(files, STICKERS), f.relativeTo(File(stage, STICKERS)).path)
            if (!out.exists()) {
                out.parentFile?.mkdirs()
                f.copyTo(out)
            }
        }
        return Restored(added, updated, skipped, busy, restoredIds)
    }

    private fun header(f: File) = DataInputStream(BufferedInputStream(f.inputStream(), 512)).use { NoteFormat.readHeader(it) }

    private fun notifyStatus() {
        main.post { onStatus?.invoke() }
    }

    companion object {
        private const val TAG = "Tinta"
        const val MIME = "application/zip"
        const val FILE_NAME = "Tinta - copia de las notas.zip"
        private const val MANIFEST = "tinta-copia.json"
        private const val NOTES = "notes"
        private const val STICKERS = "stickers"

        /** Al salir de una nota, una copia cada este tiempo como mucho. */
        const val MIN_INTERVAL_MS = 2 * 60_000L

        /** Mientras se escribe, cada bastante más: la copia entera puede pesar decenas de MB. */
        const val WHILE_WRITING_MS = 15 * 60_000L

        @Volatile private var instance: CloudBackup? = null

        fun get(context: Context): CloudBackup =
            instance ?: synchronized(this) {
                instance ?: CloudBackup(context.applicationContext).also { instance = it }
            }
    }
}
