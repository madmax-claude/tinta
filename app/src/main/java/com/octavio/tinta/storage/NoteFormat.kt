package com.octavio.tinta.storage

import com.octavio.tinta.model.Background
import com.octavio.tinta.model.NoteImage
import com.octavio.tinta.model.NoteText
import com.octavio.tinta.model.PageLayout
import com.octavio.tinta.model.PageSize
import com.octavio.tinta.model.Viewport
import com.octavio.tinta.model.Widget
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.InflaterInputStream

/**
 * Formato `.tinta`, big-endian con DataOutputStream:
 *
 *     UTF "TINTA", int versión
 *     UTF título, int fondo, long creada, long modificada
 *     float vista.x, float vista.y, float vista.zoom
 *     int cantidad de trazos
 *     desde la versión 3: int tamaño de hoja, boolean apaisada, float izquierda, float arriba
 *         (dónde empieza la primera hoja; ver [PageLayout])
 *     desde la versión 4: float ancho y alto en mm de la hoja a medida (la de un PDF)
 *     desde la versión 5: int hojas agregadas a mano (ver [PageLayout.minPages])
 *     desde la versión 6: float ancho y alto en px de la ventana donde se guardó la vista
 *     desde la versión 7, el byte de herramienta de cada trazo lleva en los 4 bits altos la
 *         sensibilidad a la presión + 1 (0: la de siempre; ver [packTool])
 *     y, comprimido con deflate, por cada trazo:
 *         byte herramienta, int color ARGB, float tamaño, float epsilon,
 *         float izquierda, arriba, derecha, abajo (caja en el mundo, para no armar la malla),
 *         int largo, puntos en el formato de [StrokeCodec]
 *     desde la versión 2, a continuación y en el mismo bloque:
 *         int cantidad de imágenes, y por cada una: float izquierda, arriba, ancho, alto, giro,
 *             int largo, bytes del PNG/JPG
 *         int cantidad de textos, y por cada uno: float izquierda, arriba, ancho, alto, tamaño,
 *             int color, int largo, texto en UTF-8
 *     desde la versión 4 las imágenes no van adentro: por cada una, float izquierda, arriba, ancho,
 *         alto, giro, byte banderas (1: bloqueada), UTF nombre del archivo en la carpeta de medios
 *         de la nota; y después de los textos, int cantidad de widgets y por cada uno int largo y
 *         su JSON en UTF-8 (ver [Widget])
 *
 * La cabecera va sin comprimir y antes que los trazos, para que la lista de notas la lea sola.
 */
object NoteFormat {
    const val MAGIC = "TINTA"
    const val VERSION = 9

    /**
     * La versión con que se guarda una nota: la 9 sólo si tiene líneas a rayas o de puntos (el
     * bloque de líneas); si no, la 8, que también abren las versiones anteriores de la app.
     */
    fun versionFor(hasGroups: Boolean) = if (hasGroups) VERSION else 8

    data class Header(
        val title: String,
        val background: Background,
        val created: Long,
        val modified: Long,
        val viewport: Viewport,
        val strokeCount: Int,
        val pages: PageLayout = PageLayout(),
        val version: Int = VERSION,
        /** Desde la versión 8: el color de la hoja, o null para el de siempre. */
        val paperColor: Int? = null,
    )

    /** Un trazo tal como está en el archivo, sin convertir a objetos de Ink. */
    class RawStroke(
        val tool: Int,
        val color: Int,
        val size: Float,
        val epsilon: Float,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val inputs: ByteArray,
    )

    fun writeHeader(out: DataOutputStream, h: Header) {
        out.writeUTF(MAGIC)
        out.writeInt(h.version)
        out.writeUTF(h.title)
        out.writeInt(h.background.id)
        out.writeLong(h.created)
        out.writeLong(h.modified)
        out.writeFloat(h.viewport.x)
        out.writeFloat(h.viewport.y)
        out.writeFloat(h.viewport.zoom)
        out.writeInt(h.strokeCount)
        out.writeInt(h.pages.size.id)
        out.writeBoolean(h.pages.landscape)
        out.writeFloat(h.pages.left)
        out.writeFloat(h.pages.top)
        out.writeFloat(h.pages.customWidth)
        out.writeFloat(h.pages.customHeight)
        out.writeInt(h.pages.minPages)
        out.writeFloat(h.viewport.width)
        out.writeFloat(h.viewport.height)
        out.writeInt(h.paperColor ?: 0)
    }

    fun readHeader(input: DataInputStream): Header {
        val magic = input.readUTF()
        if (magic != MAGIC) throw IOException("no es una nota de Tinta (cabecera \"$magic\")")
        val version = input.readInt()
        if (version > VERSION) throw IOException("nota de una versión más nueva ($version)")
        val title = input.readUTF()
        val background = Background.fromId(input.readInt())
        val created = input.readLong()
        val modified = input.readLong()
        var viewport = Viewport(input.readFloat(), input.readFloat(), input.readFloat())
        val strokeCount = input.readInt()
        var pages = if (version >= 3) {
            PageLayout(PageSize.fromId(input.readInt()), input.readBoolean(), input.readFloat(), input.readFloat())
        } else {
            PageLayout()
        }
        if (version >= 4) pages = pages.copy(customWidth = input.readFloat(), customHeight = input.readFloat())
        if (version >= 5) pages = pages.copy(minPages = input.readInt().coerceIn(0, MAX_PAGES))
        if (version >= 6) viewport = viewport.copy(width = input.readFloat(), height = input.readFloat())
        val paperColor = if (version >= 8) input.readInt().takeIf { it != 0 } else null
        return Header(title, background, created, modified, viewport, strokeCount, pages, version, paperColor)
    }

