package com.octavio.tinta.storage

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Reemplaza un archivo entero sin dejarlo a medias. El contenido nuevo va a un temporal en el
 * mismo directorio (`nombre.tmp`), se hace fsync y recién ahí se renombra encima del archivo.
 * Si falla la escritura, el fsync o el rename, el archivo que ya estaba queda igual, el temporal
 * se borra y el error le llega a quien llamó. No se copia encima del archivo vivo: un corte de
 * luz no puede dejarlo escrito por la mitad.
 *
 * Quien escribe no tiene que cerrar [out]. El búfer se vacía acá, antes del fsync. Cerrarlo
 * tampoco cierra el archivo, así un `use` no se salta el sync.
 */
object AtomicFile {
    fun write(target: File, block: (OutputStream) -> Unit) {
        commit(target, block = block)
    }

    /**
     * Igual que [write], con el fsync y el rename inyectables para probar un disco que falla
     * o un rename que no puede pisar el archivo.
     */
    internal fun commit(
        target: File,
        sync: (FileOutputStream) -> Unit = { it.fd.sync() },
        rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
        block: (OutputStream) -> Unit,
    ) {
        val dir = target.parentFile ?: throw IOException("sin carpeta para ${target.name}")
        val tmp = File(dir, target.name + ".tmp")
        var published = false
        try {
            FileOutputStream(tmp).use { stream ->
                val buffered = FlushOnlyBuffered(stream)
                block(buffered)
                buffered.flush()
                sync(stream)
            }
            if (!rename(tmp, target)) throw IOException("no se pudo guardar ${target.name}")
            published = true
        } finally {
            if (!published) tmp.delete()
        }
    }

    /**
     * Vacía el búfer al cerrar, pero deja el [FileOutputStream] abierto: todavía hay que
     * sincronizarlo. Si no, el `use` de quien escribe cerraría el descriptor antes del fsync.
     */
    private class FlushOnlyBuffered(out: OutputStream) : BufferedOutputStream(out) {
        @Synchronized
        override fun close() {
            flush()
        }
    }
}
