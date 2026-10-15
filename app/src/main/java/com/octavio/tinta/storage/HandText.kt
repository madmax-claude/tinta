package com.octavio.tinta.storage

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import com.octavio.tinta.model.Find
import com.octavio.tinta.model.InkStroke
import com.octavio.tinta.model.Lines
import com.octavio.tinta.model.NoteInfo
import com.octavio.tinta.model.NoteText
import com.octavio.tinta.model.Tool
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lo escrito a mano pasado a texto, para poder buscarlo. Los trazos se juntan en renglones
 * ([Lines]) y cada renglón se reconoce con el reconocedor de letra de Google (ML Kit, en la
 * tablet y sin conexión; el modelo de español se baja una sola vez, unos 20 MB).
 *
 * El resultado queda en `notes/<id>.texto`, con una clave por renglón hecha con las cajas de sus
 * trazos: al volver a leer una nota, sólo se reconocen los renglones nuevos o que cambiaron.
 * Los cuadros de texto (de Jnotes) entran tal cual. Las páginas de un PDF no (son imágenes).
 */
class HandText private constructor(context: Context) {
    /** Un renglón: lo que dice (las mejores lecturas, la primera es la más probable) y dónde está. */
    class Line(val key: String, val texts: List<String>, val left: Float, val top: Float, val right: Float, val bottom: Float) {
        /** [texts] pasados por [Find.normalize], para comparar rápido. */
        val normalized: List<String> = texts.map(Find::normalize)

        /** La lectura que mejor contiene lo buscado (ya normalizado) y con cuántos errores, o null. */
        fun match(query: String): Match? {
            var best: Match? = null
            for (i in texts.indices) {
                val e = Find.errors(normalized[i], query)
                if (e >= 0 && (best == null || e < best.errors)) best = Match(texts[i], e)
            }
            return best
        }
    }

    /** Un renglón encontrado: la lectura que coincide y con cuántos errores (0: tal cual). */
    class Match(val text: String, val errors: Int)

