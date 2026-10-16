package org.shadowice.flocke.andotp.Utilities

import android.content.Context
import android.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Tasks.BackupTaskResult
import org.shadowice.flocke.andotp.Tasks.UiBasedBackgroundTask
import java.io.File
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Destructive storage fixtures: disposable test installations only. */
@RunWith(AndroidJUnit4::class)
class VaultRestoreOperationTest {
    private lateinit var context: Context
    private lateinit var key: SecretKey
    private lateinit var persistence: EntryPersistence
    private lateinit var restore: VaultRestoreOperation
    private val owned = mutableListOf<Entry>()

    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext()
        DatabaseHelper.wipeDatabase(context)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        key = SecretKeySpec(ByteArray(16), "AES")
        val write = DatabaseHelper.changeVaultEncryption(context, null, key, true) { id ->
            VaultCommitDecision.fromCommit(
                PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putString(DatabaseHelper.TRANSACTION_ID, id)
                    .commit()
            )
        }
        assertTrue(write is VaultWriteResult.Saved)
        assertTrue(VaultSession.activate(key, VaultSession.generation()) is SessionCommandResult.Applied)
        persistence = EntryPersistence(context)
        restore = VaultRestoreOperation(context)
    }

    @After fun cleanup() {
        owned.forEach { it.destroy() }
        owned.clear()
        DatabaseHelper.wipeDatabase(context)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun entry(label: String, vararg tags: String): Entry {
        val entry = Entry(
            Entry.OTPType.TOTP,
            "GEZDGNBVGY3TQOJQ",
            30,
            6,
            "synthetic",
            label,
            TokenCalculator.HashAlgorithm.SHA1,
            tags.toMutableList()
        )
        owned.add(entry)
        return entry
    }

    private fun json(vararg entries: Entry): String = DatabaseHelper.entriesToString(ArrayList(entries.toList()))

    private fun seed(vararg entries: Entry) {
        assertTrue(
            persistence.save(ArrayList(entries.toList()), key, false, null) is VaultWriteResult.Saved
        )
    }

    private fun read(): ArrayList<Entry> {
        val loaded = persistence.load(key) as VaultReadResult.Loaded
        val entries = loaded.takeEntries()
        loaded.close()
        owned.addAll(entries)
        return entries
    }

    private fun ciphertext(): ByteArray =
        FileHelper.readFileToBytes(File(context.getFilesDir(), Constants.FILENAME_DATABASE))

    @Test fun mergePlacesNewEntriesFirstAndKeepsExistingDuplicateMetadata() {
        val existing = entry("existing", "original")
        existing.setUsedFrequency(42)
        seed(existing)
        val duplicate = entry("existing", "imported")
        val added = entry("new", "imported")
        assertEquals(
            VaultRestoreOperation.Result.SAVED,
            restore.restore(json(duplicate, added), false, key, VaultSession.generation(), BooleanSupplier { true })
        )
        val result = read()
        assertEquals(2, result.size)
        assertEquals("new", result[0].getLabel())
        assertEquals("existing", result[1].getLabel())
        assertEquals(listOf("original"), result[1].getTags())
        assertEquals(42L, result[1].getUsedFrequency())
        assertTrue(added.getSecret()!!.isNotEmpty()) // Operation owns decoded entries, not caller fixtures.
    }

    @Test fun replaceDoesNotMergeExistingEntries() {
        seed(entry("old"))
        assertEquals(
            VaultRestoreOperation.Result.SAVED,
            restore.restore(json(entry("replacement")), true, key, VaultSession.generation(), BooleanSupplier { true })
        )
        val result = read()
        assertEquals(1, result.size)
        assertEquals("replacement", result[0].getLabel())
    }

    @Test fun duplicateOnlyMergeIsSuccessfulAndKeepsExistingOrder() {
        seed(entry("first"), entry("second"))
        assertEquals(
            VaultRestoreOperation.Result.SAVED,
            restore.restore(json(entry("second")), false, key, VaultSession.generation(), BooleanSupplier { true })
        )
        val result = read()
        assertEquals(2, result.size)
        assertEquals("first", result[0].getLabel())
        assertEquals("second", result[1].getLabel())
    }

    @Test fun duplicateImportsRetainHistoricalStorageBehavior() {
        val duplicate = entry("same")
        assertEquals(
            VaultRestoreOperation.Result.SAVED,
            restore.restore(json(duplicate, duplicate), true, key, VaultSession.generation(), BooleanSupplier { true })
        )
        assertEquals(2, read().size) // Do not silently introduce a new import deduplication policy.
    }

    @Test fun emptyOrPartiallyInvalidImportCannotOverwriteExistingVault() {
        seed(entry("existing"))
        val before = ciphertext()
        assertEquals(
            VaultRestoreOperation.Result.EMPTY_IMPORT,
            restore.restore("[]", true, key, VaultSession.generation(), BooleanSupplier { true })
        )
        val valid = json(entry("new"))
        val partial = valid.substring(0, valid.length - 1) + ",{}]"
        assertEquals(
            VaultRestoreOperation.Result.INVALID_IMPORT,
            restore.restore(partial, true, key, VaultSession.generation(), BooleanSupplier { true })
        )
        assertArrayEquals(before, ciphertext())
    }

    @Test fun staleGenerationCannotRestoreEvenIfTheSameKeyObjectWasReactivated() {
        seed(entry("existing"))
        val before = ciphertext()
        val stale = VaultSession.generation()
        VaultSession.lock()
        VaultSession.activate(key, VaultSession.generation())
        assertEquals(
            VaultRestoreOperation.Result.SESSION_REVOKED,
            restore.restore(json(entry("new")), true, key, stale, BooleanSupplier { true })
        )
        assertArrayEquals(before, ciphertext())
    }

    @Test fun revocationOfPermissionAfterMergePreventsSave() {
        seed(entry("existing"))
        val before = ciphertext()
        val checks = AtomicInteger()
        assertEquals(
            VaultRestoreOperation.Result.SESSION_REVOKED,
            restore.restore(json(entry("new")), false, key, VaultSession.generation()) {
                checks.incrementAndGet() < 3
            }
        )
        assertEquals(3, checks.get())
        assertArrayEquals(before, ciphertext())
    }

    @Test fun corruptVaultCannotMasqueradeAsEmptyMergeOrBeOverwrittenByReplace() {
        FileHelper.writeBytesToFile(File(context.getFilesDir(), Constants.FILENAME_DATABASE), ByteArray(0))
        val input = json(entry("new"))
        assertEquals(
            VaultRestoreOperation.Result.VAULT_UNAVAILABLE,
            restore.restore(input, false, key, VaultSession.generation(), BooleanSupplier { true })
        )
        assertEquals(
            VaultRestoreOperation.Result.SAVE_FAILED,
            restore.restore(input, true, key, VaultSession.generation(), BooleanSupplier { true })
        )
        assertEquals(0, ciphertext().size)
    }

    @Test fun unexpectedPolicyFailureIsNotMisreportedAsMalformedImport() {
        seed(entry("existing"))
        val before = ciphertext()
        assertEquals(
            VaultRestoreOperation.Result.OPERATION_FAILED,
            restore.restore(json(entry("new")), true, key, VaultSession.generation()) {
                throw IllegalStateException("synthetic")
            }
        )
        assertArrayEquals(before, ciphertext())
    }

    @Test fun persistenceRejectsNonSessionKeysAndFailedSaveCannotStartBackup() {
        val other: SecretKey = SecretKeySpec(ByteArray(16), "AES")
        assertTrue(persistence.load(other) is VaultReadResult.Unavailable)
        assertTrue(persistence.save(arrayListOf(), other, false, null) is VaultWriteResult.Rejected)
        // If automatic-backup snapshot creation were attempted for this invalid write, it would fail.
        assertTrue(
            persistence.save(null, key, true) { fail("Failed write must not export") }
                is VaultWriteResult.Rejected
        )
        VaultSession.lock()
        assertTrue(persistence.load(key) is VaultReadResult.Unavailable)
        assertTrue(persistence.save(arrayListOf(), key, false, null) is VaultWriteResult.Rejected)
    }
}
