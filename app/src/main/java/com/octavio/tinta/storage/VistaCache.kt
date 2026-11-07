package com.octavio.tinta.storage

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Foto de toda la nota, al lado del `.tinta`, para abrirla sin volver a armar cada trazo.
 * El `.tinta` sigue siendo la nota: si esta foto no coincide, se tira y se rehace.
 *
 * `TVST`, versión 1, big-endian: época del pincel, `modified`, cantidad de trazos, modo oscuro,
 * escala (px de la foto por dp del mundo) y las franjas en PNG con su caja en el mundo.
 * La época sube si cambia la forma del trazo (la ventana de entrada): la foto vieja no sirve.
 */
object VistaCache {
    const val MAGIC = 0x54565354 // TVST
    const val VERSION = 1

    /** Sube cuando cambia el modelo del trazo y las fotos guardadas dejan de parecerse. */
    const val EPOCH = 1

    class Band(val left: Float, val top: Float, val right: Float, val bottom: Float, val png: ByteArray)

    class Saved(
        val modified: Long,
        val strokeCount: Int,
        val dark: Boolean,
        val scale: Float,
        val bands: List<Band>,
    ) {
        fun matches(modified: Long, strokeCount: Int, dark: Boolean) =
            this.modified == modified && this.strokeCount == strokeCount && this.dark == dark
    }

    fun read(file: File): Saved? {
        if (!file.isFile) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION || input.readInt() != EPOCH) {
                    throw IOException("vista")
                }
                val modified = input.readLong()
                val count = input.readInt()
                val dark = when (val d = input.readInt()) {
                    0 -> false
                    1 -> true
                    else -> throw IOException("modo $d")
                }
                val scale = input.readFloat()
                if (scale <= 0f || scale.isNaN()) throw IOException("escala")
                val n = input.readInt()
                if (n !in 1..MAX_BANDS) throw IOException("franjas")
                val bands = ArrayList<Band>(n)
                repeat(n) {
                    val l = input.readFloat()
                    val t = input.readFloat()
                    val r = input.readFloat()
                    val b = input.readFloat()
                    val len = input.readInt()
                    if (len !in 1..MAX_PNG) throw IOException("png")
                    val png = ByteArray(len)
                    input.readFully(png)
                    bands.add(Band(l, t, r, b, png))
                }
                Saved(modified, count, dark, scale, bands)
            }
        }.getOrNull()
    }

    /** Reemplaza el archivo de una sola vez. Un corte a la mitad deja la foto anterior. */
    fun write(file: File, modified: Long, strokeCount: Int, dark: Boolean, scale: Float, bands: List<Band>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(EPOCH)
            out.writeLong(modified)
            out.writeInt(strokeCount)
            out.writeInt(if (dark) 1 else 0)
            out.writeFloat(scale)
            out.writeInt(bands.size)
            for (b in bands) {
                out.writeFloat(b.left)
                out.writeFloat(b.top)
                out.writeFloat(b.right)
                out.writeFloat(b.bottom)
                out.writeInt(b.png.size)
                out.write(b.png)
            }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private const val MAX_BANDS = 64
    private const val MAX_PNG = 16 * 1024 * 1024
}