    /** Herramienta y sensibilidad en un byte. */
    fun packTool(tool: Int, sensitivity: Int) = (tool and 0x0F) or ((sensitivity.coerceIn(0, 14) + 1) shl 4)

    fun unpackTool(b: Int) = b and 0x0F

    /** La sensibilidad guardada, o `default` en los trazos que no la tienen. */
    fun unpackSensitivity(b: Int, default: Int) = ((b shr 4) - 1).takeIf { it >= 0 } ?: default

    fun writeStroke(out: DataOutputStream, s: RawStroke) {
        out.writeByte(s.tool)
        out.writeInt(s.color)
        out.writeFloat(s.size)
        out.writeFloat(s.epsilon)
        out.writeFloat(s.left)
        out.writeFloat(s.top)
        out.writeFloat(s.right)
        out.writeFloat(s.bottom)
        out.writeInt(s.inputs.size)
        out.write(s.inputs)
    }

    fun readStroke(input: DataInputStream): RawStroke {
        val tool = input.readUnsignedByte()
        val color = input.readInt()
        val size = input.readFloat()
        val epsilon = input.readFloat()
        val left = input.readFloat()
        val top = input.readFloat()
        val right = input.readFloat()
        val bottom = input.readFloat()
        val n = input.readInt()
        if (n < 0 || n > MAX_STROKE_BYTES) throw IOException("trazo dañado ($n bytes)")
        val inputs = ByteArray(n)
        input.readFully(inputs)
        return RawStroke(tool, color, size, epsilon, left, top, right, bottom, inputs)
    }

    fun writeElements(out: DataOutputStream, images: List<NoteImage>, texts: List<NoteText>, widgets: List<Widget>) {
        out.writeInt(images.size)
        for (i in images) {
            out.writeFloat(i.left)
            out.writeFloat(i.top)
            out.writeFloat(i.width)
            out.writeFloat(i.height)
            out.writeFloat(i.rotation)
            out.writeByte(if (i.locked) LOCKED else 0)
            out.writeUTF(i.file.name)
        }
        out.writeInt(texts.size)
        for (t in texts) {
            out.writeFloat(t.left)
            out.writeFloat(t.top)
            out.writeFloat(t.width)
            out.writeFloat(t.height)
            out.writeFloat(t.size)
            out.writeInt(t.color)
            val bytes = t.text.toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
        }
        out.writeInt(widgets.size)
        for (w in widgets) {
            val bytes = w.toJson().toString().toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
        }
    }

    /**
     * Lee imágenes, textos y widgets. En las versiones 2 y 3 las imágenes venían adentro:
     * `keep` guarda sus bytes en la carpeta de medios y devuelve el archivo.
     */
    fun readElements(
        input: DataInputStream,
        version: Int,
        mediaDir: File,
        images: MutableList<NoteImage>,
        texts: MutableList<NoteText>,
        widgets: MutableList<Widget>,
        keep: (ByteArray) -> File,
    ) {
        repeat(input.readInt()) {
            val l = input.readFloat()
            val t = input.readFloat()
            val w = input.readFloat()
            val h = input.readFloat()
            val rotation = input.readFloat()
            if (version >= 4) {
                val flags = input.readUnsignedByte()
                images.add(NoteImage(l, t, w, h, rotation, File(mediaDir, input.readUTF()), flags and LOCKED != 0))
            } else {
                images.add(NoteImage(l, t, w, h, rotation, keep(blob(input))))
            }
        }
        repeat(input.readInt()) {
            val l = input.readFloat()
            val t = input.readFloat()
            val w = input.readFloat()
            val h = input.readFloat()
            val size = input.readFloat()
            val color = input.readInt()
            texts.add(NoteText(l, t, w, h, String(blob(input), Charsets.UTF_8), size, color))
        }
        if (version < 4) return
        repeat(input.readInt()) {
            val json = String(blob(input), Charsets.UTF_8)
            runCatching { Widget.fromJson(JSONObject(json), mediaDir) }.getOrNull()?.let(widgets::add)
        }
    }

    private const val LOCKED = 1

    private fun blob(input: DataInputStream): ByteArray {
        val n = input.readInt()
        if (n < 0 || n > MAX_STROKE_BYTES) throw IOException("elemento dañado ($n bytes)")
        return ByteArray(n).also { input.readFully(it) }
    }

    /**
     * Desde la versión 9, al final: qué trazos son rayas de una misma línea. Sólo los que tienen
     * número (índice en la lista de trazos y número).
     */
    fun writeGroups(out: DataOutputStream, groups: List<Long>) {
        val marked = groups.withIndex().filter { it.value != 0L }
        out.writeInt(marked.size)
        for ((i, g) in marked) {
            out.writeInt(i)
            out.writeLong(g)
        }
    }

    /** Lo de [writeGroups]: número de cada trazo que tiene uno (índices fuera de rango, ignorados). */
    fun readGroups(input: DataInputStream, strokeCount: Int): Map<Int, Long> {
        val n = input.readInt()
        if (n < 0 || n > strokeCount) throw IOException("líneas dañadas ($n)")
        val out = HashMap<Int, Long>(n * 2)
        repeat(n) {
            val i = input.readInt()
            val g = input.readLong()
            if (i in 0 until strokeCount) out[i] = g
        }
        return out
    }

    /** Los trazos van después de la cabecera, en un único bloque deflate. */
    fun strokeInput(input: InputStream) = DataInputStream(BufferedInputStream(InflaterInputStream(input), 1 shl 16))

    private const val MAX_STROKE_BYTES = 64 shl 20
    private const val MAX_PAGES = 10_000
}
