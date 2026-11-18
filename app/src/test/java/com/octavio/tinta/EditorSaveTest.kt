package com.octavio.tinta

import com.octavio.tinta.storage.EditorSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * El editor no baja `dirty` hasta que la escritura terminó, y un fallo se reintenta.
 * [EditorSave] es esa decisión, sin Android ni disco.
 */
class EditorSaveTest {

    @Test
    fun notaLimpiaNoSeEscribe() {
        val saves = EditorSave()
        assertNull(saves.begin(wantThumbnail = true, thumbnailMissing = false))
        assertFalse(saves.rememberDirty)
    }

    @Test
    fun noBajaDirtyHastaQueLaEscrituraTermina() {
        val saves = EditorSave()
        saves.noteEdited()
        val attempt = saves.begin(wantThumbnail = true, thumbnailMissing = false)!!
        assertTrue(attempt.withThumbnail)
        assertTrue(saves.dirty)
        assertTrue(saves.thumbnailStale)
        assertFalse(saves.succeed(attempt))
        assertFalse(saves.dirty)
        assertFalse(saves.thumbnailStale)
        assertNull(saves.begin(wantThumbnail = true, thumbnailMissing = false))
    }

    @Test
    fun falloMantieneDirtyYElSiguienteIntentoSale() {
        val saves = EditorSave()
        saves.noteEdited()
        val first = saves.begin(wantThumbnail = true, thumbnailMissing = false)!!
        assertTrue(saves.failed(first))
        assertTrue(saves.dirty)
        assertTrue(saves.thumbnailStale)
        val retry = saves.begin(wantThumbnail = true, thumbnailMissing = false)
        assertNotNull(retry)
        assertTrue(retry!!.withThumbnail)
    }

    @Test
    fun elSegundoFalloSeguidoNoEncolaSolo() {
        val saves = EditorSave()
        saves.noteEdited()
        val first = saves.begin(wantThumbnail = false, thumbnailMissing = false)!!
        assertTrue(saves.failed(first))
        val second = saves.begin(wantThumbnail = false, thumbnailMissing = false)!!
        assertFalse(saves.failed(second))
        assertTrue(saves.dirty)
        // Un trazo nuevo vuelve a armar el reintento.
        saves.noteEdited()
        val third = saves.begin(wantThumbnail = false, thumbnailMissing = false)!!
        assertTrue(saves.failed(third))
    }

    @Test
    fun unCambioDuranteLaEscrituraNoSeBorraConElExitoViejo() {
        val saves = EditorSave()
        saves.noteEdited()
        val old = saves.begin(wantThumbnail = false, thumbnailMissing = false)!!
        saves.noteEdited()
        assertTrue(saves.succeed(old))
        assertTrue(saves.dirty)
        assertTrue(saves.thumbnailStale)
        val again = saves.begin(wantThumbnail = false, thumbnailMissing = false)!!
        assertFalse(saves.succeed(again))
        assertFalse(saves.dirty)
        assertTrue(saves.thumbnailStale)
    }

    @Test
    fun elFalloViejoNoPisaElReintentoDeLaEscrituraNueva() {
        val saves = EditorSave()
        saves.noteEdited()
        val old = saves.begin(wantThumbnail = false, thumbnailMissing = false)!!
        saves.noteEdited()
        val newer = saves.begin(wantThumbnail = false, thumbnailMissing = false)!!
        assertFalse(saves.failed(old))
        assertTrue(saves.dirty)
        assertTrue(saves.failed(newer))
        assertTrue(saves.dirty)
    }

    @Test
    fun noSeEncolaDosVecesLaMismaGeneracion() {
        val saves = EditorSave()
        saves.noteEdited()
        assertNotNull(saves.begin(wantThumbnail = false, thumbnailMissing = false))
        assertNull(saves.begin(wantThumbnail = false, thumbnailMissing = false))
    }

    @Test
    fun laPausaAgregaLaMiniaturaSiElAutoguardadoNoLaLlevaba() {
        val saves = EditorSave()
        saves.noteEdited()
        val auto = saves.begin(wantThumbnail = false, thumbnailMissing = false)!!
        assertFalse(auto.withThumbnail)
        val pause = saves.begin(wantThumbnail = true, thumbnailMissing = false)
        assertNotNull(pause)
        assertTrue(pause!!.withThumbnail)
    }

    @Test
    fun elAutoguardadoNoDaPorHechaLaMiniatura() {
        val saves = EditorSave()
        saves.noteEdited()
        val auto = saves.begin(wantThumbnail = false, thumbnailMissing = false)!!
        assertFalse(saves.succeed(auto))
        assertFalse(saves.dirty)
        assertTrue(saves.thumbnailStale)
        assertNull(saves.begin(wantThumbnail = false, thumbnailMissing = false))
        assertNotNull(saves.begin(wantThumbnail = true, thumbnailMissing = false))
    }

