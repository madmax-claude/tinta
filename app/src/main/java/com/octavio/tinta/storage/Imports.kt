package com.octavio.tinta.storage

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.octavio.tinta.jnotes.JnotesImporter
import com.octavio.tinta.model.Background
import com.octavio.tinta.model.Note
import com.octavio.tinta.model.NoteImage
import com.octavio.tinta.model.PageLayout
import com.octavio.tinta.model.PageSize
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Videos y PDF que entran a las notas. */
object Imports {
    class Video(val file: File, val thumb: File?, val durationMs: Long, val width: Int, val height: Int)

    /** Copia el video a la carpeta de medios y saca un cuadro, la duración y el tamaño. */
    fun video(resolver: ContentResolver, uri: Uri, dir: File): Video {
        val ext = when (resolver.getType(uri)) {
            "video/webm" -> "webm"
            "video/3gpp" -> "3gp"
            "video/x-matroska" -> "mkv"
            else -> "mp4"
        }
        val name = UUID.randomUUID().toString()
        val file = File(dir, "$name.$ext")
        val tmp = File(dir, "$name.$ext.tmp")
        resolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            ?: error("no se pudo leer el video")
        tmp.renameTo(file)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 16
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 9
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation % 180 != 0) w = h.also { h = w }
            val frame = r.getFrameAtTime(min(1_000_000L, duration * 500L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            val thumb = frame?.let { f ->
                val scaled = if (max(f.width, f.height) > 960) {
                    val s = 960f / max(f.width, f.height)
                    Bitmap.createScaledBitmap(f, (f.width * s).roundToInt(), (f.height * s).roundToInt(), true)
                } else {
                    f
                }
                File(dir, "$name.jpg").also { out -> out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
            }
            return Video(file, thumb, duration, w, h)
        } finally {
            r.release()
        }
    }

    /**
     * El PDF de una nota de PDF, en su carpeta de medios. Con él, la copia en Drive no lleva las
     * páginas dibujadas ([isPage]): se vuelven a dibujar al restaurar ([redrawMissing]).
     */
    const val ORIGINAL = "original.pdf"

    private val PAGE_NAME = Regex("""pagina-(\d+)-[0-9a-f]{8}\.jpg""")

    /** El número de página (desde 1) de una página de PDF dibujada, o null si el archivo no es eso. */
    fun pageNumber(name: String): Int? = PAGE_NAME.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()

    /** Una página dibujada que se puede volver a sacar del PDF original de su nota. */
    fun isPage(f: File): Boolean = pageNumber(f.name) != null && File(f.parentFile, ORIGINAL).exists()

    /**
     * Una nota nueva con las páginas del PDF: hojas del tamaño de la primera página, y en cada hoja
     * la página como imagen bloqueada (a 2 px por dp, unos 320 ppp), para escribir encima. El PDF
     * queda como [ORIGINAL] en la carpeta de medios. `progress` recibe (página, total).
     */
    fun pdf(
        resolver: ContentResolver,
        uri: Uri,
        store: NoteStore,
        title: String,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): Note {
        // PdfRenderer necesita un archivo en el que pueda saltar: se copia primero.
        val id = store.newId()
        val dir = store.mediaDir(id)
        val copy = File(dir, ORIGINAL)
        try {
            resolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it, 1 shl 16) } }
                ?: error("no se pudo leer el PDF")
            ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf ->
                    if (pdf.pageCount == 0) error("el PDF no tiene páginas")
                    val first = pdf.openPage(0)
                    val wMm = first.width * 25.4f / 72f
                    val hMm = first.height * 25.4f / 72f
                    first.close()
                    val pages = PageLayout(PageSize.CUSTOM, false, 0f, 0f, wMm, hMm)
                    val note = Note(id, title, Background.BLANK, pages = pages)
                    for (k in 0 until pdf.pageCount) {
                        progress(k + 1, pdf.pageCount)
                        val page = pdf.openPage(k)
                        // Cada página entra en su hoja sin deformarse, centrada arriba.
                        val s = min(pages.width / page.width, pages.height / page.height)
                        val w = page.width * s
                        val h = page.height * s
                        val file = pageFile(dir, k)
                        render(page, w, h, file)
                        note.images.add(NoteImage(pages.left + (pages.width - w) / 2, pages.pageTop(k), w, h, 0f, file, locked = true))
                    }
                    return note
                }
            }
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
    }

    /**
     * Las páginas del PDF de una nota de Jnotes, cada una en su caja, como imágenes fijas.
     * `progress` recibe (página, total).
     */
    fun pdfPages(
        pdfFile: File,
        pages: List<JnotesImporter.PdfPage>,
        dir: File,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): List<NoteImage> = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { pdf ->
            pages.filter { it.index < pdf.pageCount }.mapIndexed { n, p ->
                progress(n + 1, pages.size)
                val file = pageFile(dir, p.index)
                render(pdf.openPage(p.index), p.width, p.height, file)
                NoteImage(p.left, p.top, p.width, p.height, 0f, file, locked = true)
            }
        }
    }

    /**
     * Vuelve a dibujar desde [ORIGINAL] las páginas que le faltan a la nota (después de traer una
     * copia de Drive, que no las lleva). Devuelve cuántas dibujó.
     */
    fun redrawMissing(note: Note, dir: File, progress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val original = File(dir, ORIGINAL)
        if (!original.exists()) return 0
        val missing = note.images.filter { it.locked && !it.file.exists() && pageNumber(it.file.name) != null }
        if (missing.isEmpty()) return 0
        var done = 0
        ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                missing.forEachIndexed { n, image ->
                    progress(n + 1, missing.size)
                    val k = pageNumber(image.file.name)!! - 1
                    if (k in 0 until pdf.pageCount) {
                        render(pdf.openPage(k), image.width, image.height, File(dir, image.file.name))
                        done++
                    }
                }
            }
        }
        return done
    }

    private fun pageFile(dir: File, k: Int) = File(dir, "pagina-${k + 1}-${UUID.randomUUID().toString().take(8)}.jpg")

    /** Dibuja la página (y la cierra) para una caja de `w` × `h` dp, a [PX_PER_DP], como JPG en `file`. */
    private fun render(page: PdfRenderer.Page, w: Float, h: Float, file: File) {
        try {
            val px = (w * PX_PER_DP).roundToInt().coerceIn(1, MAX_PX)
            val py = (h * px / w).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(px, py, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val m = Matrix().apply { setScale(px.toFloat() / page.width, py.toFloat() / page.height) }
            page.render(bitmap, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            bitmap.recycle()
            tmp.renameTo(file)
        } finally {
            page.close()
        }
    }

    private const val PX_PER_DP = 2f
    private const val MAX_PX = 3200
    private const val JPEG_QUALITY = 90
}
