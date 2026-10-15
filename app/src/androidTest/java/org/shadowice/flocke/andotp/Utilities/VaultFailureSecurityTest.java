package org.shadowice.flocke.andotp.Utilities;

import android.content.Context;
import android.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

/** Destructive fixture setup: disposable test installations only. */
@RunWith(AndroidJUnit4.class)
public class VaultFailureSecurityTest {
    private Context context;
    private SecretKey key;
    @Before public void setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DatabaseHelper.wipeDatabase(context);
        KeyStoreHelper.wipeKeys(context);
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        key = new SecretKeySpec(new byte[16], "AES");
    }
    @After public void cleanup() {
        DatabaseHelper.wipeDatabase(context);
        KeyStoreHelper.wipeKeys(context);
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
    }
    private File file(String name) { return new File(context.getFilesDir(), name); }
    private void initialize() {
        assertTrue(DatabaseHelper.changeEncryption(context, null, key, true,
                id -> PreferenceManager.getDefaultSharedPreferences(context).edit().putString(DatabaseHelper.TRANSACTION_ID, id).commit()));
    }

    @Test public void missingIsNotEmptyAndNormalSaveCannotInitialize() {
        assertEquals(DatabaseHelper.Status.MISSING, DatabaseHelper.loadDatabaseResult(context, key).status);
        assertThrows(DatabaseHelper.VaultException.class, () -> DatabaseHelper.loadDatabase(context, key));
        assertFalse(DatabaseHelper.saveDatabase(context, new ArrayList<>(), key));
        initialize();
        assertTrue(DatabaseHelper.loadDatabase(context, key).isEmpty());
    }

    @Test public void failedAuthenticationCannotOverwriteVaultOrMigrate() throws Exception {
        initialize();
        byte[] original = FileHelper.readFileToBytes(file(Constants.FILENAME_DATABASE));
        byte[] wrongBytes = new byte[16]; wrongBytes[0] = 1;
        SecretKey wrong = new SecretKeySpec(wrongBytes, "AES");
        assertEquals(DatabaseHelper.Status.AUTHENTICATION_FAILED, DatabaseHelper.loadDatabaseResult(context, wrong).status);
        assertFalse(DatabaseHelper.saveDatabase(context, new ArrayList<>(), wrong));
        assertFalse(DatabaseHelper.changeEncryption(context, wrong, key, false, id -> { fail("Must not commit after failed load"); return true; }));
        assertArrayEquals(original, FileHelper.readFileToBytes(file(Constants.FILENAME_DATABASE)));
    }

    @Test public void corruptAndPartialJsonAreRejectedAllOrNothing() {
        String entry = "{\"secret\":\"GEZDGNBVGY3TQOJQ\",\"label\":\"synthetic\"}";
        assertEquals(1, DatabaseHelper.stringToEntries("[" + entry + "]").size());
        assertThrows(DatabaseHelper.VaultException.class, () -> DatabaseHelper.stringToEntries("[" + entry + ",{}]"));
        assertThrows(DatabaseHelper.VaultException.class, () -> DatabaseHelper.stringToEntries("[" + entry + ","));
        assertThrows(DatabaseHelper.VaultException.class, () -> DatabaseHelper.stringToEntries("[" + entry + "] []"));
        assertThrows(DatabaseHelper.VaultException.class, () -> DatabaseHelper.stringToEntries("[{'secret':'GEZDGNBVGY3TQOJQ','label':'synthetic'}]"));
        assertThrows(DatabaseHelper.VaultException.class, () -> DatabaseHelper.stringToEntries("[{\"secret\":\"A!\",\"label\":\"synthetic\"}]"));
    }

    @Test public void missingKeyLookupDoesNotInventReplacementForExistingVault() {
        initialize();
        assertNull(KeyStoreHelper.loadEncryptionKeyFromKeyStore(context, true));
        assertFalse(file(Constants.FILENAME_ENCRYPTED_KEY).exists());
        assertEquals(DatabaseHelper.Status.MISSING_KEY, DatabaseHelper.loadDatabaseResult(context, null).status);
    }

    @Test public void crashBeforePreferenceCommitKeepsOriginalCiphertext() throws Exception {
        initialize();
        byte[] original = FileHelper.readFileToBytes(file(Constants.FILENAME_DATABASE));
        byte[] stage = new byte[36 + 28];
        System.arraycopy("00000000-0000-0000-0000-000000000000".getBytes(StandardCharsets.US_ASCII), 0, stage, 0, 36);
        FileHelper.writeBytesToFile(file(DatabaseHelper.PENDING_FILE), stage);
        assertTrue(DatabaseHelper.recoverPendingDatabase(context));
        assertArrayEquals(original, FileHelper.readFileToBytes(file(Constants.FILENAME_DATABASE)));
        assertFalse(file(DatabaseHelper.PENDING_FILE).exists());
    }

    @Test public void crashAfterPreferenceCommitPromotesStagedCiphertext() throws Exception {
        initialize();
        String id = "11111111-1111-1111-1111-111111111111";
        byte[] encrypted = EncryptionHelper.encrypt(key, "[]".getBytes(StandardCharsets.UTF_8));
        byte[] stage = new byte[36 + encrypted.length];
        System.arraycopy(id.getBytes(StandardCharsets.US_ASCII), 0, stage, 0, 36);
        System.arraycopy(encrypted, 0, stage, 36, encrypted.length);
        FileHelper.writeBytesToFile(file(DatabaseHelper.PENDING_FILE), stage);
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(DatabaseHelper.TRANSACTION_ID, id).commit();
        assertTrue(DatabaseHelper.recoverPendingDatabase(context));
        assertArrayEquals(encrypted, FileHelper.readFileToBytes(file(Constants.FILENAME_DATABASE)));
        assertTrue(DatabaseHelper.loadDatabase(context, key).isEmpty());
    }

    @Test public void failedPreferenceCommitBlocksWritesUntilRestartOrExplicitWipe() {
        initialize();
        assertFalse(DatabaseHelper.changeEncryption(context, key, key, false, id -> false));
        assertEquals(DatabaseHelper.Status.IO_FAILURE, DatabaseHelper.loadDatabaseResult(context, key).status);
        assertFalse(DatabaseHelper.saveDatabase(context, new ArrayList<>(), key));
    }
    @Test public void typedReadDistinguishesMissingFromOwnedEmptyVault() {
        assertEquals(VaultReadError.MISSING, ((VaultReadResult.Unavailable) DatabaseHelper.readVault(context, key)).getError());
        initialize();
        VaultReadResult.Loaded result = (VaultReadResult.Loaded) DatabaseHelper.readVault(context, key);
        assertEquals("VaultReadResult.Loaded(redacted)", result.toString());
        assertTrue(result.takeEntries().isEmpty());
        assertThrows(IllegalStateException.class, result::takeEntries);
        result.close();
    }

    @Test public void loadedCloseDestroysOnlyUntransferredEntries() throws Exception {
        org.shadowice.flocke.andotp.Database.Entry first = new org.shadowice.flocke.andotp.Database.Entry("otpauth://totp/synthetic?secret=GEZDGNBVGY3TQOJQ");
        VaultReadResult.Loaded closed = new VaultReadResult.Loaded(java.util.Collections.singletonList(first));
        closed.close();
        closed.close();
        assertNull(first.getSecret());
        assertThrows(IllegalStateException.class, closed::takeEntries);
        org.shadowice.flocke.andotp.Database.Entry second = new org.shadowice.flocke.andotp.Database.Entry("otpauth://totp/synthetic?secret=GEZDGNBVGY3TQOJQ");
        VaultReadResult.Loaded transferred = new VaultReadResult.Loaded(java.util.Collections.singletonList(second));
        ArrayList<org.shadowice.flocke.andotp.Database.Entry> owned = transferred.takeEntries();
        transferred.close();
        assertTrue(second.getSecret().length > 0);
        for (org.shadowice.flocke.andotp.Database.Entry entry : owned) entry.destroy();
    }

    @Test public void typedPreCommitCancellationPreservesAuthoritativeVault() throws Exception {
        initialize();
        VaultSession.open(key);
        byte[] original = FileHelper.readFileToBytes(file(Constants.FILENAME_DATABASE));
        VaultWriteResult result = DatabaseHelper.changeVaultEncryption(context, key, key, false, id -> VaultCommitDecision.CANCELED);
        assertEquals(VaultWriteError.CANCELED, ((VaultWriteResult.Rejected) result).getError());
        assertSame(key, VaultSession.getKey());
        assertTrue(DatabaseHelper.recoverPendingDatabase(context));
        assertArrayEquals(original, FileHelper.readFileToBytes(file(Constants.FILENAME_DATABASE)));
    }

    @Test public void typedUnknownMetadataCommitRevokesAndQuarantinesSession() {
        initialize();
        VaultSession.open(key);
        VaultWriteResult result = DatabaseHelper.changeVaultEncryption(context, key, key, false, id -> VaultCommitDecision.UNKNOWN);
        assertEquals(VaultCommitState.UNKNOWN, ((VaultWriteResult.RecoveryRequired) result).getCommitState());
        assertTrue(VaultSession.snapshot() instanceof SessionState.Failed);
        assertNull(VaultSession.getKey());
        assertEquals(VaultReadError.RECOVERY_REQUIRED, ((VaultReadResult.Unavailable) DatabaseHelper.readVault(context, key)).getError());
        assertTrue(DatabaseHelper.saveVault(context, new ArrayList<>(), key) instanceof VaultWriteResult.RecoveryRequired);
    }

    @Test public void typedCommittedPromotionFailureIsNotReportedAsRollback() throws Exception {
        initialize();
        VaultSession.open(key);
        VaultWriteResult result = DatabaseHelper.changeVaultEncryption(context, key, key, false, id -> {
            boolean committed = PreferenceManager.getDefaultSharedPreferences(context).edit().putString(DatabaseHelper.TRANSACTION_ID, id).commit();
            assertTrue(committed);
            // Force promotion failure without modifying the staged ciphertext or committed UUID.
            assertTrue(file(Constants.FILENAME_DATABASE).delete());
            assertTrue(file(Constants.FILENAME_DATABASE).mkdir());
            try { assertTrue(new File(file(Constants.FILENAME_DATABASE), "block-promotion").createNewFile()); }
            catch (java.io.IOException error) { throw new IllegalStateException("Fixture setup failed"); }
            return VaultCommitDecision.COMMITTED;
        });
        try {
            assertEquals(VaultCommitState.COMMITTED, ((VaultWriteResult.RecoveryRequired) result).getCommitState());
            assertNull(VaultSession.getKey());
            assertTrue(file(DatabaseHelper.PENDING_FILE).exists());
        } finally {
            assertTrue(new File(file(Constants.FILENAME_DATABASE), "block-promotion").delete());
            assertTrue(file(Constants.FILENAME_DATABASE).delete());
        }
        assertTrue(DatabaseHelper.recoverPendingDatabase(context));
        VaultReadResult recovered = DatabaseHelper.readVault(context, key);
        assertTrue(recovered instanceof VaultReadResult.Loaded);
        ((VaultReadResult.Loaded) recovered).close();
        assertNull(VaultSession.getKey()); // Recovery of storage never authorizes a session.
    }

}
