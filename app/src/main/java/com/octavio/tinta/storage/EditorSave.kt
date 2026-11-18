package com.octavio.tinta.storage

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Qué cambios de la nota abierta todavía no están en el disco, y qué pasa cuando la escritura
 * termina. No toca archivos: el editor le pide un intento, [NoteStore] escribe, y después le
 * cuenta cómo salió.
 *
 * El flag no se baja al encolar. Se baja recién cuando ESA escritura terminó bien y no hubo
 * cambios más nuevos. Si falla, la nota sigue sucia y [failed] pide reintento: si no, la
 * próxima pausa cree que no hay nada que guardar y el trazo se pierde.
 */
class EditorSave {
    var dirty: Boolean = false
        private set
    var viewportChanged: Boolean = false
        private set
    var thumbnailStale: Boolean = false
        private set

    /** Para la caché de pestañas: un cambio de vista también tiene que volver a escribirse. */
    val rememberDirty: Boolean get() = dirty || viewportChanged

    private var generation = 0
    private var inFlight: Attempt? = null

    /** Un fallo pide un solo reintento automático; el siguiente lo deja a la próxima pausa. */
    private var retryArmed = true

    /** Trazos, textos o cualquier dato de la nota. La miniatura quedó vieja también. */
    fun noteEdited() {
        dirty = true
        thumbnailStale = true
        generation++
        retryArmed = true
    }

    /** Se movió la cámara. Se guarda al salir, con las coordenadas de ese momento. */
    fun viewportEdited() {
        viewportChanged = true
        generation++
    }

    /**
     * La pantalla pasa a mostrar esta nota (recién leída, o traída de la caché). Lo que estaba
     * en vuelo era de la nota anterior y no puede limpiar estos flags.
     */
    fun shown(dirty: Boolean, thumbnailStale: Boolean) {
        this.dirty = dirty
        this.viewportChanged = false
        this.thumbnailStale = thumbnailStale
        generation++
        inFlight = null
        retryArmed = true
    }

    class Attempt internal constructor(
        val generation: Int,
        val withThumbnail: Boolean,
    )

    /**
     * @param wantThumbnail al pausar o al salir, también la miniatura
     * @param thumbnailMissing no hay archivo de miniatura
     * @return null si no hay nada que escribir, o si ya se está escribiendo esta misma
     *         generación con una miniatura que alcanza. Si no, el intento: los flags siguen
     *         prendidos hasta [succeed].
     */
    fun begin(wantThumbnail: Boolean, thumbnailMissing: Boolean): Attempt? {
        val thumb = wantThumbnail && (thumbnailStale || thumbnailMissing)
        if (!dirty && !viewportChanged && !thumb) return null
        val flying = inFlight
        if (flying != null && flying.generation == generation && (flying.withThumbnail || !thumb)) return null
        return Attempt(generation, thumb).also { inFlight = it }
    }

    /**
     * La escritura de [attempt] llegó al disco. No baja los flags si entró un cambio después
     * de encolar (otra generación). True si todavía queda algo de la nota sin guardar.
     */
    fun succeed(attempt: Attempt): Boolean {
        if (inFlight === attempt) inFlight = null
        retryArmed = true
        if (attempt.generation == generation) {
            dirty = false
            viewportChanged = false
            if (attempt.withThumbnail) thumbnailStale = false
        }
        return dirty || viewportChanged
    }

    /**
     * La escritura falló. Los cambios siguen pendientes. True si hay que reintentar ya: no
     * hay otra escritura de esta generación en curso, y este fallo es el primero de la tanda
     * (el siguiente no encola solo, para no repetir el toast si el disco sigue fallando; la
     * pausa vuelve a intentar porque la nota sigue sucia). Si se había pedido la miniatura,
     * queda marcada para que el reintento no dependa de volver a mirar si el archivo existe.
     */
    fun failed(attempt: Attempt): Boolean {
        if (inFlight === attempt) inFlight = null
        if (attempt.generation == generation && attempt.withThumbnail) thumbnailStale = true
        val covered = inFlight?.generation == generation
        if (covered || !(dirty || viewportChanged || thumbnailStale) || !retryArmed) return false
        retryArmed = false
        return true
    }

    /** Cómo terminó [await]. [error] es la causa de la escritura, no el envoltorio del futuro. */
    class Outcome(val timedOut: Boolean, val error: Throwable?) {
        val ok: Boolean get() = error == null && !timedOut
    }

    companion object {
        /**
         * Tope para esperar el sync y el rename en el hilo de la pantalla. Alcanza para que
         * el archivo temporal quede en disco y queda lejos de un ANR (5 s).
         */
        const val WAIT_MS = 2_000L

        /**
         * Encola [write] y devuelve un futuro que termina cuando el bloque terminó. Si tira,
         * [onError] corre en ese mismo hilo y el futuro falla igual (aunque [onError] también tire).
         */
        fun enqueueWrite(executor: Executor, onError: (Throwable) -> Unit = {}, write: () -> Unit): CompletableFuture<Unit> {
            val done = CompletableFuture<Unit>()
            executor.execute {
                runCatching(write).fold(
                    onSuccess = { done.complete(Unit) },
                    onFailure = { t ->
                        runCatching { onError(t) }
                        done.completeExceptionally(t)
                    },
                )
            }
            return done
        }

        /** Espera a que [future] termine, a lo sumo [waitMs]. Null si no había nada en vuelo. */
        fun await(future: Future<*>?, waitMs: Long = WAIT_MS): Outcome? {
            if (future == null) return null
            return try {
                future.get(waitMs, TimeUnit.MILLISECONDS)
                Outcome(timedOut = false, error = null)
            } catch (_: TimeoutException) {
                Outcome(timedOut = true, error = null)
            } catch (e: ExecutionException) {
                Outcome(timedOut = false, error = e.cause ?: e)
            } catch (e: CancellationException) {
                Outcome(timedOut = false, error = e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Outcome(timedOut = false, error = e)
            }
        }
    }
}
