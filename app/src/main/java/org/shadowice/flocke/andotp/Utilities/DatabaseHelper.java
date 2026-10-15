package org.shadowice.flocke.andotp.Utilities;

import android.content.Context;
import android.preference.PreferenceManager;

import org.json.JSONArray;
import org.shadowice.flocke.andotp.Database.Entry;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.BadPaddingException;
import javax.crypto.SecretKey;

/** Authenticated, bounded vault I/O. Failures must never masquerade as empty data. */
public final class DatabaseHelper {
    public static final Object DatabaseFileLock = VaultSession.MutationLock;
    static final String TRANSACTION_ID = "vault_transaction_id";
    static final String PENDING_FILE = "secrets.pending";
    // A failed preference commit leaves process memory ambiguous. Require restart.
    private static boolean transactionPoisoned;

    public enum Status { SUCCESS, MISSING, MISSING_KEY, AUTHENTICATION_FAILED, CORRUPT, IO_FAILURE }

    /** Legacy Java adapter. Entry ownership is now one-use, not a repeatable shallow copy. */
    public static final class LoadResult implements AutoCloseable {
        public final Status status;
        private final VaultReadResult result;
        private LoadResult(VaultReadResult result) {
            this.result = result;
            VaultReadError error = result instanceof VaultReadResult.Unavailable
                    ? ((VaultReadResult.Unavailable) result).getError() : null;
            this.status = error == null ? Status.SUCCESS : error == VaultReadError.RECOVERY_REQUIRED
                    ? Status.IO_FAILURE : Status.valueOf(error.name());
        }
        public boolean isSuccess() { return result instanceof VaultReadResult.Loaded; }
        public ArrayList<Entry> requireEntries() {
            if (!isSuccess()) throw new VaultException(status);
            return ((VaultReadResult.Loaded) result).takeEntries();
        }
        @Override public void close() {
            if (result instanceof VaultReadResult.Loaded) ((VaultReadResult.Loaded) result).close();
        }
    }

    public static final class VaultException extends IllegalStateException {
        public final Status status;
        VaultException(Status status) { super("Vault unavailable: " + status); this.status = status; }
    }

    private DatabaseHelper() { }
    private static File file(Context context, String name) { return new File(context.getFilesDir(), name); }
    private static VaultReadResult unavailable(VaultReadError error) { return new VaultReadResult.Unavailable(error); }
    private static VaultWriteResult rejected(VaultWriteError error) { return new VaultWriteResult.Rejected(error); }
    private static VaultWriteResult recoveryRequired(VaultCommitState state) {
        VaultSession.fail(SessionFailure.RECOVERY_REQUIRED);
        return new VaultWriteResult.RecoveryRequired(state);
    }
    private static VaultWriteResult readFailure(VaultReadResult.Unavailable result) {
        if (result.getError() == VaultReadError.RECOVERY_REQUIRED) return recoveryRequired(VaultCommitState.UNKNOWN);
        return rejected(VaultWriteError.valueOf(result.getError().name()));
    }

    /** Used before key selection at startup. Journal contains ciphertext and a UUID, never raw keys. */
    public static boolean recoverPendingDatabase(Context context) {
        synchronized (DatabaseFileLock) {
            if (transactionPoisoned) return false;
            File pending = file(context, PENDING_FILE);
            if (!FileHelper.exists(pending)) return true;
            try {
                byte[] staged = FileHelper.readFileToBytes(pending);
                if (staged.length < 36 + 28) return false;
                String id = new String(staged, 0, 36, StandardCharsets.US_ASCII);
                String committed = PreferenceManager.getDefaultSharedPreferences(context).getString(TRANSACTION_ID, "");
                if (id.equals(committed)) {
                    // Preferences committed first. Finish promotion after a crash/write failure.
                    FileHelper.writeBytesToFile(file(context, Constants.FILENAME_DATABASE), Arrays.copyOfRange(staged, 36, staged.length));
                }
                // An uncommitted staged write is not authoritative; keep the old vault.
                FileHelper.delete(pending);
                return true;
            } catch (IOException | RuntimeException error) { return false; }
        }
    }

    public static VaultReadResult readVault(Context context, SecretKey key) {
        synchronized (DatabaseFileLock) {
            if (!recoverPendingDatabase(context)) return unavailable(VaultReadError.RECOVERY_REQUIRED);
            File database = file(context, Constants.FILENAME_DATABASE);
            if (!FileHelper.exists(database)) return unavailable(VaultReadError.MISSING);
            if (key == null) return unavailable(VaultReadError.MISSING_KEY);
            byte[] plain = null;
            try {
                plain = EncryptionHelper.decrypt(key, FileHelper.readFileToBytes(database));
                return new VaultReadResult.Loaded(stringToEntries(new String(plain, StandardCharsets.UTF_8)));
            } catch (BadPaddingException error) { return unavailable(VaultReadError.AUTHENTICATION_FAILED);
            } catch (IOException error) { return unavailable(VaultReadError.IO_FAILURE);
            } catch (Exception error) { return unavailable(VaultReadError.CORRUPT);
            } finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
        }
    }

