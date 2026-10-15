package com.octavio.tinta

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro corto en `files/diag.txt` de lo que manda el lápiz (botones, teclas), para poder ver
 * después de una prueba qué informa cada tablet. El de Android se pierde enseguida entre mensajes
 * del sistema. Se recorta solo a unos 64 KB.
 */
object Diag {
    private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)

    fun init(context: Context) {
        if (file == null) file = File(context.filesDir, "diag.txt")
    }

    @Synchronized
    fun log(message: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) {
                val tail = f.readText().takeLast(MAX_BYTES.toInt() / 2)
                f.writeText(tail)
            }
            f.appendText("${time.format(Date())} $message\n")
        }
        android.util.Log.d("Tinta", message)
    }

    private const val MAX_BYTES = 64 * 1024L
}