    private val dir = File(context.filesDir, "notes")
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor {
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            it.run()
        }, "tinta-letra")
    }
    private val model = DigitalInkRecognitionModel.builder(DigitalInkRecognitionModelIdentifier.ES_AR).build()
    private var recognizer: DigitalInkRecognizer? = null

    /** Cada búsqueda nueva deja sin efecto a la anterior (se deja de leer lo que ya no importa). */
    private val generation = AtomicInteger()

    @Volatile private var downloaded = false

    fun file(id: String) = File(dir, "$id.texto")

    fun forget(id: String) {
        worker.execute { file(id).delete() }
    }

    /**
     * Lee la letra de una nota abierta (sólo lo que cambió desde la última vez) y devuelve sus
     * renglones en el hilo de la pantalla. `progress` recibe textos para mostrar mientras tanto.
     * Las listas tienen que ser copias: se leen en otro hilo.
     */
    fun read(
        id: String,
        modified: Long,
        strokes: List<InkStroke>,
        texts: List<NoteText>,
        progress: (String) -> Unit,
        done: (List<Line>?, String?) -> Unit,
    ) {
        val gen = generation.incrementAndGet()
        worker.execute {
            val result = runCatching {
                ensureModel { main.post { progress(it) } }
                index(id, modified, strokes, texts, gen) { k, n -> main.post { progress("Leyendo la letra… ${percent(k, n)}") } }
            }
            main.post {
                if (gen != generation.get()) return@post
                result.onSuccess { done(it, null) }.onFailure { e ->
                    Log.w(TAG, "leer la letra", e)
                    done(null, problem(e))
                }
            }
        }
    }

    /** Pone al día el texto de una nota que se acaba de cerrar, si el reconocedor ya está bajado. */
    fun update(id: String, modified: Long, strokes: List<InkStroke>, texts: List<NoteText>) {
        worker.execute {
            runCatching {
                if (!isModelReady()) return@execute
                index(id, modified, strokes, texts, generation.get())
            }.onFailure { Log.w(TAG, "poner al día el texto", it) }
        }
    }

    /**
     * Para buscar en todas las notas: entrega los renglones de cada una a medida que están (las
     * que cambiaron desde la última lectura se vuelven a leer, que puede tardar). `isOpen`: notas
     * abiertas en el editor, que se leen al cerrarlas.
     */
    fun readAll(
        notes: List<NoteInfo>,
        load: (String) -> com.octavio.tinta.model.Note?,
        isOpen: (String) -> Boolean,
        progress: (String?) -> Unit,
        each: (String, List<Line>) -> Unit,
    ) {
        val gen = generation.incrementAndGet()
        worker.execute {
            // Primero lo que ya está leído, así se ve algo enseguida.
            val stale = ArrayList<NoteInfo>()
            for (info in notes) {
                if (gen != generation.get()) return@execute
                val saved = readFile(info.id)
                if (saved != null) main.post { each(info.id, saved.lines) }
                if (saved == null || saved.modified < info.modified) {
                    if (!isOpen(info.id)) stale.add(info)
                }
            }
            if (stale.isEmpty()) {
                main.post { if (gen == generation.get()) progress(null) }
                return@execute
            }
            val failure = runCatching {
                ensureModel { main.post { progress(it) } }
                for ((i, info) in stale.withIndex()) {
                    if (gen != generation.get()) return@execute
                    main.post { if (gen == generation.get()) progress("Leyendo la letra de las notas: ${i + 1} de ${stale.size}…") }
                    val note = load(info.id) ?: continue
                    val lines = index(info.id, note.modified, note.strokes, note.texts, gen) ?: return@execute
                    main.post { each(info.id, lines) }
                }
            }.exceptionOrNull()
            main.post {
                if (gen != generation.get()) return@post
                if (failure != null) Log.w(TAG, "leer la letra de las notas", failure)
                progress(failure?.let(::problem))
            }
        }
    }

    /** Deja de leer (se cerró la búsqueda). */
    fun cancel() {
        generation.incrementAndGet()
    }

    // --- Leer ---

    private fun isModelReady(): Boolean {
        if (downloaded) return true
        downloaded = runCatching { Tasks.await(RemoteModelManager.getInstance().isModelDownloaded(model), 30, TimeUnit.SECONDS) }.getOrDefault(false)
        return downloaded
    }

    private fun ensureModel(progress: (String) -> Unit) {
        if (isModelReady()) return
        progress("Bajando el lector de letra (sólo esta vez, hace falta internet)…")
        Tasks.await(RemoteModelManager.getInstance().download(model, DownloadConditions.Builder().build()), 10, TimeUnit.MINUTES)
        downloaded = true
    }

    private fun recognizer(): DigitalInkRecognizer =
        recognizer ?: DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).setMaxResultCount(CANDIDATES).build())
            .also { recognizer = it }

    /**
     * Los renglones de la nota: los que ya estaban leídos salen del archivo y los demás se leen.
     * Devuelve null si se canceló a mitad de camino (lo leído hasta ahí queda guardado igual).
     */
    private fun index(
        id: String,
        modified: Long,
        strokes: List<InkStroke>,
        texts: List<NoteText>,
        gen: Int,
        progress: ((Int, Int) -> Unit)? = null,
    ): List<Line>? {
        val saved = readFile(id)
        if (saved != null && saved.modified == modified) return saved.lines
        val known = saved?.lines?.associateBy { it.key }.orEmpty()
        val ink = strokes.filter { it.tool != Tool.HIGHLIGHTER && !it.top.isNaN() }
        val groups = Lines.group(ink.map { Lines.Box(it.left, it.top, it.right, it.bottom) })
        val out = ArrayList<Line>(groups.size + texts.size)
        val missing = ArrayList<Pair<String, List<InkStroke>>>()
        for (g in groups) {
            // En el orden en que se escribieron: el reconocedor lo tiene en cuenta.
            val members = g.sorted().map { ink[it] }
            val key = keyOf(members)
            val old = known[key]
            if (old != null) out.add(old) else missing.add(key to members)
        }
        var lastSave = System.currentTimeMillis()
        var previous = ""
        for ((i, pair) in missing.withIndex()) {
            if (gen != generation.get()) {
                writeFile(id, -1L, out)
                return null
            }
            progress?.invoke(i, missing.size)
            val (key, members) = pair
            val line = recognize(key, members, previous) ?: continue
            out.add(line)
            previous = line.texts.firstOrNull().orEmpty()
            // En notas largas, cada tanto se guarda lo leído (por si se corta).
            if (System.currentTimeMillis() - lastSave > 10_000) {
                writeFile(id, -1L, out)
                lastSave = System.currentTimeMillis()
            }
        }
        for (t in texts) {
            if (t.text.isBlank()) continue
            out.add(Line("t" + t.text.hashCode(), listOf(t.text), t.left, t.top, t.left + t.width, t.top + t.height))
        }
        out.sortWith(compareBy({ it.top }, { it.left }))
        writeFile(id, modified, out)
        return out
    }

    private fun recognize(key: String, members: List<InkStroke>, previous: String): Line? {
        val ink = Ink.builder()
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        var time = 0L
        for (s in members) {
            val p = s.points()
            if (p.size < 2) continue
            val stroke = Ink.Stroke.builder()
            for (j in 0 until p.size / 2) {
                stroke.addPoint(Ink.Point.create(p[2 * j], p[2 * j + 1], time))
                time += 8
            }
            time += 150
            ink.addStroke(stroke.build())
            l = min(l, s.left)
            t = min(t, s.top)
            r = max(r, s.right)
            b = max(b, s.bottom)
        }
        if (l > r) return null
        val context = RecognitionContext.builder()
            .setPreContext(previous.takeLast(20))
            .setWritingArea(WritingArea(max(r - l, 1f), max(b - t, 1f)))
            .build()
        val result = Tasks.await(recognizer().recognize(ink.build(), context), 30, TimeUnit.SECONDS)
        val texts = result.candidates.map { it.text.trim() }.filter { it.isNotEmpty() }.distinct()
        // Aunque no se lea nada, se guarda: así no se vuelve a intentar con lo mismo.
        return Line(key, texts, l, t, r, b)
    }

    // --- Archivo ---

    private class Saved(val modified: Long, val lines: List<Line>)

    private fun readFile(id: String): Saved? = runCatching {
        val f = file(id)
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        if (o.optInt("v") != VERSION) return null
        val list = o.getJSONArray("renglones")
        val lines = ArrayList<Line>(list.length())
        for (i in 0 until list.length()) {
            val x = list.getJSONObject(i)
            val tx = x.getJSONArray("t")
            val box = x.getJSONArray("caja")
            lines.add(
                Line(
                    x.getString("k"),
                    List(tx.length()) { tx.getString(it) },
                    box.getDouble(0).toFloat(), box.getDouble(1).toFloat(), box.getDouble(2).toFloat(), box.getDouble(3).toFloat(),
                ),
            )
        }
        Saved(o.getLong("modificada"), lines)
    }.getOrNull()

    private fun writeFile(id: String, modified: Long, lines: List<Line>) {
        val list = JSONArray()
        for (line in lines) {
            list.put(
                JSONObject()
                    .put("k", line.key)
                    .put("t", JSONArray(line.texts))
                    .put("caja", JSONArray().put(r1(line.left)).put(r1(line.top)).put(r1(line.right)).put(r1(line.bottom))),
            )
        }
        val o = JSONObject().put("v", VERSION).put("modificada", modified).put("renglones", list)
        val tmp = File(dir, "$id.texto.tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file(id))) tmp.delete()
    }

    private fun r1(v: Float) = (v * 10).roundToInt() / 10.0

    companion object {
        private const val TAG = "Tinta"
        private const val VERSION = 1
        private const val CANDIDATES = 3

        @Volatile private var instance: HandText? = null

        fun get(context: Context): HandText =
            instance ?: synchronized(this) {
                instance ?: HandText(context.applicationContext).also { instance = it }
            }

        private fun percent(k: Int, n: Int) = "${if (n == 0) 100 else k * 100 / n}%"

        private fun problem(e: Throwable): String {
            val text = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
            return if (text.contains("network", true) || text.contains("download", true) || text.contains("internet", true)) {
                "No se pudo bajar el lector de letra. Hace falta internet la primera vez."
            } else {
                "No se pudo leer la letra (${e.javaClass.simpleName})."
            }
        }

        /** Clave de un renglón: las cajas y colores de sus trazos, que no cambian si no se tocan. */
        fun keyOf(members: List<InkStroke>): String {
            val parts = members.map { s ->
                longArrayOf((s.left * 10).roundToInt().toLong(), (s.top * 10).roundToInt().toLong(), (s.right * 10).roundToInt().toLong(), (s.bottom * 10).roundToInt().toLong(), s.color.toLong())
            }.sortedWith { a, b ->
                var c = 0
                for (i in a.indices) {
                    c = a[i].compareTo(b[i])
                    if (c != 0) break
                }
                c
            }
            var h = -0x340d631b7bdddcdbL // FNV-1a de 64 bits
            for (p in parts) for (v in p) {
                h = (h xor v) * 0x100000001b3L
            }
            return java.lang.Long.toHexString(h) + "-" + members.size
        }
    }
}
