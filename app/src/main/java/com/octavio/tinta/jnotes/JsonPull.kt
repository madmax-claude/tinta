package com.octavio.tinta.jnotes

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Lectura secuencial con búfer propio y un límite opcional de bytes. Las notas de Jnotes traen
 * registros JSON de hasta 18 MB con cientos de miles de puntos: se recorren sin cargarlos enteros.
 */
class ByteSource(private val input: InputStream) {
    private val buf = ByteArray(1 shl 16)
    private var pos = 0
    private var end = 0

    /** Bytes que quedan dentro del límite actual; `Long.MAX_VALUE` si no hay límite. */
    var remaining = Long.MAX_VALUE

    fun read(): Int {
        if (remaining <= 0) return -1
        if (pos == end) {
            end = input.read(buf)
            pos = 0
            if (end <= 0) {
                end = 0
                return -1
            }
        }
        remaining--
        return buf[pos++].toInt() and 0xFF
    }

    fun u8(): Int = read().also { if (it < 0) throw EOFException("el archivo está cortado") }

    fun i32(): Int = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()

    fun i64(): Long = (i32().toLong() shl 32) or (i32().toLong() and 0xFFFFFFFFL)

    fun bytes(n: Int): ByteArray {
        val out = ByteArray(n)
        var done = 0
        while (done < n) {
            if (pos == end) {
                val c = read()
                if (c < 0) throw EOFException("el archivo está cortado")
                out[done++] = c.toByte()
                continue
            }
            val step = minOf((n - done).toLong(), (end - pos).toLong(), remaining).toInt()
            if (step <= 0) throw EOFException("el archivo está cortado")
            System.arraycopy(buf, pos, out, done, step)
            pos += step
            remaining -= step
            done += step
        }
        return out
    }

    /** u16 con el largo y después bytes UTF-8, como DataOutputStream.writeUTF. */
    fun utf(): String {
        val n = (u8() shl 8) or u8()
        return String(bytes(n), Charsets.UTF_8)
    }

    fun skip(n: Long) {
        var left = n
        while (left > 0) {
            if (pos == end) {
                if (read() < 0) throw EOFException("el archivo está cortado")
                left--
                continue
            }
            val step = minOf(left, (end - pos).toLong(), remaining).toInt()
            if (step <= 0) throw EOFException("el archivo está cortado")
            pos += step
            remaining -= step
            left -= step
        }
    }

    /** Pasa `n` bytes a `out` sin juntarlos en memoria (el PDF de una nota puede tener decenas de MB). */
    fun copyTo(out: OutputStream, n: Long) {
        var left = n
        while (left > 0) {
            if (pos == end) {
                val c = read()
                if (c < 0) throw EOFException("el archivo está cortado")
                out.write(c)
                left--
                continue
            }
            val step = minOf(left, (end - pos).toLong(), remaining).toInt()
            if (step <= 0) throw EOFException("el archivo está cortado")
            out.write(buf, pos, step)
            pos += step
            remaining -= step
            left -= step
        }
    }
}

/**
 * Parser JSON de tipo "pull" sobre un [ByteSource]: quien llama recorre la estructura que espera y
 * saltea con [skipValue] lo que no le interesa, sin armar objetos intermedios.
 */
class JsonPull(private val src: ByteSource) {
    private var peeked = -2

    private fun next(): Int {
        if (peeked != -2) return peeked.also { peeked = -2 }
        return src.read()
    }

    /** Próximo byte que no sea espacio, sin consumirlo. */
    private fun peek(): Int {
        var c = next()
        while (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) c = next()
        peeked = c
        return c
    }

    private fun expect(ch: Char) {
        val c = peek()
        if (c != ch.code) throw IOException("JSON inválido: se esperaba '$ch' y vino ${describe(c)}")
        peeked = -2
    }

    fun peekIsNull(): Boolean = peek() == 'n'.code

    /** Recorre un objeto; `onField` tiene que consumir el valor de cada campo. */
    inline fun obj(onField: (String) -> Unit) {
        beginObject()
        if (!endObjectIfEmpty()) {
            do {
                onField(nextName())
            } while (nextInObject())
        }
    }

    /** Recorre un arreglo; `onItem` tiene que consumir cada elemento. */
    inline fun array(onItem: () -> Unit) {
        beginArray()
        if (!endArrayIfEmpty()) {
            do {
                onItem()
            } while (nextInArray())
        }
    }

    fun beginObject() = expect('{')

    fun beginArray() = expect('[')

    fun endObjectIfEmpty(): Boolean = (peek() == '}'.code).also { if (it) peeked = -2 }

    fun endArrayIfEmpty(): Boolean = (peek() == ']'.code).also { if (it) peeked = -2 }

    fun nextName(): String = string().also { expect(':') }

