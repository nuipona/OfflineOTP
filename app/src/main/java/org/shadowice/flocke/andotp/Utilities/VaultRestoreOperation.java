package org.shadowice.flocke.andotp.Utilities;

import android.content.Context;
import org.shadowice.flocke.andotp.Database.Entry;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.function.BooleanSupplier;
import javax.crypto.SecretKey;

/**
 * Synchronous restore use case. Owns all decoded/loaded entries until it destroys them; no secret
 * payload escapes in the result. The screen owns consent, document access and rendering only.
 */
public final class VaultRestoreOperation {
    public enum Result {
        SAVED, EMPTY_IMPORT, INVALID_IMPORT, VAULT_UNAVAILABLE, SAVE_FAILED,
        SESSION_REVOKED, RECOVERY_REQUIRED, OPERATION_FAILED
    }

    private final EntryPersistence persistence;
    public VaultRestoreOperation(Context context) { persistence = new EntryPersistence(context); }

    private boolean authorized(SecretKey key, long generation, BooleanSupplier permitted) {
        return permitted.getAsBoolean() && VaultSession.isCurrent(generation)
                && key != null && VaultSession.getKey() == key;
    }

    public Result restore(String text, boolean replace, SecretKey key, long generation,
                          BooleanSupplier permitted) {
        ArrayList<Entry> imported = new ArrayList<>();
        ArrayList<Entry> current = new ArrayList<>();
        try {
            if (!authorized(key, generation, permitted)) return Result.SESSION_REVOKED;
            try { imported = DatabaseHelper.stringToEntries(text); }
            catch (RuntimeException invalid) { return Result.INVALID_IMPORT; }
            if (imported.isEmpty()) return Result.EMPTY_IMPORT;
            // The read/merge/write is one mutation, not a stale read followed by a later save.
            synchronized (VaultSession.MutationLock) {
                if (!authorized(key, generation, permitted)) return Result.SESSION_REVOKED;
                if (!replace) {
                    VaultReadResult read = persistence.load(key);
                    if (read instanceof VaultReadResult.Unavailable) {
                        return ((VaultReadResult.Unavailable) read).getError() == VaultReadError.RECOVERY_REQUIRED
                                ? Result.RECOVERY_REQUIRED : Result.VAULT_UNAVAILABLE;
                    }
                    current = ((VaultReadResult.Loaded) read).takeEntries();
                    for (Iterator<Entry> iterator = imported.iterator(); iterator.hasNext();) {
                        Entry entry = iterator.next();
                        if (current.contains(entry)) { iterator.remove(); entry.destroy(); }
                    }
                }
                // Preserve historical ordering: new imports first, then existing entries. Duplicate
                // imports matching existing entries do not overwrite existing metadata/counters.
                ArrayList<Entry> merged = new ArrayList<>(imported);
                merged.addAll(current);
                if (!authorized(key, generation, permitted)) return Result.SESSION_REVOKED;
                VaultWriteResult write = persistence.save(merged, key, false, null);
                if (write instanceof VaultWriteResult.Saved) return Result.SAVED;
                if (write instanceof VaultWriteResult.RecoveryRequired) return Result.RECOVERY_REQUIRED;
                return Result.SAVE_FAILED;
            }
        } catch (RuntimeException error) {
            // An unexpected policy/storage failure is not evidence of malformed import data.
            return Result.OPERATION_FAILED;
        } finally {
            for (Entry entry : imported) entry.destroy();
            for (Entry entry : current) entry.destroy();
        }
    }
}