    public static LoadResult loadDatabaseResult(Context context, SecretKey key) {
        return new LoadResult(readVault(context, key));
    }
    public static ArrayList<Entry> loadDatabase(Context context, SecretKey key) {
        return loadDatabaseResult(context, key).requireEntries();
    }

    public static VaultWriteResult saveVault(Context context, ArrayList<Entry> entries, SecretKey key) {
        synchronized (DatabaseFileLock) {
            VaultReadResult read = readVault(context, key);
            if (read instanceof VaultReadResult.Unavailable) return readFailure((VaultReadResult.Unavailable) read);
            try (VaultReadResult.Loaded loaded = (VaultReadResult.Loaded) read) {
                return writeEntries(context, entries, key);
            }
        }
    }
    /** Legacy boolean adapter: only fully acknowledged writes count as success. */
    public static boolean saveDatabase(Context context, ArrayList<Entry> entries, SecretKey key) {
        return saveVault(context, entries, key) instanceof VaultWriteResult.Saved;
    }

    private static byte[] encryptedEntries(ArrayList<Entry> entries, SecretKey key) throws Exception {
        if (key == null) throw new IllegalArgumentException("Missing vault key");
        byte[] plain = entriesToString(entries).getBytes(StandardCharsets.UTF_8);
        try {
            // Leave room for nonce/tag plus the 36-byte staged transaction UUID.
            if (plain.length > BoundedInputReader.MAX_DOCUMENT_BYTES - 64) throw new IllegalArgumentException("Vault too large");
            return EncryptionHelper.encrypt(key, plain);
        } finally { Arrays.fill(plain, (byte) 0); }
    }

    private static VaultWriteResult writeEntries(Context context, ArrayList<Entry> entries, SecretKey key) {
        try {
            FileHelper.writeBytesToFile(file(context, Constants.FILENAME_DATABASE), encryptedEntries(entries, key));
            return VaultWriteResult.Saved.INSTANCE;
        } catch (IllegalArgumentException | VaultException error) { return rejected(VaultWriteError.INVALID_INPUT);
        } catch (Exception error) { return rejected(VaultWriteError.IO_FAILURE); }
    }

    /** Legacy metadata callback: false means UNKNOWN, not guaranteed rollback. */
    public interface SettingsCommit { boolean commit(String transactionId); }
    public interface VaultSettingsCommit { VaultCommitDecision commit(String transactionId); }

    public static boolean changeEncryption(Context context, SecretKey oldKey, SecretKey newKey,
                                            boolean initialize, SettingsCommit commit) {
        return changeVaultEncryption(context, oldKey, newKey, initialize, id -> {
            try { return VaultCommitDecision.fromCommit(commit.commit(id)); }
            catch (java.util.concurrent.CancellationException canceled) { return VaultCommitDecision.CANCELED; }
        }) instanceof VaultWriteResult.Saved;
    }

