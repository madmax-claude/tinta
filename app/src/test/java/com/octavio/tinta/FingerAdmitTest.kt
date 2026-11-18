package com.octavio.tinta

import com.octavio.tinta.input.FingerAdmit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerAdmitTest {
    private val density = 2f

    @Test
    fun contactoAnchoEsPalmaAunqueElLapizEsteLejos() {
        val limit = FingerAdmit.PALM_TOUCH_DP * density
        assertTrue(FingerAdmit.isPalm(limit, density, distToTipPx = null, penNear = false))
        assertFalse(FingerAdmit.isPalm(limit - 1f, density, distToTipPx = null, penNear = false))
        assertFalse(FingerAdmit.isPalm(0f, density, distToTipPx = null, penNear = false))
    }

    @Test
    fun contactoPegadoALaPuntaEsPalmaSoloConElLapizCerca() {
        val near = FingerAdmit.NEAR_TIP_DP * density - 1f
        assertTrue(FingerAdmit.isPalm(10f, density, near, penNear = true))
        assertFalse(FingerAdmit.isPalm(10f, density, FingerAdmit.NEAR_TIP_DP * density, penNear = true))
        assertFalse(FingerAdmit.isPalm(10f, density, near, penNear = false))
    }

    @Test
    fun conElLapizCercaUnDedoSoloNoMueveYDosSi() {
        val gate = FingerAdmit()
        val first = gate.onDown(id = 1, palm = false, penNear = true, partnerDown = false, fingersEmpty = true)
        assertFalse(first.accept)
        assertEquals(1, gate.waitingId)

        val second = gate.onDown(id = 2, palm = false, penNear = true, partnerDown = true, fingersEmpty = true)
        assertTrue(second.accept)
        assertTrue(second.promote)
        assertEquals(FingerAdmit.NONE, gate.waitingId)
    }

    @Test
    fun laPalmaNoOcupaElLugarDelPrimerDedo() {
        val gate = FingerAdmit()
        val palm = gate.onDown(id = 7, palm = true, penNear = true, partnerDown = false, fingersEmpty = true)
        assertFalse(palm.accept)
        assertEquals(FingerAdmit.NONE, gate.waitingId)

        val finger = gate.onDown(id = 1, palm = false, penNear = true, partnerDown = false, fingersEmpty = true)
        assertFalse(finger.accept)
        assertEquals(1, gate.waitingId)
    }

    @Test
    fun sinElLapizUnDedoNormalEntraYUnaPalmaNo() {
        val gate = FingerAdmit()
        val finger = gate.onDown(id = 1, palm = false, penNear = false, partnerDown = false, fingersEmpty = true)
        assertTrue(finger.accept)
        assertFalse(finger.promote)

        val palm = gate.onDown(id = 2, palm = true, penNear = false, partnerDown = false, fingersEmpty = true)
        assertFalse(palm.accept)
    }

    @Test
    fun elTercerDedoEntraCuandoElGestoYaEmpezo() {
        val gate = FingerAdmit()
        val third = gate.onDown(id = 3, palm = false, penNear = true, partnerDown = false, fingersEmpty = false)
        assertTrue(third.accept)
        assertFalse(third.promote)
    }

    @Test
    fun soltarElDedoQueEsperabaLoOlvida() {
        val gate = FingerAdmit()
        gate.onDown(id = 4, palm = false, penNear = true, partnerDown = false, fingersEmpty = true)
        gate.onUp(4)
        assertEquals(FingerAdmit.NONE, gate.waitingId)
        val again = gate.onDown(id = 5, palm = false, penNear = true, partnerDown = false, fingersEmpty = true)
        assertFalse(again.accept)
        assertEquals(5, gate.waitingId)
        gate.clear()
        assertEquals(FingerAdmit.NONE, gate.waitingId)
    }
}
