package com.octavio.tinta

import com.octavio.tinta.model.History
import com.octavio.tinta.model.Note

/**
 * Las pestañas de cada mitad de la pantalla: la ventana 0 es la que se abre desde la lista de
 * notas y la 1 la que se abre al lado. Una nota está en las pestañas de una sola ventana; se
 * guardan en las preferencias para volver a abrirlas.
 */
object Tabs {
    private val lists = arrayOf(ArrayList<String>(), ArrayList<String>())
    private var loaded = false

    /** Las pantallas de cada ventana que están vivas, para avisarles cuando cambian sus pestañas. */
    val windows = arrayOfNulls<EditorActivity>(2)

    /** Qué nota muestra cada ventana ahora. */
    private val active = arrayOfNulls<String>(2)

    private fun load(prefs: Prefs) {
        if (loaded) return
        loaded = true
        for (k in 0..1) lists[k].addAll(prefs.tabs(k))
    }

    fun list(prefs: Prefs, slot: Int): List<String> {
        load(prefs)
        return lists[slot].toList()
    }

    /** Agrega la nota a las pestañas de `slot` (sacándola de la otra ventana si estaba ahí). */
    fun add(prefs: Prefs, slot: Int, id: String) {
        load(prefs)
        val other = 1 - slot
        if (lists[other].remove(id)) {
            prefs.setTabs(other, lists[other])
            windows[other]?.tabsChanged()
        }
        if (id !in lists[slot]) lists[slot].add(id)
        prefs.setTabs(slot, lists[slot])
    }

    fun remove(prefs: Prefs, slot: Int, id: String) {
        load(prefs)
        if (lists[slot].remove(id)) prefs.setTabs(slot, lists[slot])
    }

    /** Pasa todas las pestañas de `from` al final de las de `to` (se cerró la ventana `from`). */
    fun moveAll(prefs: Prefs, from: Int, to: Int) {
        load(prefs)
        if (lists[from].isEmpty()) return
        for (id in lists[from]) if (id !in lists[to]) lists[to].add(id)
        lists[from].clear()
        prefs.setTabs(from, lists[from])
        prefs.setTabs(to, lists[to])
    }

    /** Saca de las pestañas las notas que ya no existen. */
    fun prune(prefs: Prefs, exists: (String) -> Boolean) {
        load(prefs)
        for (k in 0..1) if (lists[k].removeAll { !exists(it) }) prefs.setTabs(k, lists[k])
    }

    fun setActive(slot: Int, id: String?) {
        active[slot] = id
        if (id != null) last[slot] = id
    }

    /** La última nota que mostró la ventana (para seguir ahí si la pantalla se vuelve a crear). */
    private val last = arrayOfNulls<String>(2)

    fun lastShown(slot: Int) = last[slot]

    /** La ventana que muestra esa nota ahora, o -1. */
    fun showing(id: String) = active.indexOf(id)
}

/**
 * Notas ya abiertas, con su historial de deshacer, para volver a una pestaña sin leerla de nuevo.
 * Al cambiar de modo (oscuro) la pantalla se vuelve a crear: la nota pasa por acá sea del tamaño
 * que sea; las de las pestañas, sólo si son chicas, para no llenar la memoria.
 */
object NoteCache {
    class Entry(val note: Note, val history: History, val dirty: Boolean, val thumbnailStale: Boolean)

    private val map = LinkedHashMap<String, Entry>(8, 0.75f, true)

    fun put(entry: Entry, always: Boolean) {
        if (!always && entry.note.strokes.size > MAX_STROKES) {
            map.remove(entry.note.id)
            return
        }
        map[entry.note.id] = entry
        while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
    }

    fun take(id: String): Entry? = map.remove(id)

    /** La nota cambió en el disco desde otro lado (renombrada, movida, borrada): se olvida. */
    fun drop(id: String) {
        map.remove(id)
    }

    private const val MAX_STROKES = 6000
    private const val MAX_ENTRIES = 4
}
