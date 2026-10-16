package org.shadowice.flocke.andotp.Utilities

import android.content.Context
import android.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Database.Entry
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Destructive fixture setup: disposable test installations only. */
@RunWith(AndroidJUnit4::class)
class VaultFailureSecurityTest {
    private lateinit var context: Context
    private lateinit var key: SecretKey

    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext()
        DatabaseHelper.wipeDatabase(context)
        KeyStoreHelper.wipeKeys(context)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        key = SecretKeySpec(ByteArray(16), "AES")
    }

    @After fun cleanup() {
        DatabaseHelper.wipeDatabase(context)
        KeyStoreHelper.wipeKeys(context)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun file(name: String): File = File(context.getFilesDir(), name)

    private fun initialize() {
        assertTrue(
            DatabaseHelper.changeEncryption(context, null, key, true) { id ->
                PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putString(DatabaseHelper.TRANSACTION_ID, id)
                    .commit()
            }
        )
    }

    @Test fun missingIsNotEmptyAndNormalSaveCannotInitialize() {
        assertEquals(DatabaseHelper.Status.MISSING, DatabaseHelper.loadDatabaseResult(context, key).status)
        assertThrows(DatabaseHelper.VaultException::class.java) { DatabaseHelper.loadDatabase(context, key) }
        assertFalse(DatabaseHelper.saveDatabase(context, arrayListOf(), key))
        initialize()
        assertTrue(DatabaseHelper.loadDatabase(context, key).isEmpty())
    }

    @Test fun failedAuthenticationCannotOverwriteVaultOrMigrate() {
        initialize()
        val original = FileHelper.readFileToBytes(file(Constants.FILENAME_DATABASE))
        val wrongBytes = ByteArray(16).also { it[0] = 1 }
        val wrong = SecretKeySpec(wrongBytes, "AES")
        assertEquals(DatabaseHelper.Status.AUTHENTICATION_FAILED, DatabaseHelper.loadDatabaseResult(context, wrong).status)
        assertFalse(DatabaseHelper.saveDatabase(context, arrayListOf(), wrong))
        assertFalse(
            DatabaseHelper.changeEncryption(context, wrong, key, false) {
                fail("Must not commit after failed load")
                true
            }
        )
        assertArrayEquals(original, FileHelper.readFileToBytes(file(Constants.FILENAME_DATABASE)))
    }

    @Test fun corruptAndPartialJsonAreRejectedAllOrNothing() {
        val entry = "{\"secret\":\"GEZDGNBVGY3TQOJQ\",\"label\":\"synthetic\"}"
        assertEquals(1, DatabaseHelper.stringToEntries("[$entry]").size)
        assertThrows(DatabaseHelper.VaultException::class.java) { DatabaseHelper.stringToEntries("[$entry,{}]") }
        assertThrows(DatabaseHelper.VaultException::class.java) { DatabaseHelper.stringToEntries("[$entry,") }
        assertThrows(DatabaseHelper.VaultException::class.java) { DatabaseHelper.stringToEntries("[$entry] []") }
        assertThrows(DatabaseHelper.VaultException::class.java) {
            DatabaseHelper.stringToEntries("[{'secret':'GEZDGNBVGY3TQOJQ','label':'synthetic'}]")
        }
        assertThrows(DatabaseHelper.VaultException::class.java) {
            DatabaseHelper.stringToEntries("[{\"secret\":\"A!\",\"label\":\"synthetic\"}]")
        }
    }

    @Test fun missingKeyLookupDoesNotInventReplacementForExistingVault() {
        initialize()
        assertNull(KeyStoreHelper.loadEncryptionKeyFromKeyStore(context, true))
        assertFalse(file(Constants.FILENAME_ENCRYPTED_KEY).exists())
        assertEquals(DatabaseHelper.Status.MISSING_KEY, DatabaseHelper.loadDatabaseResult(context, null).status)
    }

    @Test fun crashBeforePreferenceCommitKeepsOriginalCiphertext() {
        initialize()
        val original = FileHelper.readFileToBytes(file(Constants.FILENAME_DATABASE))
        val stage = ByteArray(36 + 28)
        System.arraycopy(
            "00000000-0000-0000-0000-000000000000".toByteArray(StandardCharsets.US_ASCII),
            0,
            stage,
            0,
            36
        )
        FileHelper.writeBytesToFile(file(DatabaseHelper.PENDING_FILE), stage)
        assertTrue(DatabaseHelper.recoverPendingDatabase(context))
        assertArrayEquals(original, FileHelper.readFileToBytes(file(Constants.FILENAME_DATABASE)))
        assertFalse(file(DatabaseHelper.PENDING_FILE).exists())
    }

    @Test fun crashAfterPreferenceCommitPromotesStagedCiphertext() {
        initialize()
        val id = "11111111-1111-1111-1111-111111111111"
        val encrypted = EncryptionHelper.encrypt(key, "[]".toByteArray(StandardCharsets.UTF_8))
        val stage = ByteArray(36 + encrypted.size)
        System.arraycopy(id.toByteArray(StandardCharsets.US_ASCII), 0, stage, 0, 36)
        System.arraycopy(encrypted, 0, stage, 36, encrypted.size)
        FileHelper.writeBytesToFile(file(DatabaseHelper.PENDING_FILE), stage)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(DatabaseHelper.TRANSACTION_ID, id)
            .commit()
        assertTrue(DatabaseHelper.recoverPendingDatabase(context))
        assertArrayEquals(encrypted, FileHelper.readFileToBytes(file(Constants.FILENAME_DATABASE)))
        assertTrue(DatabaseHelper.loadDatabase(context, key).isEmpty())
    }

    @Test fun failedPreferenceCommitBlocksWritesUntilRestartOrExplicitWipe() {
        initialize()
        assertFalse(DatabaseHelper.changeEncryption(context, key, key, false) { false })
        assertEquals(DatabaseHelper.Status.IO_FAILURE, DatabaseHelper.loadDatabaseResult(context, key).status)
        assertFalse(DatabaseHelper.saveDatabase(context, arrayListOf(), key))
    }

    @Test fun typedReadDistinguishesMissingFromOwnedEmptyVault() {
        val missing = DatabaseHelper.readVault(context, key) as VaultReadResult.Unavailable
        assertEquals(VaultReadError.MISSING, missing.error)
        initialize()
        val result = DatabaseHelper.readVault(context, key) as VaultReadResult.Loaded
        assertEquals("VaultReadResult.Loaded(redacted)", result.toString())
        assertTrue(result.takeEntries().isEmpty())
        assertThrows(IllegalStateException::class.java) { result.takeEntries() }
        result.close()
    }

    @Test fun loadedCloseDestroysOnlyUntransferredEntries() {
        val first = Entry("otpauth://totp/synthetic?secret=GEZDGNBVGY3TQOJQ")
        val closed = VaultReadResult.Loaded(listOf(first))
        closed.close()
        closed.close()
        assertNull(first.getSecret())
        assertThrows(IllegalStateException::class.java) { closed.takeEntries() }

        val second = Entry("otpauth://totp/synthetic?secret=GEZDGNBVGY3TQOJQ")
        val transferred = VaultReadResult.Loaded(listOf(second))
        val owned = transferred.takeEntries()
        transferred.close()
        assertTrue(second.getSecret()!!.isNotEmpty())
        owned.forEach { it.destroy() }
    }

    @Test fun typedPreCommitCancellationPreservesAuthoritativeVault() {
        initialize()
        VaultSession.open(key)
        val original = FileHelper.readFileToBytes(file(Constants.FILENAME_DATABASE))
        val result = DatabaseHelper.changeVaultEncryption(context, key, key, false) { VaultCommitDecision.CANCELED }
        assertEquals(VaultWriteError.CANCELED, (result as VaultWriteResult.Rejected).error)
        assertSame(key, VaultSession.getKey())
        assertTrue(DatabaseHelper.recoverPendingDatabase(context))
        assertArrayEquals(original, FileHelper.readFileToBytes(file(Constants.FILENAME_DATABASE)))
    }

    @Test fun typedUnknownMetadataCommitRevokesAndQuarantinesSession() {
        initialize()
        VaultSession.open(key)
        val result = DatabaseHelper.changeVaultEncryption(context, key, key, false) { VaultCommitDecision.UNKNOWN }
        assertEquals(VaultCommitState.UNKNOWN, (result as VaultWriteResult.RecoveryRequired).commitState)
        assertTrue(VaultSession.snapshot() is SessionState.Failed)
        assertNull(VaultSession.getKey())
        val unavailable = DatabaseHelper.readVault(context, key) as VaultReadResult.Unavailable
        assertEquals(VaultReadError.RECOVERY_REQUIRED, unavailable.error)
        assertTrue(DatabaseHelper.saveVault(context, arrayListOf(), key) is VaultWriteResult.RecoveryRequired)
    }

    @Test fun typedCommittedPromotionFailureIsNotReportedAsRollback() {
        initialize()
        VaultSession.open(key)
        val result = DatabaseHelper.changeVaultEncryption(context, key, key, false) { id ->
            val committed = PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(DatabaseHelper.TRANSACTION_ID, id)
                .commit()
            assertTrue(committed)
            // Force promotion failure without modifying the staged ciphertext or committed UUID.
            assertTrue(file(Constants.FILENAME_DATABASE).delete())
            assertTrue(file(Constants.FILENAME_DATABASE).mkdir())
            try {
                assertTrue(File(file(Constants.FILENAME_DATABASE), "block-promotion").createNewFile())
            } catch (_: IOException) {
                throw IllegalStateException("Fixture setup failed")
            }
            VaultCommitDecision.COMMITTED
        }
        try {
            assertEquals(VaultCommitState.COMMITTED, (result as VaultWriteResult.RecoveryRequired).commitState)
            assertNull(VaultSession.getKey())
            assertTrue(file(DatabaseHelper.PENDING_FILE).exists())
        } finally {
            assertTrue(File(file(Constants.FILENAME_DATABASE), "block-promotion").delete())
            assertTrue(file(Constants.FILENAME_DATABASE).delete())
        }
        assertTrue(DatabaseHelper.recoverPendingDatabase(context))
        val recovered = DatabaseHelper.readVault(context, key)
        assertTrue(recovered is VaultReadResult.Loaded)
        (recovered as VaultReadResult.Loaded).close()
        assertNull(VaultSession.getKey()) // Storage recovery never authorizes a session.
    }
}
