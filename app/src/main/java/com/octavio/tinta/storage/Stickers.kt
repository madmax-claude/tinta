package com.octavio.tinta.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.octavio.tinta.export.Render
import com.octavio.tinta.model.Background
import com.octavio.tinta.model.Clipboard
import com.octavio.tinta.model.Viewport
import java.io.File
import java.util.UUID

/**
 * Stickers hechos a mano: lo elegido con el lazo, guardado aparte para pegarlo en cualquier nota.
 * Cada uno es un archivo con el formato de las notas en `files/stickers/`, con su miniatura y sus
 * imágenes en `stickers/media/`.
 */
class Stickers(context: Context) {
    private val dir = File(context.filesDir, "stickers").apply { mkdirs() }
    private val media = File(dir, "media").apply { mkdirs() }

    class Sticker(val id: String, val file: File, val thumbnail: File)

    fun list(): List<Sticker> = dir.listFiles { f -> f.name.endsWith(".tinta") }.orEmpty()
        .sortedByDescending { it.lastModified() }
        .map { f -> f.name.removeSuffix(".tinta").let { id -> Sticker(id, f, File(dir, "$id.png")) } }

    /** Guarda lo elegido como sticker. Llamar fuera del hilo de la pantalla. */
    fun save(clip: Clipboard.Clip): Sticker {
        val id = UUID.randomUUID().toString()
        val images = clip.images.map { it.withFile(NoteStore.copyMedia(it.file, media)) }
        val widgets = clip.widgets.map { w -> w.withFiles { NoteStore.copyMedia(it, media) } }
        val header = NoteFormat.Header("sticker", Background.BLANK, System.currentTimeMillis(), System.currentTimeMillis(), Viewport(), clip.strokes.size)
        val file = File(dir, "$id.tinta")
        NoteStore.writeNote(file, header, clip.strokes, images, clip.texts, widgets)
        val thumb = File(dir, "$id.png")
        val bitmap = Render.fit(clip.strokes, images, clip.texts, widgets, THUMB)
        thumb.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Sticker(id, file, thumb)
    }

    /** El sticker listo para pegar (como lo copiado con el lazo). */
    fun load(s: Sticker): Clipboard.Clip {
        val n = NoteStore.readNote(s.file, s.id, media)
        val b = Render.bounds(n.strokes, n.images, n.texts, n.widgets) ?: RectF()
        return Clipboard.Clip(n.strokes, n.images, n.texts, n.widgets, b)
    }

    fun delete(s: Sticker) {
        s.file.delete()
        s.thumbnail.delete()
        // Los archivos de medios que ya no usa ningún sticker se van.
        val used = list().flatMap { st -> runCatching { load(st) }.getOrNull()?.let { c -> c.images.map { it.file.name } + c.widgets.flatMap { w -> w.files.map { it.name } } }.orEmpty() }.toSet()
        media.listFiles().orEmpty().filter { it.name !in used }.forEach { it.delete() }
    }

    private companion object {
        const val THUMB = 240
    }
}
