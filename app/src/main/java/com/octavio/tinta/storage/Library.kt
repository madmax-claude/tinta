package com.octavio.tinta.storage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Carpetas de notas, en `notes/carpetas.json`: las carpetas (cada una puede estar dentro de otra,
 * con `padre`) y en cuál está cada nota. Las notas no se mueven de lugar en el disco. Una carpeta
 * cuyo padre no existe (o que formaría un círculo) cuenta como de arriba de todo.
 */
class Library(private val file: File) {

    data class Folder(val id: String, val name: String, val parent: String? = null)

    private val folders = ArrayList<Folder>()
    private val placement = HashMap<String, String>()

    init {
        runCatching { if (file.exists()) read(JSONObject(file.readText())) }
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
        folders.add(f)
        sort()
        write()
        return f
    }

    /** Agrega una carpeta con ese id si no está (al restaurar una copia). */
    @Synchronized
    fun adopt(id: String, name: String, parent: String? = null) {
        if (folders.any { it.id == id }) return
        folders.add(Folder(id, name, parent))
        sort()
        write()
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val i = folders.indexOfFirst { it.id == id }
        if (i < 0) return
        folders[i] = folders[i].copy(name = name)
        sort()
        write()
    }

    /** Pone la carpeta dentro de `parent` (null: arriba de todo). No se puede meter adentro de sí misma. */
    @Synchronized
    fun moveFolder(id: String, parent: String?): Boolean {
        val i = folders.indexOfFirst { it.id == id }
        if (i < 0) return false
        if (parent != null && (parent in subtree(id) || folders.none { it.id == parent })) return false
        folders[i] = folders[i].copy(parent = parent)
        write()
        return true
    }

    /** Borra la carpeta; lo que tenía (notas y carpetas) pasa a la carpeta de arriba. */
    @Synchronized
    fun delete(id: String) {
        val f = folders.firstOrNull { it.id == id } ?: return
        val up = effectiveParent(f)
        folders.remove(f)
        for (i in folders.indices) if (folders[i].parent == id) folders[i] = folders[i].copy(parent = up)
        for (k in placement.keys.toList()) if (placement[k] == id) {
            if (up == null) placement.remove(k) else placement[k] = up
        }
        write()
    }

    /** Borra la carpeta con todas las de adentro; las notas que tenían quedan sin carpeta. Devuelve las borradas. */
    @Synchronized
    fun deleteTree(id: String): Set<String> {
        val gone = subtree(id)
        folders.removeAll { it.id in gone }
        placement.values.removeAll { it in gone }
        write()
        return gone
    }

    /** Pone la nota en la carpeta, o la deja sin carpeta con null. */
    @Synchronized
    fun move(noteId: String, folderId: String?) {
        if (folderId == null || folders.none { it.id == folderId }) placement.remove(noteId) else placement[noteId] = folderId
        write()
    }

    @Synchronized
    fun forget(noteId: String) {
        if (placement.remove(noteId) != null) write()
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

    private fun read(o: JSONObject) {
        val list = o.optJSONArray("carpetas") ?: JSONArray()
        for (i in 0 until list.length()) {
            val f = list.getJSONObject(i)
            folders.add(Folder(f.getString("id"), f.getString("nombre"), f.optString("padre").ifEmpty { null }))
        }
        sort()
        val notes = o.optJSONObject("notas") ?: JSONObject()
        for (key in notes.keys()) {
            val folder = notes.getString(key)
            if (folders.any { it.id == folder }) placement[key] = folder
        }
    }

    private fun write() {
        val o = JSONObject()
        o.put("carpetas", JSONArray().apply {
            folders.forEach { f ->
                put(JSONObject().put("id", f.id).put("nombre", f.name).apply { f.parent?.let { put("padre", it) } })
            }
        })
        o.put("notas", JSONObject().apply { placement.forEach { (k, v) -> put(k, v) } })
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString(2))
        if (!tmp.renameTo(file)) tmp.delete()
    }
}
