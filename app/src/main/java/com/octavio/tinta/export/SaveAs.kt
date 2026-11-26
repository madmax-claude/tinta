package com.octavio.tinta.export

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.octavio.tinta.R
import com.octavio.tinta.hint
import com.octavio.tinta.toast
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Los mismos tipos con los que hoy se comparte, más el zip de varias hojas. */
internal const val MIME_PDF = "application/pdf"
internal const val MIME_PNG = "image/png"
internal const val MIME_TEXT = "text/plain"
internal const val MIME_ZIP = "application/zip"

/**
 * Lo que pide "Guardar como…": un documento, con su tipo y el nombre sugerido.
 * Varias imágenes no entran en un solo documento: [zip] las junta en un `.zip` (una PNG por hoja).
 * Una sola imagen sigue siendo PNG. Compartir no pasa por acá y manda las PNG sueltas.
 */
internal data class SaveTarget(val mime: String, val suggestedName: String, val zip: Boolean)

internal fun saveTarget(title: String, format: Exporter.Format, sourceNames: List<String>): SaveTarget {
    val base = Render.fileName(title)
    return when (format) {
        Exporter.Format.PDF -> SaveTarget(MIME_PDF, suggestedFileName("$base.pdf", sourceNames.singleOrNull()), zip = false)
        Exporter.Format.TEXT -> SaveTarget(MIME_TEXT, suggestedFileName("$base.txt", sourceNames.singleOrNull()), zip = false)
        Exporter.Format.NOTE -> SaveTarget(
            Exporter.BUNDLE_MIME,
            suggestedFileName("$base${Exporter.BUNDLE_EXT}", sourceNames.singleOrNull()),
            zip = false,
        )
        Exporter.Format.IMAGES -> if (sourceNames.size > 1) {
            SaveTarget(MIME_ZIP, "$base.zip", zip = true)
        } else {
            SaveTarget(MIME_PNG, suggestedFileName("$base.png", sourceNames.singleOrNull()), zip = false)
        }
    }
}

/** Nombre que ve el usuario. Si hay un archivo, se usa el suyo, sin carpetas. */
internal fun suggestedFileName(fallback: String, sourceName: String?): String {
    val raw = sourceName?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
    return raw.ifBlank { fallback }
}

/**
 * Nombres de las entradas de un zip, en el mismo orden, sin carpetas y sin repetidos
 * (`hoja.png`, `hoja-2.png`, …).
 */
internal fun uniqueZipNames(names: List<String>): List<String> {
    val used = HashSet<String>()
    return names.map { raw ->
        val cleaned = raw.substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { "archivo" }
        if (used.add(cleaned)) return@map cleaned
        val dot = cleaned.lastIndexOf('.')
        val stem = if (dot > 0) cleaned.substring(0, dot) else cleaned
        val ext = if (dot > 0) cleaned.substring(dot) else ""
        var i = 2
        var candidate = "$stem-$i$ext"
        while (!used.add(candidate)) {
            i++
            candidate = "$stem-$i$ext"
        }
        candidate
    }
}

/** Comprime `files` en `dest`. Cada entrada lleva el nombre del archivo, no su ruta. */
internal fun zipFiles(dest: File, files: List<File>) {
    val names = uniqueZipNames(files.map { it.name })
    ZipOutputStream(dest.outputStream().buffered()).use { zip ->
        for (i in files.indices) {
            zip.putNextEntry(ZipEntry(names[i]))
            files[i].inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

/**
 * `file` está adentro de `root`. Un nombre que sólo comparte el prefijo (`exports-otro`) no,
 * ni un `..` que se sale de la carpeta.
 */
internal fun isInside(root: File, file: File): Boolean = runCatching {
    val base = root.canonicalFile
    val path = file.canonicalFile
    path == base || path.path.startsWith(base.path + File.separator)
}.getOrDefault(false)

/**
 * "Guardar como…" con el selector de documentos del sistema. Hay un registro por tipo porque
 * [ActivityResultContracts.CreateDocument] fija el MIME en el contrato.
 *
 * Se construye como campo de la pantalla, antes de `onStart`: después el registro de resultados
 * no acepta altas. Si la pantalla se recrea con el selector abierto, el archivo pendiente vuelve
 * del estado guardado.
 */
class ExportSaver(private val activity: ComponentActivity) {
    private var pending: File? = null

    private val launchers: Map<String, ActivityResultLauncher<String>> = MIMES.associateWith { mime ->
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri -> write(uri) }
    }

    init {
        activity.savedStateRegistry.registerSavedStateProvider(STATE_KEY) {
            val file = pending
            if (file == null) Bundle() else Bundle().apply { putString(STATE_PATH, file.absolutePath) }
        }
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_CREATE) restore()
        })
    }

    /** Abre el selector. `file` tiene que estar en `cache/exports`, que es donde escribe [Exporter]. */
    fun open(mime: String, suggestedName: String, file: File) {
        val launcher = launchers[mime]
        val exports = File(activity.cacheDir, "exports")
        if (launcher == null || !file.isFile || !isInside(exports, file)) {
            activity.toast(activity.getString(R.string.export_save_failed, activity.getString(R.string.export_save_missing)), long = true)
            return
        }
        pending = file
        launcher.launch(suggestedName.ifBlank { file.name })
    }

    private fun restore() {
        val state = activity.savedStateRegistry.consumeRestoredStateForKey(STATE_KEY) ?: return
        val path = state.getString(STATE_PATH) ?: return
        val file = File(path)
        if (file.isFile && isInside(File(activity.cacheDir, "exports"), file)) pending = file
    }

    private fun write(uri: Uri?) {
        val file = pending
        pending = null
        if (uri == null || file == null) return
        if (!file.isFile) {
            activity.toast(activity.getString(R.string.export_save_failed, activity.getString(R.string.export_save_missing)), long = true)
            return
        }
        val resolver = activity.applicationContext.contentResolver
        val missing = activity.getString(R.string.export_save_missing)
        Thread({
            val result = runCatching {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: error(missing)
            }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                result.onSuccess { activity.hint(activity.getString(R.string.export_saved)) }
                    .onFailure { activity.toast(activity.getString(R.string.export_save_failed, it.message ?: missing), long = true) }
            }
        }, "tinta-guardar").start()
    }

    private companion object {
        const val STATE_KEY = "tinta-guardar-como"
        const val STATE_PATH = "path"

        /** El orden no puede cambiar: de él salen las claves del registro de resultados. */
        val MIMES = listOf(MIME_PDF, MIME_PNG, MIME_TEXT, MIME_ZIP, Exporter.BUNDLE_MIME)
    }
}
