package com.octavio.tinta.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/**
 * Carpeta elegida con el selector (`ACTION_OPEN_DOCUMENT_TREE`). Los documentos nuevos se crean
 * al lado de los que ya están: el que tiene la copia buena no se abre para escribir.
 *
 * Drive no acepta el modo `"wt"` y trata `"w"` como truncar. `"rw"` y `"wa"` tampoco andan
 * (el archivo no se puede buscar). Por eso el modo se elige sobre un documento recién creado,
 * que todavía no tiene una copia, y se prueba el que el proveedor acepte.
 */
internal class SafBackupDirectory(
    private val resolver: ContentResolver,
    private val tree: Uri,
) : BackupDirectory {
    private val folderId: String = folderDocumentId(tree)
    private val parent: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, folderId)

    override fun documents(): List<BackupDocument> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val found = ArrayList<BackupDocument>()
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (mimeCol >= 0 && !cursor.isNull(mimeCol) &&
                    cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR
                ) {
                    continue
                }
                val id = cursor.getString(idCol) ?: continue
                val name = if (cursor.isNull(nameCol)) "" else cursor.getString(nameCol)
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                found.add(SafBackupDocument(resolver, uri, name))
            }
        } ?: error("no se pudo leer la carpeta de la copia")
        return found
    }

    override fun create(name: String): BackupDocument {
        val created = DocumentsContract.createDocument(resolver, parent, CloudBackup.MIME, name)
            ?: error("no se pudo crear el archivo de la copia")
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(created))
        return SafBackupDocument(resolver, uri, queryName(uri) ?: name)
    }

    private fun queryName(uri: Uri): String? =
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    companion object {
        /** El id de la carpeta: el árbol que eligió la persona, o un documento adentro de ese árbol. */
        fun folderDocumentId(uri: Uri): String =
            if (uri.pathSegments.size >= 4) DocumentsContract.getDocumentId(uri)
            else DocumentsContract.getTreeDocumentId(uri)

        /** La raíz del árbol (`.../tree/id`), no un archivo de adentro. */
        fun isFolderGrant(uri: Uri): Boolean =
            DocumentsContract.isTreeUri(uri) && uri.pathSegments.size < 4
    }
}

internal class SafBackupDocument(
    private val resolver: ContentResolver,
    val uri: Uri,
    override val name: String,
) : BackupDocument {
    override val key: String = uri.toString()

    override fun openInput(): InputStream =
        resolver.openInputStream(uri) ?: throw FileNotFoundException(name)

    override fun openOutput(): OutputStream {
        // Un documento nuevo: truncarlo no toca la copia anterior. Drive rechaza "wt" y "rw".
        var last: Exception? = null
        for (mode in WRITE_MODES) {
            try {
                resolver.openOutputStream(uri, mode)?.let { return it }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: FileNotFoundException(name)
    }

    override fun delete() {
        if (!DocumentsContract.deleteDocument(resolver, uri)) error("no se pudo borrar $name")
    }

    private companion object {
        val WRITE_MODES = arrayOf("w", "rwt", "wt")
    }
}
