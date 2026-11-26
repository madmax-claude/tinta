package com.octavio.tinta.storage

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * Publica una copia en una carpeta de documentos sin abrir la anterior para escribir.
 *
 * En Drive (y en el selector de archivos) abrir un documento con `"w"`, `"wt"` o `"rwt"` lo
 * trunca en el acto. Si la app, el disco o la subida se cortan a mitad, esa era la única copia
 * de afuera y queda vacía o rota. Drive además rechaza `"wt"` y no deja buscar ni agregar
 * (`"rw"` / `"wa"`). No hay un reemplazo atómico de un solo documento.
 *
 * Por eso la copia nueva se crea en otro documento, se lee entera y recién entonces —si coincide
 * con el zip armado en la tablet— se recuerda como la vigente y se borran las anteriores que se
 * pudieron leer y son más viejas. Un corte deja la última copia buena sin tocar.
 */
object BackupRotation {
    const val MANIFEST = "tinta-copia.json"

    /** Los bytes de un archivo y su CRC, para saber si la copia subida es exactamente la armada. */
    data class Fingerprint(val bytes: Long, val crc: Long)

    /** Qué se pudo saber de un documento sin escribirlo. */
    sealed class State {
        class Valid(val fecha: Long) : State()

        /** Se leyó hasta el final y no es una copia de Tinta (vacío, zip cortado o sin manifiesto). */
        object Invalid : State()

        /** No se pudo leer (la red, Drive). No se borra: podría ser la copia buena. */
        object Unreadable : State()
    }

    /**
     * Escribe [zip] en un documento nuevo de [dir]. [onCommitted] se llama cuando la copia nueva
     * ya se leyó entera y coincide, y antes de borrar ninguna copia anterior.
     */
    fun publish(dir: BackupDirectory, zip: File, baseName: String, onCommitted: (BackupDocument) -> Unit = {}): BackupDocument {
        val expectedFecha = fechaOf(zip)
        val expected = fingerprint(zip)
        val existing = dir.documents().filter { isFamily(it.name, baseName) }
        val states = HashMap<String, State>()
        for (doc in existing) states[doc.key] = inspect(doc)

        val same = existing.firstOrNull { doc ->
            val state = states[doc.key]
            state is State.Valid && state.fecha == expectedFecha && fingerprintOrNull(doc) == expected
        }
        if (same != null) {
            onCommitted(same)
            deleteOlder(existing, states, same, expectedFecha)
            return same
        }

        // Nombre nuevo: nunca es el de una copia que ya está. Si el proveedor le agrega " (1)",
        // sigue siendo de la familia y se puede encontrar.
        val created = dir.create("$baseName.$expectedFecha.zip")
        try {
            created.openOutput().use { out ->
                FileInputStream(zip).use { input -> input.copyTo(out, BUFFER) }
                out.flush()
                (out as? FileOutputStream)?.let { runCatching { it.fd.sync() } }
            }
        } catch (t: Throwable) {
            // Este documento lo acabamos de crear: a lo sumo tiene un zip cortado.
            runCatching { created.delete() }
            throw t
        }
        val got = try {
            created.openInput().use { fingerprint(it) }
        } catch (e: IOException) {
            // No se sabe si llegó entera. No se borra ni se adopta; la anterior sigue vigente.
            throw IOException("no se pudo verificar la copia nueva", e)
        }
        if (got != expected) {
            runCatching { created.delete() }
            error("la copia nueva no coincide con la que se armó")
        }
        onCommitted(created)
        deleteOlder(existing, states, created, expectedFecha)
        return created
    }

    /**
     * La copia válida con la fecha de manifiesto más nueva. Ignora zip cortados, vacíos y los que
     * no se pudieron leer. Si hay empate, gana el nombre mayor (la generación más alta).
     * [includeKey] entra aunque el proveedor le haya puesto un nombre irreconocible.
     */
    fun newest(dir: BackupDirectory, baseName: String, includeKey: String? = null): BackupDocument? {
        var best: BackupDocument? = null
        var bestFecha = Long.MIN_VALUE
        var bestName = ""
        for (doc in dir.documents()) {
            if (!isFamily(doc.name, baseName) && doc.key != includeKey) continue
            val state = inspect(doc)
            if (state !is State.Valid) continue
            if (state.fecha > bestFecha || (state.fecha == bestFecha && doc.name > bestName)) {
                best = doc
                bestFecha = state.fecha
                bestName = doc.name
            }
        }
        return best
    }

