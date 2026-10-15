package com.octavio.tinta

import com.octavio.tinta.jnotes.ByteSource
import com.octavio.tinta.jnotes.JsonPull
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonPullTest {

    private fun json(text: String) = JsonPull(ByteSource(text.toByteArray().inputStream()))

    @Test
    fun numbers() {
        val values = mutableListOf<Double>()
        val j = json("[0, -1, 135.2, 0.29, -16777216, 1.5e3, 2E-2, 4278190080]")
        j.array { values.add(j.double()) }
        assertEquals(listOf(0.0, -1.0, 135.2, 0.29, -16777216.0, 1500.0, 0.02, 4278190080.0), values)
    }

    @Test
    fun stringsWithEscapesAndUtf8() {
        // Texto JSON: Física \"II\"\n\u00e1\/x  (escapes de JSON, no de Kotlin)
        val bs = '\\'
        val j = json(" {\"t\": \"Física ${bs}\"II${bs}\"${bs}n${bs}u00e1${bs}/x\"} ")
        var t = ""
        j.obj { k -> if (k == "t") t = j.string() else j.skipValue() }
        assertEquals("Física \"II\"\n\u00e1/x", t)
    }

    @Test
    fun skipsNestedValues() {
        val j = json("""{"a":{"b":[1,{"c":true},null,"x\"y"],"d":false},"e":7}""")
        var e = 0
        j.obj { k -> if (k == "e") e = j.int() else j.skipValue() }
        assertEquals(7, e)
    }

    @Test
    fun emptyContainers() {
        val j = json("""{"a":[],"b":{}}""")
        var items = 0
        j.obj { k ->
            if (k == "a") j.array { items++; j.skipValue() } else j.skipValue()
        }
        assertEquals(0, items)
    }
}
