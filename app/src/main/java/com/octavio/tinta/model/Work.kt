package com.octavio.tinta.model

import android.os.Process
import java.util.concurrent.Callable
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinWorkerThread
import java.util.stream.Collectors

/**
 * Hilos para el trabajo pesado de fondo (mallas, imagen de la nota). Tienen prioridad de fondo y
 * dejan dos núcleos libres: en la P12 los dos núcleos rápidos quedan para la pantalla y el lápiz.
 */
object Work {
    private val pool = ForkJoinPool(
        (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(2),
        { p ->
            object : ForkJoinWorkerThread(p) {
                override fun onStart() {
                    super.onStart()
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                }
            }.apply { name = "tinta-fondo-$poolIndex" }
        },
        null,
        false,
    )

    /** Hace `action` con cada elemento en paralelo y espera a que terminen todos. */
    fun <T> each(items: List<T>, action: (T) -> Unit) {
        pool.submit { items.parallelStream().forEach(action) }.get()
    }

    fun <T, R> map(items: List<T>, transform: (T) -> R): List<R> =
        pool.submit(Callable { items.parallelStream().map(transform).collect(Collectors.toList()) }).get()
}