    /**
     * Un archivo de esta copia: el nombre histórico, una generación `nombre.<fecha>.zip`, o el
     * mismo con el " (1)" que agrega el proveedor cuando el nombre ya existe.
     */
    fun isFamily(name: String, baseName: String): Boolean {
        if (!name.endsWith(".zip")) return false
        val stripped = name.removeSuffix(".zip").replace(DUPLICATE_SUFFIX, "")
        if (stripped == baseName) return true
        if (!stripped.startsWith("$baseName.")) return false
        val rest = stripped.removePrefix("$baseName.")
        return rest.isNotEmpty() && rest.all { it.isDigit() }
    }

    fun inspect(doc: BackupDocument): State {
        return try {
            val fecha = doc.openInput().use { readFecha(it) }
            if (fecha == null) State.Invalid else State.Valid(fecha)
        } catch (e: ZipException) {
            State.Invalid
        } catch (e: IOException) {
            State.Unreadable
        }
    }

    /** La fecha del manifiesto, o null si el zip está entero pero no es una copia de Tinta. */
    fun readFecha(input: InputStream): Long? {
        ZipInputStream(input).use { zip ->
            var fecha: Long? = null
            val buf = ByteArray(BUFFER)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == MANIFEST) {
                    val bytes = readFully(zip, buf)
                    val json = try {
                        JSONObject(bytes.toString(Charsets.UTF_8))
                    } catch (e: Exception) {
                        return null
                    }
                    if (json.has("app") && json.optString("app") != "Tinta") return null
                    val value = json.optLong("fecha", -1)
                    if (value < 0) return null
                    fecha = value
                } else {
                    drain(zip, buf)
                }
            }
            return fecha
        }
    }

    fun fingerprint(file: File): Fingerprint = FileInputStream(file).use { fingerprint(it) }

    fun fingerprint(input: InputStream): Fingerprint {
        val crc = CRC32()
        val buf = ByteArray(BUFFER)
        var n = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            crc.update(buf, 0, r)
            n += r
        }
        return Fingerprint(n, crc.value)
    }

    private fun fechaOf(file: File): Long =
        try {
            FileInputStream(file).use { readFecha(it) } ?: error("la copia armada no es de Tinta")
        } catch (e: ZipException) {
            throw IllegalStateException("la copia armada no es de Tinta", e)
        }

    private fun fingerprintOrNull(doc: BackupDocument): Fingerprint? =
        try {
            doc.openInput().use { fingerprint(it) }
        } catch (e: IOException) {
            null
        }

    /**
     * Borra copias anteriores que se leyeron bien y son más viejas (o un duplicado de esta misma).
     * No borra las que fallaron al leer: un corte de Drive no tiene que hacer desaparecer la única
     * copia que había.
     */
    private fun deleteOlder(existing: List<BackupDocument>, states: Map<String, State>, keep: BackupDocument, fecha: Long) {
        for (doc in existing) {
            if (doc.key == keep.key) continue
            val state = states[doc.key]
            if (state is State.Valid && (state.fecha < fecha || state.fecha == fecha)) {
                runCatching { doc.delete() }
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        drain(input, buf, out)
        return out.toByteArray()
    }

    private fun drain(input: InputStream, buf: ByteArray, out: ByteArrayOutputStream? = null) {
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out?.write(buf, 0, n)
        }
    }

    private val DUPLICATE_SUFFIX = Regex(""" \(\d+\)$""")
    private const val BUFFER = 1 shl 16
}

/** Una carpeta donde se pueden crear documentos sin abrir los que ya están. */
interface BackupDirectory {
    fun documents(): List<BackupDocument>

    /** Un documento nuevo. No trunca ni reabre uno que ya tenga una copia. */
    fun create(name: String): BackupDocument
}

/** Un documento de la carpeta. [key] no cambia aunque el proveedor ajuste el nombre visible. */
interface BackupDocument {
    val key: String
    val name: String
    fun openInput(): InputStream
    fun openOutput(): OutputStream
    fun delete()
}