    /** Después de un valor: true si sigue otro campo, false si se cerró el objeto. */
    fun nextInObject(): Boolean = separator('}')

    fun nextInArray(): Boolean = separator(']')

    private fun separator(close: Char): Boolean {
        val c = peek()
        peeked = -2
        return when (c) {
            ','.code -> true
            close.code -> false
            else -> throw IOException("JSON inválido: se esperaba ',' o '$close' y vino ${describe(c)}")
        }
    }

    fun string(): String {
        expect('"')
        val raw = java.io.ByteArrayOutputStream()
        val sb = StringBuilder()
        while (true) {
            val c = next()
            when {
                c < 0 -> throw EOFException("texto JSON sin cerrar")
                c == '"'.code -> break
                c == '\\'.code -> {
                    if (raw.size() > 0) {
                        sb.append(raw.toString(Charsets.UTF_8.name()))
                        raw.reset()
                    }
                    when (val e = next()) {
                        'n'.code -> sb.append('\n')
                        't'.code -> sb.append('\t')
                        'r'.code -> sb.append('\r')
                        'b'.code -> sb.append('\b')
                        'f'.code -> sb.append('\u000C')
                        'u'.code -> {
                            var v = 0
                            repeat(4) { v = v * 16 + Character.digit(next(), 16).also { d -> if (d < 0) throw IOException("escape \\u inválido") } }
                            sb.append(v.toChar())
                        }
                        else -> sb.append(e.toChar()) // \" \\ \/
                    }
                }
                else -> raw.write(c)
            }
        }
        if (raw.size() > 0) sb.append(raw.toString(Charsets.UTF_8.name()))
        return sb.toString()
    }

    /** Número JSON. Camino rápido sin crear Strings para el caso común (sin exponente). */
    fun double(): Double {
        var c = peek()
        peeked = -2
        if (c == 'n'.code) {
            literal("ull")
            return Double.NaN
        }
        if (c == '"'.code) { // algunos campos numéricos vienen como texto
            peeked = c
            return string().toDoubleOrNull() ?: Double.NaN
        }
        val negative = c == '-'.code
        if (negative || c == '+'.code) c = next()
        var mantissa = 0L
        var digits = 0
        var decimals = 0
        var seenDot = false
        while (true) {
            if (c in '0'.code..'9'.code) {
                if (digits < 18) {
                    mantissa = mantissa * 10 + (c - '0'.code)
                    digits++
                    if (seenDot) decimals++
                } else if (!seenDot) {
                    decimals-- // dígitos enteros de más: se corre la coma
                }
            } else if (c == '.'.code && !seenDot) {
                seenDot = true
            } else {
                break
            }
            c = next()
        }
        var value = mantissa.toDouble()
        if (c == 'e'.code || c == 'E'.code) {
            var exp = 0
            var expNegative = false
            c = next()
            if (c == '-'.code || c == '+'.code) {
                expNegative = c == '-'.code
                c = next()
            }
            while (c in '0'.code..'9'.code) {
                exp = exp * 10 + (c - '0'.code)
                c = next()
            }
            decimals += if (expNegative) exp else -exp
        }
        peeked = c
        if (digits == 0) throw IOException("JSON inválido: número vacío")
        value = when {
            decimals > 0 -> value / Math.pow(10.0, decimals.toDouble())
            decimals < 0 -> value * Math.pow(10.0, -decimals.toDouble())
            else -> value
        }
        return if (negative) -value else value
    }

    /** `true` o `false`; cualquier otro valor se saltea y cuenta como `false`. */
    fun bool(): Boolean = when (peek()) {
        't'.code -> { peeked = -2; literal("rue"); true }
        else -> { skipValue(); false }
    }

    /** Texto, o "" si es null u otra cosa. */
    fun stringOrEmpty(): String = if (peek() == '"'.code) string() else "".also { skipValue() }

    fun long(): Long = double().let { if (it.isNaN()) 0L else it.toLong() }

    fun int(): Int = long().toInt()

    fun skipValue() {
        when (peek()) {
            '{'.code -> obj { skipValue() }
            '['.code -> array { skipValue() }
            '"'.code -> skipString()
            't'.code -> { peeked = -2; literal("rue") }
            'f'.code -> { peeked = -2; literal("alse") }
            'n'.code -> { peeked = -2; literal("ull") }
            else -> double()
        }
    }

    private fun skipString() {
        expect('"')
        while (true) {
            when (next()) {
                -1 -> throw EOFException("texto JSON sin cerrar")
                '"'.code -> return
                '\\'.code -> next()
            }
        }
    }

    private fun literal(rest: String) {
        for (ch in rest) if (next() != ch.code) throw IOException("JSON inválido cerca de '$rest'")
    }

    private fun describe(c: Int) = if (c < 0) "el fin del registro" else "'${c.toChar()}'"
}
