package com.octavio.tinta.storage

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
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
 * una carpeta en Google Drive (o en cualquier otro lugar) y la app escribe ahí cuando hay cambios.
 * La app de Drive se encarga de subirla. No hace falta ninguna cuenta ni clave en la app.
 *
 * Cada copia es un archivo nuevo. El anterior no se abre para escribir: en Drive abrir un
 * documento lo trunca antes de que entren los bytes, y si la app, el disco o la subida se cortan
 * esa era la única copia de afuera. Recién cuando la nueva se lee entera y coincide se la recuerda
 * y se borra la vieja. [latestBackupUri] elige la válida con la fecha más nueva.
 *
 * La copia es un .zip con `notes/` (las notas, sus miniaturas, sus medios y `carpetas.json`),
 * `stickers/` y `tinta-copia.json` (versión y fecha). Restaurar trae las notas que faltan y las
 * que en la copia están más nuevas; nunca borra.
 *
 * Las páginas de los PDF dibujadas como imagen no van si está el PDF original de la nota
 * ([Imports.isPage]): son casi todo el peso. Al restaurar se vuelven a dibujar.
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

    /** Empieza a usar esta carpeta (recién elegida con el selector) y hace la primera copia. */
    fun connect(uri: Uri, name: String?) {
        // Para poder volver a escribir después de reiniciar la tablet.
        val previous = listOfNotNull(prefs.getString("uri", null), prefs.getString(PREF_DIR, null)).distinct()
        val folder = DocumentsContract.isTreeUri(uri)
        runCatching { takePermission(uri) }
        prefs.edit {
            if (folder) putString(PREF_DIR, uri.toString()) else remove(PREF_DIR)
            putString("uri", uri.toString())
            putString("name", if (folder) folderLabel(uri) ?: name else name)
            putLong("last", 0L)
            remove("error")
        }
        previous.filter { it != uri.toString() }.forEach { raw ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    raw.toUri(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        backup(force = true)
    }

    fun disconnect() {
        listOfNotNull(prefs.getString("uri", null), prefs.getString(PREF_DIR, null))
            .distinct()
            .forEach { raw ->
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        raw.toUri(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
        prefs.edit { clear() }
        notifyStatus()
    }

    /**
     * El archivo de la copia válida más nueva, o el que ya estaba anotado si la carpeta no se puede
     * leer. Null si todavía no hay un archivo (sólo la carpeta).
     */
    fun latestBackupUri(): Uri? {
        val fallback = target?.takeIf { !SafBackupDirectory.isFolderGrant(it) }
        val dir = backupFolder() ?: return fallback
        val best = try {
            BackupRotation.newest(dir, stem(), prefs.getString("uri", null))
        } catch (e: Exception) {
            Log.w(TAG, "buscar la copia más nueva", e)
            return fallback
        } ?: return fallback
        if (best.key != prefs.getString("uri", null)) pointAt(best)
        return best.key.toUri()
    }

    /**
     * Sube la copia si hay cambios desde la última (o siempre, con `force`). Sin `force`, a lo
     * sumo una vez cada `minIntervalMs`. Las notas se empaquetan en el hilo de disco de
     * [NoteStore], después de los guardados pendientes, así la copia nunca tiene una nota a medias.
     */
    fun backup(force: Boolean = false, minIntervalMs: Long = MIN_INTERVAL_MS) {
        if (prefs.getString(PREF_DIR, null) == null && target == null) return
        val now = System.currentTimeMillis()
        if (!force && now - lastBackup < minIntervalMs) return
        if (running) return
        running = true
        notifyStatus()
        worker.execute {
            val zip = File(context.cacheDir, "copia-tinta.zip")
            val result = runCatching {
                // Sin carpeta no hay dónde crear el archivo nuevo: reescribir el único documento
                // lo truncaría antes de terminar.
                val dir = backupFolder() ?: error(NEED_FOLDER)
                val store = NoteStore.get(context)
                val changed = store.io.submit<Boolean> {
                    val latest = latestChange()
                    if (!force && latest <= lastBackup) return@submit false
                    zip.outputStream().buffered(1 shl 16).use { write(it) }
                    true
                }.get()
                if (changed) {
                    // Primero el zip completo en la tablet. Recién después un documento nuevo;
                    // la copia anterior no se abre para escribir.
                    BackupRotation.publish(dir, zip, stem()) { pointAt(it) }
                } else {
                    // Nada nuevo para subir: igual se busca si quedó una copia más nueva a medias.
                    reconcile(dir)
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

    private fun backupFolder(): SafBackupDirectory? {
        val raw = prefs.getString(PREF_DIR, null) ?: return null
        val uri = raw.toUri()
        if (!DocumentsContract.isTreeUri(uri)) return null
        return SafBackupDirectory(context.contentResolver, uri)
    }

    private fun reconcile(dir: BackupDirectory) {
        val best = BackupRotation.newest(dir, stem(), prefs.getString("uri", null)) ?: return
        if (best.key != prefs.getString("uri", null)) pointAt(best)
    }

    /** Anota el archivo vigente antes de borrar el anterior, y lo deja en disco ya. */
    @SuppressLint("ApplySharedPref")
    private fun pointAt(doc: BackupDocument) {
        prefs.edit(commit = true) {
            putString("uri", doc.key)
            putString("name", doc.name)
        }
    }

    private fun stem() = FILE_NAME.removeSuffix(".zip")

    private fun takePermission(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    private fun folderLabel(uri: Uri): String? {
        val doc = try {
            DocumentsContract.buildDocumentUriUsingTree(uri, SafBackupDirectory.folderDocumentId(uri))
        } catch (e: Exception) {
            return null
        }
        return context.contentResolver.query(
            doc,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
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
            zip.putNextEntry(ZipEntry(BackupRotation.MANIFEST))
            zip.write(
                JSONObject().put("version", 1).put("fecha", System.currentTimeMillis()).put("app", "Tinta").toString(2)
                    .toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
            for (top in listOf(NOTES, STICKERS)) {
                val dir = File(files, top)
                if (!dir.exists()) continue
                dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") && !Imports.isPage(it) }.forEach { f ->
                    zip.putNextEntry(ZipEntry(top + "/" + f.relativeTo(dir).invariantSeparatorsPath).apply { time = f.lastModified() })
                    FileInputStream(f).use { it.copyTo(zip, 1 shl 16) }
                    zip.closeEntry()
                }
            }
        }
    }

    class Restored(val added: Int, val updated: Int, val skipped: Int, val busy: List<String>, val ids: List<String>, val redrawn: Int = 0)

    /**
     * Trae lo que hay en la copia: notas que no están acá y notas que en la copia son más nuevas
     * (según la fecha guardada en cada nota). Las que están abiertas en el editor (`isOpen`) no se
     * tocan. Carpetas y stickers se suman. Después se dibujan las páginas de PDF que no venían en
     * la copia; `progress` recibe un texto para mostrar. Llamar fuera del hilo de la pantalla.
     */
    fun restore(input: InputStream, isOpen: (String) -> Boolean, progress: (String) -> Unit = {}): Restored {
        val store = NoteStore.get(context)
        val stage = File(context.cacheDir, "restaurar").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            unzip(input, stage)
            if (!File(stage, BackupRotation.MANIFEST).exists()) error("ese archivo no es una copia de Tinta")
            val r = store.io.submit<Restored> { merge(stage, store, isOpen) }.get()
            stage.deleteRecursively()
            var redrawn = 0
            r.ids.forEachIndexed { i, id ->
                runCatching {
                    val note = store.load(id)
                    redrawn += Imports.redrawMissing(note, store.mediaDir(id)) { k, n ->
                        progress("Dibujando las páginas de «${note.title}» (${i + 1}/${r.ids.size}): $k de $n")
                    }
                }.onFailure { Log.w(TAG, "páginas de $id", it) }
            }
            return Restored(r.added, r.updated, r.skipped, r.busy, r.ids, redrawn)
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
                if (name != BackupRotation.MANIFEST && !name.startsWith("$NOTES/") && !name.startsWith("$STICKERS/")) continue
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
        private const val PREF_DIR = "dir"
        private const val NOTES = "notes"

        /**
         * Un solo documento no se puede reemplazar sin truncarlo. La persona tiene que elegir la
         * carpeta; el archivo que ya está no se toca.
         */
        private const val NEED_FOLDER =
            "la copia está en un solo archivo y reescribirla la vacía antes de terminar: si se corta, se pierde. Elegí la carpeta de Drive; el archivo que ya está no se toca."
        private const val STICKERS = "stickers"

        /**
         * La copia automática, como mucho una vez por día: con muchas notas el zip pesa cientos
         * de MB y se sube entero cada vez. "Subir ahora" la hace en el momento.
         */
        const val MIN_INTERVAL_MS = 24 * 60 * 60_000L

        /** Mientras se escribe, lo mismo. */
        const val WHILE_WRITING_MS = MIN_INTERVAL_MS

        @Volatile private var instance: CloudBackup? = null

        fun get(context: Context): CloudBackup =
            instance ?: synchronized(this) {
                instance ?: CloudBackup(context.applicationContext).also { instance = it }
            }
    }
}
