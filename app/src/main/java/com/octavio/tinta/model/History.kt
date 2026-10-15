package com.octavio.tinta.model

/**
 * Deshacer y rehacer sobre las listas de una nota (trazos, imágenes, textos). Como se deshace en
 * orden inverso, al deshacer un cambio la lista está exactamente como quedó después de aplicarlo,
 * así que los índices guardados valen.
 */
class History {

    sealed interface Edit {
        fun undo()
        fun redo()

        /** Si el cambio toca esa lista. */
        fun affects(list: List<*>): Boolean
    }

    /** Elementos agregados al final de la lista. */
    class Add<T>(private val list: MutableList<T>, val added: List<T>) : Edit {
        override fun undo() = repeat(added.size) { list.removeAt(list.lastIndex) }
        override fun redo() {
            list.addAll(added)
        }
        override fun affects(list: List<*>) = list === this.list
    }

    /** Elementos quitados, en el orden en que se quitaron, con el índice que tenían en ese momento. */
    class Remove<T>(private val list: MutableList<T>, val removed: List<Pair<Int, T>>) : Edit {
        override fun undo() = removed.asReversed().forEach { (i, item) -> list.add(i, item) }
        override fun redo() = removed.forEach { (i, _) -> list.removeAt(i) }
        override fun affects(list: List<*>) = list === this.list
    }

    /** Elementos puestos en el lugar `index` (lo que había ahí y después se corre). */
    class Insert<T>(private val list: MutableList<T>, private val index: Int, val inserted: List<T>) : Edit {
        override fun undo() = repeat(inserted.size) { list.removeAt(index) }
        override fun redo() {
            list.addAll(index, inserted)
        }
        override fun affects(list: List<*>) = list === this.list
    }

    /** Un cambio fuera de las listas (por ejemplo, cuántas hojas tiene la nota). */
    class Change(private val undoAction: () -> Unit, private val redoAction: () -> Unit) : Edit {
        override fun undo() = undoAction()
        override fun redo() = redoAction()
        override fun affects(list: List<*>) = false
    }

    /** Varios cambios que se deshacen juntos (por ejemplo, mover una selección). */
    class Group(val edits: List<Edit>) : Edit {
        override fun undo() = edits.asReversed().forEach { it.undo() }
        override fun redo() = edits.forEach { it.redo() }
        override fun affects(list: List<*>) = edits.any { it.affects(list) }
    }

    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun <T> add(list: MutableList<T>, added: List<T>) {
        if (added.isEmpty()) return
        list.addAll(added)
        record(Add(list, added))
    }

    /** Registra elementos que ya se quitaron de la lista (el borrador los saca mientras pasa). */
    fun <T> recordRemoved(list: MutableList<T>, removed: List<Pair<Int, T>>) {
        if (removed.isNotEmpty()) record(Remove(list, removed))
    }

    /** Vacía las listas en un solo paso. */
    fun clear(vararg lists: MutableList<*>) {
        val edits = lists.filter { it.isNotEmpty() }.map { removeAll(it) }
        if (edits.isNotEmpty()) record(Group(edits))
    }

    /** Registra un cambio ya hecho. */
    fun record(edit: Edit) {
        undoStack.addLast(edit)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
    }

    /** Deshace el último cambio y lo devuelve, o null si no había. */
    fun undo(): Edit? {
        val edit = undoStack.removeLastOrNull() ?: return null
        edit.undo()
        redoStack.addLast(edit)
        return edit
    }

    fun redo(): Edit? {
        val edit = redoStack.removeLastOrNull() ?: return null
        edit.redo()
        undoStack.addLast(edit)
        return edit
    }

    companion object {
        private const val MAX_UNDO = 200

        /** Quita los elementos en esos índices y devuelve el cambio (sin registrarlo). */
        fun <T> removeAt(list: MutableList<T>, indices: Collection<Int>): Remove<T> {
            val removed = indices.sortedDescending().distinct().map { it to list.removeAt(it) }
            return Remove(list, removed)
        }

        private fun <T> removeAll(list: MutableList<T>): Remove<T> = removeAt(list, list.indices.toList())
    }
}
