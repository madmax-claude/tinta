package com.octavio.tinta.storage

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * Carpetas de notas, en `notes/carpetas.json`: las carpetas (cada una puede estar dentro de otra,
 * con `padre`) y en cuál está cada nota. Las notas no se mueven de lugar en el disco. Una carpeta
 * cuyo padre no existe (o que formaría un círculo) cuenta como de arriba de todo.
 *
 * Se escribe en un temporal, se hace fsync y recién ahí se renombra: un corte de luz no puede
 * dejar el archivo a medias. Lo último que quedó bien se guarda en `carpetas.json.bak`. Si el
 * principal está cortado o no es JSON, se usa esa copia. Si ninguna se puede leer, la biblioteca
 * queda vacía en memoria y no se reescribe el archivo: crear, mover o borrar no pisan las
 * carpetas con un árbol vacío. Una copia de la nube sí puede resolverlo ([applyBackup]).
 */
class Library(private val file: File) {

    data class Folder(val id: String, val name: String, val parent: String? = null)

    private val folders = ArrayList<Folder>()
    private val placement = HashMap<String, String>()

    /** Falso si el archivo existe y ni él ni el `.bak` se pudieron leer: no se puede reescribir. */
    private var persistable = false

    init {
        val primary = parseFile(file)
        if (primary != null) {
            applyParsed(primary)
            persistable = true
            // La primera vez (o si el .bak se cortó) queda una copia de lo que sí se leyó.
            runCatching { healBak(file.readBytes()) }
        } else if (file.exists()) {
            val saved = parseFile(bakFile())
            if (saved != null) {
                applyParsed(saved)
                persistable = true
            }
        } else {
            persistable = true
        }
    }

    @Synchronized
    fun folders(): List<Folder> = folders.toList()

    @Synchronized
    fun folder(id: String?): Folder? = folders.firstOrNull { it.id == id }

    @Synchronized
    fun folderOf(noteId: String): String? = placement[noteId]

    /** La carpeta de arriba de `id` (null si está arriba de todo). */
    @Synchronized
    fun parentOf(id: String): String? = folders.firstOrNull { it.id == id }?.let(::effectiveParent)

    /** Las carpetas que están directamente adentro de `parent` (null: las de arriba de todo), por nombre. */
    @Synchronized
    fun children(parent: String?): List<Folder> = folders.filter { effectiveParent(it) == parent }

    /** De la carpeta de arriba de todo hasta `id`, inclusive. */
    @Synchronized
    fun path(id: String): List<Folder> {
        val out = ArrayList<Folder>()
        var f = folders.firstOrNull { it.id == id }
        val seen = HashSet<String>()
        while (f != null && seen.add(f.id)) {
            out.add(0, f)
            f = effectiveParent(f)?.let { p -> folders.firstOrNull { it.id == p } }
        }
        return out
    }

    /** `id` y todas las carpetas que tiene adentro, a cualquier profundidad. */
    @Synchronized
    fun subtree(id: String): Set<String> {
        val out = LinkedHashSet<String>()
        fun add(x: String) {
            if (!out.add(x)) return
            for (c in folders) if (effectiveParent(c) == x) add(c.id)
        }
        if (folders.any { it.id == id }) add(id)
        return out
    }

    @Synchronized
    fun create(name: String, parent: String? = null): Folder {
        val f = Folder(UUID.randomUUID().toString(), name, parent?.takeIf { p -> folders.any { it.id == p } })
        commit {
            folders.add(f)
            sort()
        }
        return f
    }

    /** Agrega una carpeta con ese id si no está (al importar). */
    @Synchronized
    fun adopt(id: String, name: String, parent: String? = null) {
        if (folders.any { it.id == id }) return
        commit {
            folders.add(Folder(id, name, parent))
            sort()
        }
    }