    /** Ciphertext -> explicit metadata decision/UUID -> promotion; ambiguous commits quarantine writes. */
    public static VaultWriteResult changeVaultEncryption(Context context, SecretKey oldKey, SecretKey newKey,
                                                         boolean initialize, VaultSettingsCommit commit) {
        synchronized (DatabaseFileLock) {
            if (!recoverPendingDatabase(context)) return recoveryRequired(VaultCommitState.UNKNOWN);
            if (newKey == null) return rejected(VaultWriteError.MISSING_KEY);
            ArrayList<Entry> entries;
            if (initialize) {
                if (FileHelper.exists(file(context, Constants.FILENAME_DATABASE))
                        || FileHelper.exists(file(context, Constants.FILENAME_DATABASE_BACKUP))) return rejected(VaultWriteError.ALREADY_EXISTS);
                entries = new ArrayList<>();
            } else {
                VaultReadResult read = readVault(context, oldKey);
                if (read instanceof VaultReadResult.Unavailable) return readFailure((VaultReadResult.Unavailable) read);
                entries = ((VaultReadResult.Loaded) read).takeEntries();
                if (!backupDatabase(context)) {
                    for (Entry entry : entries) entry.destroy();
                    return rejected(VaultWriteError.BACKUP_FAILED);
                }
            }
            try {
                byte[] encrypted = encryptedEntries(entries, newKey);
                String id = UUID.randomUUID().toString();
                byte[] staged = new byte[36 + encrypted.length];
                System.arraycopy(id.getBytes(StandardCharsets.US_ASCII), 0, staged, 0, 36);
                System.arraycopy(encrypted, 0, staged, 36, encrypted.length);
                FileHelper.writeBytesToFile(file(context, PENDING_FILE), staged);
                VaultCommitDecision decision;
                try { decision = commit.commit(id); }
                catch (Exception unknown) { decision = VaultCommitDecision.UNKNOWN; }
                String marker;
                try { marker = PreferenceManager.getDefaultSharedPreferences(context).getString(TRANSACTION_ID, ""); }
                catch (RuntimeException unknown) {
                    transactionPoisoned = true;
                    return recoveryRequired(VaultCommitState.UNKNOWN);
                }
                if (decision == VaultCommitDecision.CANCELED && !id.equals(marker)) {
                    // Contract: CANCELED must be returned before any metadata mutation.
                    return rejected(VaultWriteError.CANCELED);
                }
                if (decision != VaultCommitDecision.COMMITTED || !id.equals(marker)) {
                    transactionPoisoned = true;
                    return recoveryRequired(VaultCommitState.UNKNOWN);
                }
                return recoverPendingDatabase(context) ? VaultWriteResult.Saved.INSTANCE
                        : recoveryRequired(VaultCommitState.COMMITTED);
            } catch (IllegalArgumentException | VaultException error) { return rejected(VaultWriteError.INVALID_INPUT);
            } catch (Exception error) { return rejected(VaultWriteError.IO_FAILURE);
            } finally { for (Entry entry : entries) entry.destroy(); }
        }
    }

    public static boolean canInitialize(Context context) {
        synchronized (DatabaseFileLock) {
            return recoverPendingDatabase(context) && !FileHelper.exists(file(context, Constants.FILENAME_DATABASE))
                    && !FileHelper.exists(file(context, Constants.FILENAME_DATABASE_BACKUP));
        }
    }

    public static boolean backupDatabase(Context context) {
        synchronized (DatabaseFileLock) {
            try {
                FileHelper.writeBytesToFile(file(context, Constants.FILENAME_DATABASE_BACKUP),
                        FileHelper.readFileToBytes(file(context, Constants.FILENAME_DATABASE)));
                return true;
            } catch (IOException | RuntimeException error) { return false; }
        }
    }

    public static boolean restoreDatabaseBackup(Context context) {
        synchronized (DatabaseFileLock) {
            if (!recoverPendingDatabase(context)) return false;
            try {
                FileHelper.writeBytesToFile(file(context, Constants.FILENAME_DATABASE),
                        FileHelper.readFileToBytes(file(context, Constants.FILENAME_DATABASE_BACKUP)));
                return true;
            } catch (IOException | RuntimeException error) { return false; }
        }
    }

    /** Explicit destructive operation, serialized with migration and backup operations. */
    public static void wipeDatabase(Context context) {
        VaultSession.lock(SessionLockReason.RESET);
        synchronized (DatabaseFileLock) {
            for (String name : new String[] {Constants.FILENAME_DATABASE, Constants.FILENAME_DATABASE_BACKUP, PENDING_FILE}) {
                FileHelper.delete(file(context, name));
            }
            transactionPoisoned = false;
        }
    }

    public static String entriesToString(ArrayList<Entry> entries) {
        if (entries == null || entries.size() > InputPolicy.MAX_ENTRIES) throw new IllegalArgumentException("Too many entries");
        JSONArray json = new JSONArray();
        try {
            for (Entry entry : entries) { entry.validate(); json.put(entry.toJSON()); }
            return json.toString();
        } catch (Exception error) { throw new VaultException(Status.CORRUPT); }
    }

    /** All-or-nothing parser. Never return a prefix of a malformed backup. */
    public static ArrayList<Entry> stringToEntries(String data) {
        ArrayList<Entry> entries = new ArrayList<>();
        try {
            if (data == null || data.length() > BoundedInputReader.MAX_DOCUMENT_BYTES) throw new IllegalArgumentException();
            InputPolicy.jsonDepth(data);
            org.json.JSONTokener tokener = new org.json.JSONTokener(data);
            Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONArray) || tokener.nextClean() != 0) throw new IllegalArgumentException("Invalid JSON document");
            JSONArray json = (JSONArray) parsed;
            if (json.length() > InputPolicy.MAX_ENTRIES) throw new IllegalArgumentException();
            for (int i = 0; i < json.length(); i++) entries.add(new Entry(json.getJSONObject(i)));
            return entries;
        } catch (Exception error) {
            for (Entry entry : entries) entry.destroy();
            throw new VaultException(Status.CORRUPT);
        }
    }
}
