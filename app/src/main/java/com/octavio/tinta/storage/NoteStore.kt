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
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
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

    fun list(): List<NoteInfo> = dir.listFiles { f -> f.name.endsWith(".tinta") }.orEmpty()
        .mapNotNull { f ->
            runCatching {
                DataInputStream(BufferedInputStream(f.inputStream(), 512)).use { input ->
                    val h = NoteFormat.readHeader(input)
                    val id = f.name.removeSuffix(".tinta")
                    NoteInfo(id, h.title, h.modified, h.strokeCount, library.folderOf(id))
                }
            }.getOrNull()
        }
        .sortedByDescending { it.modified }

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
     */
    fun cleanMedia(note: Note, keep: Collection<File> = emptyList()) {
        val used = HashSet<String>()
        // Lo copiado o cortado con el lazo puede venir de esta nota y todavía no se pegó.
        keep.filter { it.parentFile == mediaDir(note.id) }.mapTo(used) { it.name }
        note.images.mapTo(used) { it.file.name }
        note.widgets.flatMapTo(used) { w -> w.files.map { it.name } }
        mediaDir(note.id).listFiles().orEmpty().filter { it.name !in used && !it.name.endsWith(".tmp") }.forEach { it.delete() }
    }

    /**
     * Copia el estado de la nota ahora (desde el hilo de la UI) y la escribe en el hilo de disco.
     * `onError` se llama en ese hilo.
     */
    fun save(note: Note, withThumbnail: Boolean, onError: (Throwable) -> Unit = {}) {
        val id = note.id
        val header = header(note)
        val strokes = note.strokes.toList()
        val images = note.images.toList()
        val texts = note.texts.toList()
        val widgets = note.widgets.toList()
        io.execute {
            runCatching {
                write(id, header, strokes, images, texts, widgets)
                if (withThumbnail) {
                    writeThumbnail(id, Render.thumbnail(strokes, images, texts, widgets, header.background, header.pages, 480, 360, header.paperColor))
                }
            }.onFailure(onError)
        }
    }

    fun header(note: Note) = NoteFormat.Header(
        note.title, note.background, note.created, note.modified, note.viewport, note.strokes.size, note.pages,
        paperColor = note.paperColor,
    )

    /** Escribe la nota completa de forma atómica. Llamar desde [io]. */
    fun write(
        id: String,
        header: NoteFormat.Header,
        strokes: List<InkStroke>,
        images: List<NoteImage> = emptyList(),
        texts: List<NoteText> = emptyList(),
        widgets: List<Widget> = emptyList(),
    ) = writeNote(file(id), header, strokes, images, texts, widgets)

    fun writeThumbnail(id: String, bitmap: Bitmap) {
        val tmp = File(dir, "$id.png.tmp")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(thumbnail(id))
    }

    fun delete(id: String) {
        file(id).delete()
        thumbnail(id).delete()
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
                    NoteFormat.readElements(data, h.version, media, note.images, note.texts, note.widgets) { keep(media, it) }
                }
                if (h.version >= 9) {
                    for ((i, g) in NoteFormat.readGroups(data, note.strokes.size)) note.strokes[i].group = g
                }
            }
            Log.i(TAG, "«${h.title}»: ${note.strokes.size} trazos leídos en ${(System.nanoTime() - t0) / 1_000_000} ms")
            note
        }

        /** Escribe una nota (o un sticker) en `target` de forma atómica. */
        fun writeNote(
            target: File,
            header: NoteFormat.Header,
            strokes: List<InkStroke>,
            images: List<NoteImage>,
            texts: List<NoteText>,
            widgets: List<Widget>,
        ) {
            val tmp = File(target.parentFile, target.name + ".tmp")
            val fos = FileOutputStream(tmp)
            val deflater = Deflater(Deflater.BEST_SPEED)
            try {
                val bos = BufferedOutputStream(fos, 1 shl 16)
                NoteFormat.writeHeader(DataOutputStream(bos), header.copy(version = NoteFormat.VERSION, strokeCount = strokes.size))
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
                NoteFormat.writeElements(out, images, texts, widgets)
                NoteFormat.writeGroups(out, strokes.map { it.group })
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
                val tmp = File(dir, "$hash.img.tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(f)
            }
            return f
        }

        /** El archivo de otra carpeta de medios copiado a `dir` (con el mismo nombre), o él mismo si ya está. */
        fun copyMedia(file: File, dir: File): File {
            if (file.parentFile == dir) return file
            val f = File(dir, file.name)
            if (!f.exists() && file.exists()) {
                val tmp = File(dir, file.name + ".tmp")
                file.copyTo(tmp, overwrite = true)
                tmp.renameTo(f)
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
