package com.octavio.tinta.storage

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
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
     * Una nota nueva con las páginas del PDF: hojas del tamaño de la primera página, y en cada hoja
     * la página como imagen bloqueada (a 2 px por dp, unos 320 ppp), para escribir encima.
     * `progress` recibe (página, total).
     */
    fun pdf(
        resolver: ContentResolver,
        uri: Uri,
        store: NoteStore,
        title: String,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): Note {
        // PdfRenderer necesita un archivo en el que pueda saltar: se copia primero.
        val copy = File.createTempFile("importar", ".pdf")
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
                    val note = Note(store.newId(), title, Background.BLANK, pages = pages)
                    val dir = store.mediaDir(note.id)
                    for (k in 0 until pdf.pageCount) {
                        progress(k + 1, pdf.pageCount)
                        val page = pdf.openPage(k)
                        // Cada página entra en su hoja sin deformarse, centrada arriba.
                        val s = min(pages.width / page.width, pages.height / page.height)
                        val w = page.width * s
                        val h = page.height * s
                        val px = (w * PX_PER_DP).roundToInt().coerceAtMost(MAX_PX)
                        val py = (h * px / w).roundToInt()
                        val bitmap = Bitmap.createBitmap(px, py, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        val m = Matrix().apply { setScale(px.toFloat() / page.width, py.toFloat() / page.height) }
                        page.render(bitmap, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        val file = File(dir, "pagina-${k + 1}-${UUID.randomUUID().toString().take(8)}.jpg")
                        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                        bitmap.recycle()
                        note.images.add(NoteImage(pages.left + (pages.width - w) / 2, pages.pageTop(k), w, h, 0f, file, locked = true))
                    }
                    return note
                }
            }
        } finally {
            copy.delete()
        }
    }

    private const val PX_PER_DP = 2f
    private const val MAX_PX = 3200
    private const val JPEG_QUALITY = 90
}