    @Test
    fun miniaturaFaltanteSeReintentaAunqueElArchivoAparezca() {
        val saves = EditorSave()
        // Sin trazos nuevos, pero falta el png: igual hay que escribirlo.
        val attempt = saves.begin(wantThumbnail = true, thumbnailMissing = true)!!
        assertTrue(attempt.withThumbnail)
        assertFalse(saves.dirty)
        assertTrue(saves.failed(attempt))
        assertTrue(saves.thumbnailStale)
        val retry = saves.begin(wantThumbnail = true, thumbnailMissing = false)
        assertNotNull(retry)
        assertTrue(retry!!.withThumbnail)
        assertFalse(saves.succeed(retry))
        assertFalse(saves.thumbnailStale)
    }

    @Test
    fun laVistaTambienQuedaParaReescribir() {
        val saves = EditorSave()
        saves.viewportEdited()
        assertTrue(saves.rememberDirty)
        assertFalse(saves.dirty)
        val attempt = saves.begin(wantThumbnail = false, thumbnailMissing = false)!!
        assertTrue(saves.failed(attempt))
        assertTrue(saves.viewportChanged)
        assertTrue(saves.rememberDirty)
        val retry = saves.begin(wantThumbnail = true, thumbnailMissing = false)!!
        assertFalse(saves.succeed(retry))
        assertFalse(saves.rememberDirty)
    }

    @Test
    fun cambiarDeNotaNoDejaQueElIntentoAnteriorLimpieLosFlags() {
        val saves = EditorSave()
        saves.noteEdited()
        val previous = saves.begin(wantThumbnail = true, thumbnailMissing = false)!!
        saves.shown(dirty = true, thumbnailStale = true)
        saves.succeed(previous)
        assertTrue(saves.dirty)
        assertTrue(saves.thumbnailStale)
        saves.shown(dirty = false, thumbnailStale = false)
        assertFalse(saves.failed(previous))
        assertFalse(saves.dirty)
        assertFalse(saves.thumbnailStale)
    }

    @Test
    fun laEsperaTieneTope() {
        assertEquals(2_000L, EditorSave.WAIT_MS)
        assertTrue(EditorSave.WAIT_MS < 5_000L)
        assertNull(EditorSave.await(null, 10))
        assertTrue(EditorSave.await(CompletableFuture.completedFuture(Unit), 10)!!.ok)
    }

    @Test
    fun laEscrituraNoTerminaAntesDelBloqueYUnFalloLlegaAlFuturo() {
        val io = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<Int>())
        try {
            val first = EditorSave.enqueueWrite(io) {
                gate.await()
                order.add(1)
            }
            val second = EditorSave.enqueueWrite(io) { order.add(2) }
            assertFalse(first.isDone)
            val waited = EditorSave.await(first, 80)!!
            assertTrue(waited.timedOut)
            assertFalse(second.isDone)
            gate.countDown()
            assertTrue(EditorSave.await(second, 2_000)!!.ok)
            assertEquals(listOf(1, 2), order)

            val errors = mutableListOf<String>()
            val failed = EditorSave.enqueueWrite(io, { errors.add(it.message ?: "") }) { error("disco lleno") }
            val outcome = EditorSave.await(failed, 2_000)!!
            assertFalse(outcome.ok)
            assertEquals("disco lleno", outcome.error?.message)
            assertEquals(listOf("disco lleno"), errors)

            val stillFails = EditorSave.enqueueWrite(io, { throw IllegalStateException("toast") }) { error("disco lleno") }
            assertEquals("disco lleno", EditorSave.await(stillFails, 2_000)!!.error?.message)
        } finally {
            gate.countDown()
            io.shutdownNow()
        }
    }

    @Test
    fun unFalloDeLaEscrituraNoDaPorGuardadaLaNota() {
        val io = Executors.newSingleThreadExecutor()
        try {
            val saves = EditorSave()
            saves.noteEdited()
            val attempt = saves.begin(wantThumbnail = true, thumbnailMissing = false)!!
            val future = EditorSave.enqueueWrite(io) { error("lleno") }
            val outcome = EditorSave.await(future, 2_000)!!
            assertEquals("lleno", outcome.error?.message)
            assertTrue(saves.failed(attempt))
            assertTrue(saves.dirty)
            assertNotNull(saves.begin(wantThumbnail = true, thumbnailMissing = false))
        } finally {
            io.shutdownNow()
        }
    }

    @Test
    fun siLaEsperaSeVenceLaNotaSigueSuciaHastaQueLaEscrituraTermina() {
        val io = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        try {
            val saves = EditorSave()
            saves.noteEdited()
            val attempt = saves.begin(wantThumbnail = true, thumbnailMissing = false)!!
            val future = EditorSave.enqueueWrite(io) { gate.await() }
            val waited = EditorSave.await(future, 80)!!
            assertTrue(waited.timedOut)
            assertTrue(saves.dirty)
            assertNull(saves.begin(wantThumbnail = true, thumbnailMissing = false))
            gate.countDown()
            assertTrue(EditorSave.await(future, 2_000)!!.ok)
            assertFalse(saves.succeed(attempt))
            assertFalse(saves.dirty)
            assertFalse(saves.thumbnailStale)
        } finally {
            gate.countDown()
            io.shutdownNow()
        }
    }
}
