package com.octavio.tinta.jnotes

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Lector de notas de Jnotes (com.jideos.jnotes), pasado de `~/jnotes2pdf/jnotes2pdf.py`, donde
 * está documentado el formato:
 *
 *   .Jnotes = ZIP con un único `zip.Jzip`.
 *   .Jzip   = flujo big-endian estilo DataOutputStream: UTF "TRY", int versión, UTF id_nota, y
 *             mientras int == 1: UTF tipo, UTF origen, UTF id, long largo_json, UTF id_padre,
 *             UTF ruta, UTF largo_blob, json, blob.
 *
 * Los puntos de un trazo son relativos a un desplazamiento (h, i): posición = punto - (h, i).
 * Se lee todo en flujo: una nota real tiene ~60 MB de JSON y ~500.000 puntos de 15 campos.
 */
object JnotesFile {

    class JStroke(
        val tool: Int,
        val color: Int,
        val width: Float,
        val order: Long,
        val xs: FloatArray,
        val ys: FloatArray,
        val pressures: FloatArray,
    )

    /** Imagen pegada: esquina (x - i, y - j), tamaño (c, d), giro `e` en grados; `bytes` es el PNG/JPG. */
    class JImage(val x: Float, val y: Float, val width: Float, val height: Float, val rotation: Float, val bytes: ByteArray)

    /** Cuadro de texto: esquina (x - t, y - u), tamaño (c, d), texto `e`, letra `g`, color `f`. */
    class JText(val x: Float, val y: Float, val width: Float, val height: Float, val text: String, val size: Float, val color: Int)

    /**
     * `viewX`/`viewY`/`viewZoom`: la última vista en Jnotes (campos `m`, `n`, `o` de la página, con
     * la esquina superior izquierda en (-m, -n)), o NaN si no está.
     */
    class JPage(
        val width: Float,
        val height: Float,
        val strokes: List<JStroke>,
        val images: List<JImage> = emptyList(),
        val texts: List<JText> = emptyList(),
        val viewX: Float = Float.NaN,
        val viewY: Float = Float.NaN,
        val viewZoom: Float = Float.NaN,
    )

    private class PageMeta(val width: Float, val height: Float, val viewX: Float, val viewY: Float, val viewZoom: Float)

    class JNote(val title: String, val pages: List<JPage>)

    fun parse(data: ByteArray): JNote = parse(ByteArrayInputStream(data))

    fun parse(input: InputStream): JNote {
        val src = ByteSource(unzip(input))
        val magic = src.utf()
        if (magic != "TRY") throw IOException("no parece un archivo de Jnotes (cabecera \"$magic\")")
        src.i32()
        src.utf()

        var title = ""
        val pageMeta = LinkedHashMap<String, PageMeta>()
        val strokes = LinkedHashMap<String, MutableList<JStroke>>()
        val images = LinkedHashMap<String, MutableList<JImage>>()
        val texts = LinkedHashMap<String, MutableList<JText>>()
        val points = PointBuffer()
        while (true) {
            val more = runCatching { src.i32() }.getOrDefault(0)
            if (more != 1) break
            val type = src.utf()
            src.utf() // origen
            val id = src.utf()
            val jsonLength = src.i64()
            src.utf() // id del padre
            src.utf() // ruta
            val blobLength = src.utf().toLongOrNull()?.takeIf { it >= 0 } ?: 0L
            if (jsonLength < 0) throw IOException("registro $type dañado")

            src.remaining = jsonLength
            val json = JsonPull(src)
            var image: Fields? = null
            when (type) {
                "NOTE" -> json.obj { k -> if (k == "b" && title.isEmpty()) title = json.string() else json.skipValue() }
                "PAGE" -> {
                    var w = 1240f
                    var h = 1754f
                    var m = Float.NaN
                    var n = Float.NaN
                    var o = Float.NaN
                    json.obj { k ->
                        when (k) {
                            "k" -> w = json.double().toFloat()
                            "l" -> h = json.double().toFloat()
                            "m" -> m = json.double().toFloat()
                            "n" -> n = json.double().toFloat()
                            "o" -> o = json.double().toFloat()
                            else -> json.skipValue()
                        }
                    }
                    pageMeta[id] = PageMeta(w, h, -m, -n, o)
                }
                "NBSTROKE" -> json.array {
                    val (page, stroke) = stroke(json, points)
                    strokes.getOrPut(page) { mutableListOf() }.add(stroke)
                }
                "IMAGE" -> image = numbers(json, setOf("x", "y", "i", "j", "c", "d", "e"), "b")
                "TEXT" -> json.array {
                    var text = ""
                    val f = numbers(json, setOf("x", "y", "t", "u", "c", "d", "g", "f"), "b") { k ->
                        if (k == "e") {
                            text = if (json.peekIsNull()) "".also { json.skipValue() } else json.string()
                            true
                        } else {
                            false
                        }
                    }
                    if (text.isNotBlank()) {
                        texts.getOrPut(f.page) { mutableListOf() }.add(
                            JText(
                                f["x"] - f["t"], f["y"] - f["u"], f["c"], f["d"], text,
                                f.values["g"]?.takeIf { it > 0 } ?: 24f,
                                f.longs["f"]?.toInt() ?: 0xFF000000.toInt(),
                            ),
                        )
                    }
                }
            }
            src.skip(src.remaining) // lo que no se leyó (registros ignorados, espacios finales)
            src.remaining = Long.MAX_VALUE
            val f = image
            if (f != null && blobLength in 1..MAX_IMAGE_BYTES) {
                val bytes = src.bytes(blobLength.toInt())
                images.getOrPut(f.page) { mutableListOf() }.add(
                    JImage(f["x"] - f["i"], f["y"] - f["j"], f["c"], f["d"], f["e"], bytes),
                )
            } else {
                src.skip(blobLength)
            }
        }

        // Mismo orden que el script: primero las páginas declaradas, después las que sólo
        // aparecen como destino de algún trazo, imagen o texto.
        val pageIds = LinkedHashSet<String>().apply {
            addAll(pageMeta.keys)
            addAll(strokes.keys)
            addAll(images.keys)
            addAll(texts.keys)
        }
        val pages = pageIds.map { id ->
            val meta = pageMeta[id] ?: PageMeta(1240f, 1754f, Float.NaN, Float.NaN, Float.NaN)
            JPage(
                meta.width, meta.height, strokes[id].orEmpty().sortedBy { it.order }, images[id].orEmpty(), texts[id].orEmpty(),
                meta.viewX, meta.viewY, meta.viewZoom,
            )
        }
        return JNote(title, pages)
    }

