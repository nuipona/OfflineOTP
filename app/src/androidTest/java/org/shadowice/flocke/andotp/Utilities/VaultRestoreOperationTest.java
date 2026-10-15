package org.shadowice.flocke.andotp.Utilities;

import android.content.Context;
import android.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.shadowice.flocke.andotp.Database.Entry;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;
import static org.shadowice.flocke.andotp.Utilities.VaultRestoreOperation.Result.*;

/** Destructive storage fixtures: disposable test installations only. */
@RunWith(AndroidJUnit4.class)
public class VaultRestoreOperationTest {
    private Context context;
    private SecretKey key;
    private EntryPersistence persistence;
    private VaultRestoreOperation restore;
    private final List<Entry> owned = new ArrayList<>();

    @Before public void setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DatabaseHelper.wipeDatabase(context);
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        key = new SecretKeySpec(new byte[16], "AES");
        assertTrue(DatabaseHelper.changeVaultEncryption(context, null, key, true, id ->
                VaultCommitDecision.fromCommit(PreferenceManager.getDefaultSharedPreferences(context)
                        .edit().putString(DatabaseHelper.TRANSACTION_ID, id).commit())) instanceof VaultWriteResult.Saved);
        assertTrue(VaultSession.activate(key, VaultSession.generation()) instanceof SessionCommandResult.Applied);
        persistence = new EntryPersistence(context);
        restore = new VaultRestoreOperation(context);
    }
    @After public void cleanup() {
        for (Entry entry : owned) entry.destroy();
        owned.clear();
        DatabaseHelper.wipeDatabase(context);
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
    }
    private Entry entry(String label, String... tags) {
        Entry entry = new Entry(Entry.OTPType.TOTP, "GEZDGNBVGY3TQOJQ", 30, 6, "synthetic", label,
                TokenCalculator.HashAlgorithm.SHA1, new ArrayList<>(Arrays.asList(tags)));
        owned.add(entry);
        return entry;
    }
    private String json(Entry... entries) { return DatabaseHelper.entriesToString(new ArrayList<>(Arrays.asList(entries))); }
    private void seed(Entry... entries) {
        assertTrue(persistence.save(new ArrayList<>(Arrays.asList(entries)), key, false, null) instanceof VaultWriteResult.Saved);
    }
    private ArrayList<Entry> read() {
        VaultReadResult.Loaded loaded = (VaultReadResult.Loaded) persistence.load(key);
        ArrayList<Entry> entries = loaded.takeEntries();
        loaded.close();
        owned.addAll(entries);
        return entries;
    }
    private byte[] ciphertext() throws Exception {
        return FileHelper.readFileToBytes(new File(context.getFilesDir(), Constants.FILENAME_DATABASE));
    }

    @Test public void mergePlacesNewEntriesFirstAndKeepsExistingDuplicateMetadata() {
        Entry existing = entry("existing", "original");
        existing.setUsedFrequency(42);
        seed(existing);
        Entry duplicate = entry("existing", "imported");
        Entry added = entry("new", "imported");
        assertEquals(SAVED, restore.restore(json(duplicate, added), false, key, VaultSession.generation(), () -> true));
        ArrayList<Entry> result = read();
        assertEquals(2, result.size());
        assertEquals("new", result.get(0).getLabel());
        assertEquals("existing", result.get(1).getLabel());
        assertEquals(Arrays.asList("original"), result.get(1).getTags());
        assertEquals(42L, result.get(1).getUsedFrequency());
        assertTrue(added.getSecret().length > 0); // Operation owns decoded entries, not caller fixtures.
    }

    @Test public void replaceDoesNotMergeExistingEntries() {
        seed(entry("old"));
        assertEquals(SAVED, restore.restore(json(entry("replacement")), true, key, VaultSession.generation(), () -> true));
        ArrayList<Entry> result = read();
        assertEquals(1, result.size());
        assertEquals("replacement", result.get(0).getLabel());
    }

    @Test public void duplicateOnlyMergeIsSuccessfulAndKeepsExistingOrder() {
        seed(entry("first"), entry("second"));
        assertEquals(SAVED, restore.restore(json(entry("second")), false, key, VaultSession.generation(), () -> true));
        ArrayList<Entry> result = read();
        assertEquals(2, result.size());
        assertEquals("first", result.get(0).getLabel());
        assertEquals("second", result.get(1).getLabel());
    }

    @Test public void duplicateImportsRetainHistoricalStorageBehavior() {
        Entry duplicate = entry("same");
        assertEquals(SAVED, restore.restore(json(duplicate, duplicate), true, key, VaultSession.generation(), () -> true));
        assertEquals(2, read().size()); // Do not silently introduce a new import deduplication policy.
    }

    @Test public void emptyOrPartiallyInvalidImportCannotOverwriteExistingVault() throws Exception {
        seed(entry("existing"));
        byte[] before = ciphertext();
        assertEquals(EMPTY_IMPORT, restore.restore("[]", true, key, VaultSession.generation(), () -> true));
        String valid = json(entry("new"));
        String partial = valid.substring(0, valid.length() - 1) + ",{}]";
        assertEquals(INVALID_IMPORT, restore.restore(partial, true, key, VaultSession.generation(), () -> true));
        assertArrayEquals(before, ciphertext());
    }

    @Test public void staleGenerationCannotRestoreEvenIfTheSameKeyObjectWasReactivated() throws Exception {
        seed(entry("existing"));
        byte[] before = ciphertext();
        long stale = VaultSession.generation();
        VaultSession.lock();
        VaultSession.activate(key, VaultSession.generation());
        assertEquals(SESSION_REVOKED, restore.restore(json(entry("new")), true, key, stale, () -> true));
        assertArrayEquals(before, ciphertext());
    }

    @Test public void revocationOfPermissionAfterMergePreventsSave() throws Exception {
        seed(entry("existing"));
        byte[] before = ciphertext();
        AtomicInteger checks = new AtomicInteger();
        assertEquals(SESSION_REVOKED, restore.restore(json(entry("new")), false, key,
                VaultSession.generation(), () -> checks.incrementAndGet() < 3));
        assertEquals(3, checks.get());
        assertArrayEquals(before, ciphertext());
    }

    @Test public void corruptVaultCannotMasqueradeAsEmptyMergeOrBeOverwrittenByReplace() throws Exception {
        FileHelper.writeBytesToFile(new File(context.getFilesDir(), Constants.FILENAME_DATABASE), new byte[0]);
        String input = json(entry("new"));
        assertEquals(VAULT_UNAVAILABLE, restore.restore(input, false, key, VaultSession.generation(), () -> true));
        assertEquals(SAVE_FAILED, restore.restore(input, true, key, VaultSession.generation(), () -> true));
        assertEquals(0, ciphertext().length);
    }

    @Test public void unexpectedPolicyFailureIsNotMisreportedAsMalformedImport() throws Exception {
        seed(entry("existing"));
        byte[] before = ciphertext();
        assertEquals(OPERATION_FAILED, restore.restore(json(entry("new")), true, key,
                VaultSession.generation(), () -> { throw new IllegalStateException("synthetic"); }));
        assertArrayEquals(before, ciphertext());
    }

    @Test public void persistenceRejectsNonSessionKeysAndFailedSaveCannotStartBackup() {
        SecretKey other = new SecretKeySpec(new byte[16], "AES");
        assertTrue(persistence.load(other) instanceof VaultReadResult.Unavailable);
        assertTrue(persistence.save(new ArrayList<>(), other, false, null) instanceof VaultWriteResult.Rejected);
        // If automatic-backup snapshot creation were attempted for this invalid write, it would fail.
        assertTrue(persistence.save(null, key, true, outcome -> fail("Failed write must not export")) instanceof VaultWriteResult.Rejected);
        VaultSession.lock();
        assertTrue(persistence.load(key) instanceof VaultReadResult.Unavailable);
        assertTrue(persistence.save(new ArrayList<>(), key, false, null) instanceof VaultWriteResult.Rejected);
    }
}
