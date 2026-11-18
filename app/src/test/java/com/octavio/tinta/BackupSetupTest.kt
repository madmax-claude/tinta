package com.octavio.tinta

import com.octavio.tinta.storage.BackupSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupSetupTest {
    private val file = "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dvieja"
    private val folder = "content://com.android.externalstorage.documents/tree/primary%3ATinta"

    @Test
    fun aSingleFileFromBeforeStaysReadableAndDoesNotLookReady() {
        assertEquals(BackupSetup.Kind.LEGACY_FILE, BackupSetup.kind(folderUri = null, targetUri = file))
        assertEquals(BackupSetup.Kind.LEGACY_FILE, BackupSetup.kind(folderUri = "", targetUri = file))
        assertEquals(file, BackupSetup.legacyRestoreUri(file, targetIsFolderGrant = false))
    }

    @Test
    fun choosingAFolderLeavesTheLegacyState() {
        assertEquals(BackupSetup.Kind.FOLDER, BackupSetup.kind(folderUri = folder, targetUri = file))
        assertEquals(BackupSetup.Kind.FOLDER, BackupSetup.kind(folderUri = folder, targetUri = null))
    }

    @Test
    fun nothingConfiguredIsDisconnected() {
        assertEquals(BackupSetup.Kind.DISCONNECTED, BackupSetup.kind(null, null))
        assertEquals(BackupSetup.Kind.DISCONNECTED, BackupSetup.kind("", ""))
        assertNull(BackupSetup.legacyRestoreUri(null, targetIsFolderGrant = false))
    }

    @Test
    fun aFolderGrantIsNotOpenedAsIfItWereTheZip() {
        assertNull(BackupSetup.legacyRestoreUri(folder, targetIsFolderGrant = true))
    }
}
