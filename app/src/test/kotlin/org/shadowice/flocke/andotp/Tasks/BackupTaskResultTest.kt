package org.shadowice.flocke.andotp.Tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

import org.shadowice.flocke.andotp.R

class BackupTaskResultTest {
    @Test fun successFactoryReportsASuccessfulBackupWithTheDefaultMessage() {
        val result = BackupTaskResult.success(BackupTaskResult.ResultType.BACKUP, "andotp-backup.json")
        assertTrue(result.success)
        assertEquals(BackupTaskResult.ResultType.BACKUP, result.resultType)
        assertEquals("andotp-backup.json", result.payload)
        assertEquals(R.string.backup_toast_export_success, result.messageId)
        assertFalse(result.isPGP)
        assertNull(result.decryptIntent)
        assertNull(result.uri)
    }

    @Test fun failureFactoryKeepsTheGivenMessageAndNoPayload() {
        val result = BackupTaskResult.failure(BackupTaskResult.ResultType.RESTORE, 4242)
        assertFalse(result.success)
        assertEquals(BackupTaskResult.ResultType.RESTORE, result.resultType)
        assertNull(result.payload)
        assertEquals(4242, result.messageId)
    }

    @Test fun pgpConstructorCarriesItsOwnedExtras() {
        val result = BackupTaskResult(
                BackupTaskResult.ResultType.RESTORE, true, "decrypted", 0, true, null, null)
        assertTrue(result.isPGP)
        assertEquals(0, result.messageId)
        assertNull(result.decryptIntent)
        assertNull(result.uri)
        // The fields stay writable public fields, like the Java original.
        result.isPGP = false
        assertFalse(result.isPGP)
    }

    @Test fun diagnosticsNeverContainThePayload() {
        val result = BackupTaskResult.success(BackupTaskResult.ResultType.BACKUP, "secret-database-json")
        assertNotSame(result.payload, result.toString())
        assertFalse(result.toString().contains("secret-database-json"))
    }

    @Test fun resultTypeCoversAllThreeStates() {
        assertEquals(listOf("NONE", "BACKUP", "RESTORE"),
                BackupTaskResult.ResultType.values().map { it.name })
    }
}
