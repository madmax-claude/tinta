package com.octavio.tinta.storage

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.octavio.tinta.export.Render
import com.octavio.tinta.model.Background
import com.octavio.tinta.model.InkStroke
import com.octavio.tinta.model.Note
import com.octavio.tinta.model.NoteImage
import com.octavio.tinta.model.NoteInfo
import com.octavio.tinta.model.NoteText
import com.octavio.tinta.model.PageLayout
import com.octavio.tinta.model.Sensitivity
import com.octavio.tinta.model.Tool
import com.octavio.tinta.model.Widget
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Una nota por archivo en `files/notes/<id>.tinta`, con su miniatura en `<id>.png`. Todas las
 * escrituras pasan por un único hilo, así nunca se pisan dos guardados de la misma nota.
 */
class NoteStore private constructor(context: Context) {
    private val dir = File(context.filesDir, "notes").apply { mkdirs() }

    /** Carpetas. */
    val library = Library(File(dir, "carpetas.json"))

    /** Hilo de disco. También sirve para cargar e importar sin trabar la pantalla. */
    val io: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "tinta-io") }

    fun newId(): String = UUID.randomUUID().toString()

    /** Una nota vacía, ya guardada y en la carpeta `folder`. El editor la puede abrir enseguida. */
    fun create(title: String, background: Background, pages: PageLayout, folder: String?, paperColor: Int? = null): Note {
        val note = Note(newId(), title, background, pages = pages, paperColor = paperColor)
        save(note, withThumbnail = false)
        io.execute { library.move(note.id, folder) } // en el mismo hilo, antes de que el editor la cargue
        return note
    }

    fun file(id: String) = File(dir, "$id.tinta")

    fun thumbnail(id: String) = File(dir, "$id.png")

    /** Foto de la nota para abrirla rápido. Se puede borrar: el `.tinta` la vuelve a armar. */
    fun vista(id: String) = File(dir, "$id.vista")

    fun list(): List<NoteInfo> = readList(dir, library::folderOf)

    /** Imágenes, audios y videos de la nota: `notes/<id>.media/`. */
    fun mediaDir(id: String) = File(dir, "$id.media").apply { mkdirs() }

    /**
     * Lee la nota sin armar las mallas (eso lo hace [MeshBuilder] en segundo plano). Las imágenes
     * de las notas viejas, que venían adentro del archivo, pasan a la carpeta de medios.
     */
    fun load(id: String): Note = readNote(file(id), id, mediaDir(id))

    /**
     * Borra los medios que la nota ya no usa. Sólo al abrirla en el editor, cuando no hay historial
     * que los pueda recuperar ni otra pantalla con la nota en memoria. Llamar desde [io].
     * Si quedó JSON de widgets sin leer, no borra nada: ese JSON puede nombrar un archivo.
     */
    fun cleanMedia(note: Note, keep: Collection<File> = emptyList()) {
        unusedMedia(note, mediaDir(note.id), keep).forEach { it.delete() }
    }

    /**
     * Copia el estado de la nota ahora (desde el hilo de la UI) y la escribe en el hilo de disco.
     * El futuro termina cuando el archivo ya se escribió (sync y rename, y la miniatura si va):
     * normal si salió bien, con error si falló. `onError` se llama en el hilo de disco, antes de
     * que el futuro falle. La nota se escribe antes que la miniatura, así un corte en el medio
     * deja los trazos ya en disco.
     */
    fun save(note: Note, withThumbnail: Boolean, onError: (Throwable) -> Unit = {}): CompletableFuture<Unit> {
        val id = note.id
        val header = header(note)
        val strokes = note.strokes.toList()
        val images = note.images.toList()
        val texts = note.texts.toList()
        val widgets = note.widgets.toList()
        val preserved = note.preservedWidgets.map { it.copyOf() }
        return EditorSave.enqueueWrite(io, onError) {
            write(id, header, strokes, images, texts, widgets, preserved)
            if (withThumbnail) {
                writeThumbnail(id, Render.thumbnail(strokes, images, texts, widgets, header.background, header.pages, 480, 360, header.paperColor))
            }
        }
    }

    fun header(note: Note) = NoteFormat.Header(
        note.title, note.background, note.created, note.modified, note.viewport, note.strokes.size, note.pages,
        paperColor = note.paperColor,
    )

    /**
     * Escribe la nota completa de forma atómica. Llamar desde [io].
     * [preservedWidgets] es el JSON que no se pudo leer: no omitirlo si la nota salió de [readNote].
     */
    fun write(
        id: String,
        header: NoteFormat.Header,
        strokes: List<InkStroke>,
        images: List<NoteImage> = emptyList(),
        texts: List<NoteText> = emptyList(),
        widgets: List<Widget> = emptyList(),
        preservedWidgets: List<ByteArray> = emptyList(),
    ) = writeNote(file(id), header, strokes, images, texts, widgets, preservedWidgets)

    fun writeThumbnail(id: String, bitmap: Bitmap) {
        val dest = thumbnail(id)
        AtomicFile.write(dest) { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IOException("no se pudo guardar ${dest.name}")
            }
        }
    }

    fun delete(id: String) {
        file(id).delete()
        thumbnail(id).delete()
        vista(id).delete()
        File(dir, "$id.vista.tmp").delete()
        File(dir, "$id.media").deleteRecursively()
        File(dir, "$id.texto").delete() // lo leído de la letra, para buscar
        library.forget(id)
    }

    companion object {
        private const val TAG = "Tinta"

        /** Lee una nota (o un sticker) de `file`, con sus medios en `media`. */
        fun readNote(file: File, id: String, media: File): Note = BufferedInputStream(file.inputStream(), 1 shl 16).use { bin ->
            val t0 = System.nanoTime()
            val h = NoteFormat.readHeader(DataInputStream(bin))
            val note = Note(id, h.title, h.background, h.created, h.modified, h.viewport, h.pages, paperColor = h.paperColor)
            NoteFormat.strokeInput(bin).use { data ->
                repeat(h.strokeCount) {
                    val r = NoteFormat.readStroke(data)
                    note.strokes.add(
                        InkStroke.unbuilt(
                            Tool.fromId(NoteFormat.unpackTool(r.tool)), r.color, r.size, r.epsilon,
                            r.left, r.top, r.right, r.bottom, r.inputs,
                            NoteFormat.unpackSensitivity(r.tool, Sensitivity.DEFAULT),
                        ),
                    )
                }
                if (h.version >= 2) {
                    NoteFormat.readElements(
                        data, h.version, media, note.images, note.texts, note.widgets, note.preservedWidgets,
                    ) { keep(media, it) }
                }
                if (h.version >= 9) {
                    for ((i, g) in NoteFormat.readGroups(data, note.strokes.size)) note.strokes[i].group = g
                }
            }
            val extra = if (note.preservedWidgets.isEmpty()) "" else ", ${note.preservedWidgets.size} widgets sin leer"
            Log.i(TAG, "«${h.title}»: ${note.strokes.size} trazos leídos$extra en ${(System.nanoTime() - t0) / 1_000_000} ms")
            note
        }

        /**
         * Una entrada por cada `.tinta`. Si la cabecera no se puede leer, la nota queda marcada
         * como dañada y el archivo no se toca: no desaparece de la lista ni se borra.
         */
        internal fun readList(dir: File, folderOf: (String) -> String? = { null }): List<NoteInfo> =
            dir.listFiles { f -> f.isFile && f.name.endsWith(".tinta") }.orEmpty()
                .map { f ->
                    val id = f.name.removeSuffix(".tinta")
                    runCatching {
                        DataInputStream(BufferedInputStream(f.inputStream(), 512)).use { input ->
                            val h = NoteFormat.readHeader(input)
                            NoteInfo(id, h.title, h.modified, h.strokeCount, folderOf(id))
                        }
                    }.getOrElse {
                        NoteInfo(id, "", f.lastModified(), 0, folderOf(id), damaged = true)
                    }
                }
                .sortedByDescending { it.modified }

        /**
         * Medios de [dir] que la nota ya no usa. Vacío si quedó JSON de widgets sin leer:
         * pueden nombrar un archivo, y borrarlo lo perdería.
         */
        internal fun unusedMedia(note: Note, dir: File, keep: Collection<File> = emptyList()): List<File> {
            if (note.preservedWidgets.isNotEmpty()) return emptyList()
            val used = HashSet<String>()
            // Lo copiado o cortado con el lazo puede venir de esta nota y todavía no se pegó.
            keep.filter { it.parentFile == dir }.mapTo(used) { it.name }
            note.images.mapTo(used) { it.file.name }
            used.add(Imports.ORIGINAL) // de donde salen las páginas de un PDF
            note.widgets.flatMapTo(used) { w -> w.files.map { it.name } }
            return dir.listFiles().orEmpty().filter { it.name !in used && !it.name.endsWith(".tmp") }
        }

        /** Escribe una nota (o un sticker) en `target` de forma atómica. */
        fun writeNote(
            target: File,
            header: NoteFormat.Header,
            strokes: List<InkStroke>,
            images: List<NoteImage>,
            texts: List<NoteText>,
            widgets: List<Widget>,
            preservedWidgets: List<ByteArray> = emptyList(),
        ) {
            val tmp = File(target.parentFile, target.name + ".tmp")
            val fos = FileOutputStream(tmp)
            val deflater = Deflater(Deflater.BEST_SPEED)
            try {
                val bos = BufferedOutputStream(fos, 1 shl 16)
                val richText = texts.any { it.font != NoteText.DEFAULT_FONT || it.clip }
                val version = NoteFormat.versionFor(
                    strokes.any { it.group != 0L }, header.pages.horizontal, richText, header.pages.hasSheetMeta(),
                )
                NoteFormat.writeHeader(DataOutputStream(bos), header.copy(version = version, strokeCount = strokes.size))
                val compressed = DeflaterOutputStream(bos, deflater, 1 shl 16)
                // Sin búfer, DataOutputStream le pasa al compresor un byte por vez.
                val out = DataOutputStream(BufferedOutputStream(compressed, 1 shl 16))
                for (s in strokes) {
                    NoteFormat.writeStroke(
                        out,
                        NoteFormat.RawStroke(
                            NoteFormat.packTool(s.tool.id, s.sensitivity), s.color, s.size, s.epsilon,
                            s.left, s.top, s.right, s.bottom, s.encoded(),
                        ),
                    )
                }
                // La 12 también lleva la letra y el recorte de cada texto: quien la lee mira la versión.
                NoteFormat.writeElements(out, images, texts, widgets, version >= 11, preservedWidgets)
                if (version >= 9) NoteFormat.writeGroups(out, strokes.map { it.group })
                out.flush()
                compressed.finish()
                bos.flush()
                fos.fd.sync() // que un corte de energía no deje el archivo a medias antes del rename
            } finally {
                fos.close()
                deflater.end()
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                error("no se pudo guardar ${target.name}")
            }
        }

        /**
         * Guarda los bytes de una imagen en `dir` con un nombre sacado del contenido: la misma imagen
         * dos veces es un solo archivo, y repetir una migración que se cortó no duplica nada.
         */
        fun keep(dir: File, bytes: ByteArray): File {
            val hash = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(20)
            val f = File(dir, "$hash.img")
            if (!f.exists() || f.length() != bytes.size.toLong()) {
                AtomicFile.write(f) { it.write(bytes) }
            }
            return f
        }

        /** El archivo de otra carpeta de medios copiado a `dir` (con el mismo nombre), o él mismo si ya está. */
        fun copyMedia(file: File, dir: File): File {
            if (file.parentFile == dir) return file
            val f = File(dir, file.name)
            if (!f.exists() && file.exists()) {
                AtomicFile.write(f) { out -> file.inputStream().use { input -> input.copyTo(out) } }
            }
            return f
        }

        @Volatile private var instance: NoteStore? = null

        fun get(context: Context): NoteStore =
            instance ?: synchronized(this) {
                instance ?: NoteStore(context.applicationContext).also { instance = it }
            }
    }
}