    /**
     * Aplica las carpetas y todas las ubicaciones de una copia (`carpetas.json`).
     * Las carpetas que ya están actualizan nombre y padre; las que la copia no trae se quedan.
     * Cada nota que figura en la copia vuelve a su carpeta, aunque el archivo de la nota no se
     * haya reemplazado. Las que la copia no menciona no se mueven. Si el archivo local no se
     * podía leer, esto lo deja resuelto (salvo que la copia tampoco traiga nada).
     */
    @Synchronized
    fun applyBackup(o: JSONObject) {
        val incomingFolders = parseFolders(o)
        val incomingNotes = parseNotes(o)
        if (!persistable && incomingFolders.isEmpty() && incomingNotes.isEmpty()) damaged()
        val snapFolders = folders.toList()
        val snapPlacement = HashMap(placement)
        var changed = false
        var done = false
        try {
            for (f in incomingFolders) {
                val i = folders.indexOfFirst { it.id == f.id }
                if (i < 0) {
                    folders.add(f)
                    changed = true
                } else if (folders[i].name != f.name || folders[i].parent != f.parent) {
                    folders[i] = folders[i].copy(name = f.name, parent = f.parent)
                    changed = true
                }
            }
            if (changed) sort()
            val known = HashSet<String>(folders.size).apply { folders.forEach { add(it.id) } }
            for ((id, folderId) in incomingNotes) {
                if (folderId !in known || placement[id] == folderId) continue
                placement[id] = folderId
                changed = true
            }
            if (!changed) {
                if (!persistable) damaged()
            } else {
                persist(allowUnresolved = true)
            }
            done = true
        } finally {
            if (!done) restore(snapFolders, snapPlacement)
        }
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val i = folders.indexOfFirst { it.id == id }
        if (i < 0 || folders[i].name == name) return
        commit {
            folders[i] = folders[i].copy(name = name)
            sort()
        }
    }

    /** Pone la carpeta dentro de `parent` (null: arriba de todo). No se puede meter adentro de sí misma. */
    @Synchronized
    fun moveFolder(id: String, parent: String?): Boolean {
        val i = folders.indexOfFirst { it.id == id }
        if (i < 0) return false
        if (parent != null && (parent in subtree(id) || folders.none { it.id == parent })) return false
        if (folders[i].parent == parent) return true
        commit { folders[i] = folders[i].copy(parent = parent) }
        return true
    }

    /** Borra la carpeta; lo que tenía (notas y carpetas) pasa a la carpeta de arriba. */
    @Synchronized
    fun delete(id: String) {
        val f = folders.firstOrNull { it.id == id } ?: return
        val up = effectiveParent(f)
        commit {
            folders.remove(f)
            for (i in folders.indices) if (folders[i].parent == id) folders[i] = folders[i].copy(parent = up)
            for (k in placement.keys.toList()) if (placement[k] == id) {
                if (up == null) placement.remove(k) else placement[k] = up
            }
        }
    }

    /** Borra la carpeta con todas las de adentro; las notas que tenían quedan sin carpeta. Devuelve las borradas. */
    @Synchronized
    fun deleteTree(id: String): Set<String> {
        val gone = subtree(id)
        if (gone.isEmpty()) return gone
        commit {
            folders.removeAll { it.id in gone }
            placement.values.removeAll { it in gone }
        }
        return gone
    }

    /** Pone la nota en la carpeta, o la deja sin carpeta con null. */
    @Synchronized
    fun move(noteId: String, folderId: String?) {
        val target = folderId?.takeIf { id -> folders.any { it.id == id } }
        if (placement[noteId] == target) return
        commit {
            if (target == null) placement.remove(noteId) else placement[noteId] = target
        }
    }

    @Synchronized
    fun forget(noteId: String) {
        if (!placement.containsKey(noteId)) return
        commit { placement.remove(noteId) }
    }

    /** El padre si existe y no hace un círculo; si no, null (arriba de todo). */
    private fun effectiveParent(f: Folder): String? {
        var p = f.parent ?: return null
        val seen = hashSetOf(f.id)
        val first = p
        while (true) {
            if (!seen.add(p)) return null // círculo
            val up = folders.firstOrNull { it.id == p } ?: return null
            p = up.parent ?: return first
        }
    }

    private fun sort() = folders.sortBy { it.name.lowercase() }

    private fun commit(block: () -> Unit) {
        if (!persistable) damaged()
        val snapFolders = folders.toList()
        val snapPlacement = HashMap(placement)
        var done = false
        try {
            block()
            persist(allowUnresolved = false)
            done = true
        } finally {
            if (!done) restore(snapFolders, snapPlacement)
        }
    }

    private fun restore(foldersSnap: List<Folder>, placementSnap: Map<String, String>) {
        folders.clear()
        folders.addAll(foldersSnap)
        placement.clear()
        placement.putAll(placementSnap)
    }

    private fun damaged(): Nothing = error("carpetas.json está dañado y no se reescribe")

