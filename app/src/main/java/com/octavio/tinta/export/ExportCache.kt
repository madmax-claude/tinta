package com.octavio.tinta.export

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * `cache/exports` junta cada PDF, PNG, txt y `.tinta`, y nadie lo vaciaba.
 * Se borra lo que es más viejo que [STALE_AFTER_MS], salvo un archivo que se está escribiendo
 * o que un compartir puede seguir leyendo ([SHARE_LEASE_MS] desde que se abrió "Compartir").
 */
internal object ExportCache {
    const val DIR = "exports"

    /** Dos horas: un compartir abierto, o la app que lo recibió, puede tardar en leer el archivo. */
    const val STALE_AFTER_MS = 2 * 60 * 60 * 1000L

    /** El mismo plazo, contado desde que se entregó el archivo a Compartir. */
    const val SHARE_LEASE_MS = STALE_AFTER_MS

    private val leases = ConcurrentHashMap<String, Long>()
    private val writing = HashSet<String>()
    private val lock = Any()

    /** `files` se acaba de pasar a otra app: no borrarlos hasta que venza el plazo. */
    fun markShared(files: Iterable<File>, now: Long = System.currentTimeMillis()) {
        val until = now + SHARE_LEASE_MS
        synchronized(lock) {
            for (file in files) leases[file.absolutePath] = until
        }
    }

    /**
     * Mientras corre `block`, una limpieza no borra `file` (aunque su fecha sea vieja: se está
     * reescribiendo).
     */
    fun <T> keeping(file: File, block: () -> T): T {
        synchronized(lock) { writing.add(file.absolutePath) }
        try {
            return block()
        } finally {
            synchronized(lock) { writing.remove(file.absolutePath) }
        }
    }

    /** Borra en `dir` lo que [filesToDelete] marque. Una carpeta que no existe no se crea. */
    fun cleanup(dir: File, now: Long = System.currentTimeMillis(), maxAgeMs: Long = STALE_AFTER_MS) {
        synchronized(lock) {
            val listed = dir.listFiles() ?: return
            val victims = filesToDelete(
                listed.map { CacheEntry(it.name, it.isFile, it.lastModified(), it.absolutePath) },
                now,
                maxAgeMs,
                HashMap(leases),
                HashSet(writing),
            ).toSet()
            for (file in listed) {
                if (file.absolutePath !in victims) continue
                if (file.absolutePath in writing) continue
                val modified = file.lastModified()
                if (modified > 0L && now - modified > maxAgeMs) file.delete()
            }
            val expired = leases.filterValues { it <= now }.keys.toList()
            expired.forEach { leases.remove(it) }
        }
    }
}

/** Un archivo de `cache/exports`, para decidir sin tocar el disco. `key` es la ruta absoluta. */
internal data class CacheEntry(val name: String, val isFile: Boolean, val lastModified: Long, val key: String)

/**
 * Rutas a borrar. No toca carpetas, ni un archivo cuya fecha no se puede leer (`lastModified`
 * <= 0), ni uno que se está escribiendo, ni uno alquilado (`leasedUntil` > `now`). Hace falta
 * que sea más viejo que `maxAgeMs`: con la misma edad se queda.
 */
internal fun filesToDelete(
    entries: List<CacheEntry>,
    now: Long,
    maxAgeMs: Long,
    leasedUntil: Map<String, Long>,
    writing: Set<String>,
): List<String> {
    if (maxAgeMs < 0L) return emptyList()
    return entries.mapNotNull { entry ->
        if (!entry.isFile) return@mapNotNull null
        if (entry.lastModified <= 0L) return@mapNotNull null
        if (entry.key in writing) return@mapNotNull null
        val lease = leasedUntil[entry.key]
        if (lease != null && lease > now) return@mapNotNull null
        if (now - entry.lastModified > maxAgeMs) entry.key else null
    }
}

/**
 * Corre al abrir la app, antes de las pantallas: ahí se tiran las exportaciones viejas aunque
 * el usuario no exporte nada en esta sesión. No exporta nada.
 */
class ExportCacheProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context ?: return true
        ExportCache.cleanup(File(ctx.cacheDir, ExportCache.DIR))
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
