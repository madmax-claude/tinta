package com.octavio.tinta.storage

/**
 * Cómo quedó configurada la copia. Las versiones anteriores guardaban un solo archivo
 * (`uri`, sin carpeta). Reescribir ese archivo lo vacía antes de terminar, así que no se
 * vuelve a subir hasta que la persona elige una carpeta. El archivo viejo se sigue pudiendo leer.
 */
object BackupSetup {
    enum class Kind {
        /** Nadie eligió destino. */
        DISCONNECTED,

        /** Hay carpeta: se puede publicar un archivo nuevo. */
        FOLDER,

        /** El destino de antes es un solo archivo. No se escribe; hay que avisar siempre. */
        LEGACY_FILE,
    }

    fun kind(folderUri: String?, targetUri: String?): Kind = when {
        !folderUri.isNullOrEmpty() -> Kind.FOLDER
        targetUri.isNullOrEmpty() -> Kind.DISCONNECTED
        else -> Kind.LEGACY_FILE
    }

    /**
     * El archivo único de antes, para traer las notas. Null si no hay, o si lo guardado es la
     * carpeta (no se puede abrir como zip).
     */
    fun legacyRestoreUri(targetUri: String?, targetIsFolderGrant: Boolean): String? =
        if (targetUri.isNullOrEmpty() || targetIsFolderGrant) null else targetUri
}
