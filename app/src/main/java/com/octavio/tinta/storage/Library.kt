package com.octavio.tinta.storage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Carpetas de notas, en `notes/carpetas.json`: la lista de carpetas y en cuál está cada nota. Las
 * notas no se mueven de lugar en el disco; borrar una carpeta deja sus notas sin carpeta.
 */
class Library(private val file: File) {

    data class Folder(val id: String, val name: String)

    private val folders = ArrayList<Folder>()
    private val placement = HashMap<String, String>()

    init {
        runCatching { if (file.exists()) read(JSONObject(file.readText())) }
    }

    @Synchronized
    fun folders(): List<Folder> = folders.toList()

    @Synchronized
    fun folderOf(noteId: String): String? = placement[noteId]

    @Synchronized
    fun create(name: String): Folder {
        val f = Folder(UUID.randomUUID().toString(), name)
        folders.add(f)
        folders.sortBy { it.name.lowercase() }
        write()
        return f
    }

    /** Agrega una carpeta con ese id si no está (al restaurar una copia). */
    @Synchronized
    fun adopt(id: String, name: String) {
        if (folders.any { it.id == id }) return
        folders.add(Folder(id, name))
        folders.sortBy { it.name.lowercase() }
        write()
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val i = folders.indexOfFirst { it.id == id }
        if (i < 0) return
        folders[i] = folders[i].copy(name = name)
        folders.sortBy { it.name.lowercase() }
        write()
    }

    @Synchronized
    fun delete(id: String) {
        folders.removeAll { it.id == id }
        placement.values.removeAll { it == id }
        write()
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

    private fun read(o: JSONObject) {
        val list = o.optJSONArray("carpetas") ?: JSONArray()
        for (i in 0 until list.length()) {
            val f = list.getJSONObject(i)
            folders.add(Folder(f.getString("id"), f.getString("nombre")))
        }
        val notes = o.optJSONObject("notas") ?: JSONObject()
        for (key in notes.keys()) {
            val folder = notes.getString(key)
            if (folders.any { it.id == folder }) placement[key] = folder
        }
    }

    private fun write() {
        val o = JSONObject()
        o.put("carpetas", JSONArray().apply { folders.forEach { put(JSONObject().put("id", it.id).put("nombre", it.name)) } })
        o.put("notas", JSONObject().apply { placement.forEach { (k, v) -> put(k, v) } })
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString(2))
        if (!tmp.renameTo(file)) tmp.delete()
    }
}
