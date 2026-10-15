package com.octavio.tinta.model

import java.io.File

/** Fondo de la hoja. `id` es el valor que se guarda en el archivo; no cambiarlo. */
enum class Background(val id: Int, val label: String) {
    BLANK(3, "Liso"),
    GRID_SMALL(4, "Cuadrícula chica"),
    GRID(0, "Cuadrícula"),
    GRID_LARGE(5, "Cuadrícula grande"),
    GRAPH(6, "Milimetrado"),
    LINES(1, "Rayado"),
    LINES_WIDE(7, "Rayado ancho"),
    DOTS_SMALL(10, "Puntos chicos"),
    DOTS(2, "Puntos"),
    ISOMETRIC(8, "Isométrica"),
    HEX(11, "Hexágonos"),
    MUSIC(9, "Pentagrama"),
    ERRORS(12, "Hoja de errores");

    companion object {
        fun fromId(id: Int): Background = entries.firstOrNull { it.id == id } ?: GRID
    }
}

/**
 * Vista guardada con la nota: punto del mundo en la esquina superior izquierda, zoom y el tamaño
 * de la ventana en px (0 si no se sabe), para abrirla en otra ventana mirando el mismo centro.
 */
data class Viewport(val x: Float = 0f, val y: Float = 0f, val zoom: Float = 1f, val width: Float = 0f, val height: Float = 0f)

class Note(
    val id: String,
    var title: String,
    var background: Background = Background.GRID,
    val created: Long = System.currentTimeMillis(),
    var modified: Long = created,
    var viewport: Viewport = Viewport(),
    var pages: PageLayout = PageLayout(),
    val strokes: MutableList<InkStroke> = mutableListOf(),
    val images: MutableList<NoteImage> = mutableListOf(),
    val texts: MutableList<NoteText> = mutableListOf(),
    /** Gráficos, figuras 3D, notas de voz y videos. */
    val widgets: MutableList<Widget> = mutableListOf(),
)

/**
 * Imagen pegada (desde la galería, desde Jnotes o una página de un PDF). Caja en el mundo y giro en
 * grados alrededor del centro; el PNG/JPG está en `file`, en la carpeta de medios de la nota, así
 * guardar la nota no reescribe las imágenes. Se dibuja debajo de la tinta. `locked`: la página de
 * un PDF, que es parte de la hoja y no se elige con el lazo.
 */
class NoteImage(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
    val file: File,
    val locked: Boolean = false,
) {
    val centerX get() = left + width / 2
    val centerY get() = top + height / 2

    /** La misma imagen con p' = a·R(θ)·p + d: el centro se mueve, el tamaño se escala y el giro se suma. */
    fun transformed(t: Transform): NoteImage {
        val cx = t.x(centerX, centerY)
        val cy = t.y(centerX, centerY)
        val w = width * t.a
        val h = height * t.a
        return NoteImage(cx - w / 2, cy - h / 2, w, h, (rotation + t.degrees) % 360f, file, locked)
    }

    fun withFile(f: File) = NoteImage(left, top, width, height, rotation, f, locked)
}

/** Cuadro de texto (desde Jnotes). Se dibuja encima de la tinta, como en Jnotes; no gira. */
class NoteText(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val text: String,
    val size: Float,
    val color: Int,
) {
    fun transformed(t: Transform, color: Int = this.color): NoteText {
        val r = Transform.upright(t, left, top, width, height)
        return NoteText(r[0], r[1], r[2], r[3], text, size * t.a, color)
    }
}

/** Lo que muestra la lista de notas sin decodificar los trazos. */
data class NoteInfo(val id: String, val title: String, val modified: Long, val strokeCount: Int, val folder: String? = null)