    /** Campos numéricos de un objeto JSON; los que faltan valen 0. */
    private class Fields(val page: String, val values: Map<String, Float>, val longs: Map<String, Long>) {
        operator fun get(k: String): Float = values[k]?.takeIf { !it.isNaN() } ?: 0f
    }

    /**
     * Lee un objeto quedándose con los números de `keys` y con el texto de `pageKey`. `other`
     * puede consumir otros campos (devuelve true si lo hizo).
     */
    private inline fun numbers(json: JsonPull, keys: Set<String>, pageKey: String, other: (String) -> Boolean = { false }): Fields {
        var page = ""
        val values = HashMap<String, Float>()
        val longs = HashMap<String, Long>()
        json.obj { k ->
            when {
                k == pageKey -> page = json.string()
                k in keys -> json.double().let {
                    values[k] = it.toFloat()
                    if (!it.isNaN()) longs[k] = it.toLong()
                }
                !other(k) -> json.skipValue()
            }
        }
        return Fields(page, values, longs)
    }

    /** Un trazo: `a` página, `c` orden, `h`/`i` desplazamiento, `b` pincel con los puntos en `k`. */
    private fun stroke(json: JsonPull, points: PointBuffer): Pair<String, JStroke> {
        var page = ""
        var order = 0L
        var dx = 0f
        var dy = 0f
        var tool = 0
        var color = 0xFF000000.toInt()
        var width = 2f
        points.clear()
        json.obj { k ->
            when (k) {
                "a" -> page = json.string()
                "c" -> order = json.long()
                "h" -> dx = json.double().toFloat()
                "i" -> dy = json.double().toFloat()
                "b" -> json.obj { bk ->
                    when (bk) {
                        "a" -> tool = json.int()
                        "c" -> color = json.long().toInt()
                        "d" -> width = json.double().toFloat()
                        "k" -> json.array { point(json, points) }
                        else -> json.skipValue()
                    }
                }
                else -> json.skipValue()
            }
        }
        val n = points.size
        val xs = FloatArray(n) { points.x[it] - dx }
        val ys = FloatArray(n) { points.y[it] - dy }
        return page to JStroke(tool, color, width, order, xs, ys, points.p.copyOf(n))
    }

    private fun point(json: JsonPull, points: PointBuffer) {
        var x = 0f
        var y = 0f
        var p = 0f
        json.obj { k ->
            when (k) {
                "x" -> x = json.double().toFloat()
                "y" -> y = json.double().toFloat()
                "p" -> p = json.double().toFloat()
                else -> json.skipValue()
            }
        }
        points.add(x, y, p)
    }

    /** Puntos del trazo que se está leyendo; se reutiliza entre trazos. */
    private class PointBuffer {
        var x = FloatArray(256)
        var y = FloatArray(256)
        var p = FloatArray(256)
        var size = 0

        fun clear() {
            size = 0
        }

        fun add(px: Float, py: Float, pp: Float) {
            if (size == x.size) {
                x = x.copyOf(size * 2)
                y = y.copyOf(size * 2)
                p = p.copyOf(size * 2)
            }
            x[size] = px
            y[size] = py
            p[size] = pp
            size++
        }
    }

    /** Más grande que esto no es una imagen pegada sino un registro dañado. */
    private const val MAX_IMAGE_BYTES = 64L shl 20

    private fun unzip(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input, 1 shl 16)
        buffered.mark(4)
        val isZip = buffered.read() == 'P'.code && buffered.read() == 'K'.code
        buffered.reset()
        if (!isZip) return buffered
        val zip = ZipInputStream(buffered)
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.name.lowercase().endsWith(".jzip")) return zip
        }
        throw IOException("el ZIP no tiene ningún .Jzip adentro")
    }
}
