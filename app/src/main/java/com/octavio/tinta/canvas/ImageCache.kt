package com.octavio.tinta.canvas

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors

/**
 * Las imágenes decodificadas para la pantalla, en la memoria de la GPU, compartidas por las dos
 * notas abiertas. Se decodifican recién cuando hacen falta (una página de un PDF de 80 hojas no se
 * carga hasta que se mira) y, pasado [BUDGET], se sueltan las que hace más que no se usan. Todo
 * desde el hilo de la pantalla salvo la decodificación.
 */
object ImageCache {
    private class Entry(val bitmap: Bitmap) {
        var used = SystemClock.uptimeMillis()
        val bytes = bitmap.allocationByteCount
    }

    private val entries = LinkedHashMap<File, Entry>(16, 0.75f, true)
    private val waiting = HashMap<File, MutableList<() -> Unit>>()
    private var total = 0L
    private val main = Handler(Looper.getMainLooper())
    private val decoder = Executors.newFixedThreadPool(2) {
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            it.run()
        }, "tinta-imagenes")
    }

    /** La imagen si ya está; si no, null y `onReady` se llama cuando esté (si se pudo leer). */
    fun get(file: File, onReady: () -> Unit): Bitmap? {
        entries[file]?.let {
            it.used = SystemClock.uptimeMillis()
            return it.bitmap
        }
        val list = waiting[file]
        if (list != null) {
            list.add(onReady)
            return null
        }
        waiting[file] = mutableListOf(onReady)
        decoder.execute {
            val bitmap = runCatching { Elements.decode(file, MAX_SIDE, hardware = true) }.getOrNull()
            main.post {
                val callbacks = waiting.remove(file).orEmpty()
                if (bitmap != null) {
                    entries[file] = Entry(bitmap)
                    total += bitmap.allocationByteCount
                    trim()
                    callbacks.forEach { it() }
                }
            }
        }
        return null
    }

    /** Marca que la imagen se sigue mirando, aunque no haga falta volver a dibujarla. */
    fun touch(file: File) {
        entries[file]?.used = SystemClock.uptimeMillis()
    }

    /** Suelta las que se usaron hace más tiempo, salvo las de los últimos segundos (las de la vista). */
    private fun trim() {
        if (total <= BUDGET) return
        val now = SystemClock.uptimeMillis()
        val it = entries.entries.iterator()
        while (total > BUDGET && it.hasNext()) {
            val e = it.next().value
            if (now - e.used < RECENT_MS) continue
            total -= e.bytes
            it.remove()
        }
    }

    /** Lado mayor con que se decodifican: alcanza para una página de PDF a buen zoom. */
    const val MAX_SIDE = 3300
    private const val BUDGET = 320L shl 20
    private const val RECENT_MS = 3000L
}
