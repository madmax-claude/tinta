package com.octavio.tinta

import com.octavio.tinta.model.Pen
import com.octavio.tinta.model.Sensitivity
import com.octavio.tinta.model.Tool
import com.octavio.tinta.storage.NoteFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class SensitivityTest {

    @Test
    fun defaultIsTheOldPen() {
        // Los trazos viejos tienen que verse igual: 0,4 a 1,3 veces el tamaño.
        val (low, high) = Sensitivity.range(Sensitivity.DEFAULT)
        assertEquals(0.4f, low, 1e-6f)
        assertEquals(1.3f, high, 1e-6f)
        assertEquals(1f to 1f, Sensitivity.range(0)) // sin sensibilidad, ancho fijo
    }

    @Test
    fun packedInTheToolByte() {
        for (tool in Tool.entries) for (level in 0..Sensitivity.MAX) {
            val b = NoteFormat.packTool(tool.id, level)
            assertEquals(tool.id, NoteFormat.unpackTool(b))
            assertEquals(level, NoteFormat.unpackSensitivity(b, -1))
        }
        // Un byte de una nota vieja (sólo la herramienta) da la de siempre.
        assertEquals(Sensitivity.DEFAULT, NoteFormat.unpackSensitivity(Tool.PEN.id, Sensitivity.DEFAULT))
    }

    @Test
    fun penKeepsItInJson() {
        val p = Pen(Tool.PEN, 0xFF1E5BD8.toInt(), 2f, sensitivity = 9)
        assertEquals(p, Pen.fromJson(p.toJson()))
        assertEquals(Sensitivity.DEFAULT, Pen.fromJson(org.json.JSONObject().put("tool", 0)).sensitivity)
    }
}