    private data class Parsed(val folders: List<Folder>, val notes: Map<String, String>)

    private fun applyParsed(p: Parsed) {
        folders.clear()
        folders.addAll(p.folders)
        placement.clear()
        placement.putAll(p.notes)
    }

    /** Null si no es un archivo legible. No toca el estado. */
    private fun parseFile(f: File): Parsed? {
        if (!f.isFile) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        if (text.isBlank()) return null
        return runCatching { parse(JSONObject(text)) }.getOrNull()
    }

    private fun parse(o: JSONObject): Parsed {
        val fs = parseFolders(o)
        val notes = HashMap<String, String>()
        for ((k, v) in parseNotes(o)) if (fs.any { it.id == v }) notes[k] = v
        return Parsed(fs, notes)
    }

    private fun parseFolders(o: JSONObject): List<Folder> {
        if (o.has("carpetas") && o.optJSONArray("carpetas") == null) throw JSONException("carpetas")
        val list = o.optJSONArray("carpetas") ?: JSONArray()
        val out = ArrayList<Folder>(list.length())
        for (i in 0 until list.length()) {
            val f = list.getJSONObject(i)
            out.add(Folder(f.getString("id"), f.getString("nombre"), f.optString("padre").ifEmpty { null }))
        }
        out.sortBy { it.name.lowercase() }
        return out
    }

    /** Todas las ubicaciones, aunque la carpeta todavía no esté en esta biblioteca. */
    private fun parseNotes(o: JSONObject): Map<String, String> {
        if (o.has("notas") && o.optJSONObject("notas") == null) throw JSONException("notas")
        val notes = o.optJSONObject("notas") ?: JSONObject()
        val out = LinkedHashMap<String, String>(notes.length())
        for (key in notes.keys()) out[key] = notes.getString(key)
        return out
    }

    private fun toJson(): String {
        val o = JSONObject()
        o.put("carpetas", JSONArray().apply {
            folders.forEach { f ->
                put(JSONObject().put("id", f.id).put("nombre", f.name).apply { f.parent?.let { put("padre", it) } })
            }
        })
        o.put("notas", JSONObject().apply { placement.forEach { (k, v) -> put(k, v) } })
        return o.toString(2)
    }

    /**
     * Reemplaza el archivo con el estado en memoria. Si [allowUnresolved] es falso y el archivo
     * no se pudo leer al abrir, no lo toca. El temporal se sincroniza antes del rename; si el
     * rename falla, se avisa y la memoria del que llamó se revierte en [commit].
     */
    private fun persist(allowUnresolved: Boolean) {
        if (!persistable && !allowUnresolved) damaged()
        val bytes = toJson().toByteArray(Charsets.UTF_8)
        val tmp = File(file.path + ".tmp")
        var renamed = false
        try {
            writeSynced(tmp, bytes)
            if (!tmp.renameTo(file)) error("no se pudo guardar carpetas.json")
            renamed = true
        } finally {
            if (!renamed) tmp.delete()
        }
        fsyncDir(file.parentFile)
        persistable = true
        // El principal ya quedó. Si la copia de respaldo falla, no se deshace el guardado.
        runCatching { writeBak(bytes) }
    }

    /** Deja en el `.bak` los mismos bytes, si todavía no están. */
    private fun healBak(bytes: ByteArray) {
        val bak = bakFile()
        if (bak.isFile && bak.length() == bytes.size.toLong() && bak.readBytes().contentEquals(bytes)) return
        writeBak(bytes)
    }

    private fun writeBak(bytes: ByteArray) {
        val bakTmp = File(file.path + ".bak.tmp")
        var renamed = false
        try {
            writeSynced(bakTmp, bytes)
            if (!bakTmp.renameTo(bakFile())) error("no se pudo guardar carpetas.json.bak")
            renamed = true
        } finally {
            if (!renamed) bakTmp.delete()
        }
        fsyncDir(file.parentFile)
    }

    private fun writeSynced(target: File, bytes: ByteArray) {
        FileOutputStream(target).use { fos ->
            fos.write(bytes)
            fos.fd.sync() // un corte de luz no puede dejar el archivo a medias antes del rename
        }
    }

    /** Para que el rename quede en el directorio y no sólo en los datos del archivo. */
    private fun fsyncDir(dir: File?) {
        if (dir == null || !dir.isDirectory) return
        runCatching {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private fun bakFile() = File(file.path + ".bak")
}
