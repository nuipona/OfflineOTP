package org.shadowice.flocke.andotp.Utilities;

import android.content.Context;
import org.shadowice.flocke.andotp.Database.Entry;
import org.shadowice.flocke.andotp.Tasks.BackupTaskResult;
import org.shadowice.flocke.andotp.Tasks.EncryptedBackupTask;
import org.shadowice.flocke.andotp.Tasks.UiBasedBackgroundTask;

import java.util.ArrayList;
import javax.crypto.SecretKey;

/**
 * Session-authorized entry I/O and automatic-backup scheduling. Owns no screen, entry collection or
 * key. Read ownership transfers to the caller; saves borrow entries only during this call.
 */
public final class EntryPersistence {
    private final Context context;
    private final Settings settings;

    public EntryPersistence(Context context) {
        this.context = context.getApplicationContext();
        this.settings = new Settings(this.context);
    }

    private boolean authorized(SecretKey key) { return key != null && VaultSession.getKey() == key; }

    public VaultReadResult load(SecretKey key) {
        synchronized (VaultSession.MutationLock) {
            if (!authorized(key)) return new VaultReadResult.Unavailable(VaultReadError.MISSING_KEY);
            return DatabaseHelper.readVault(context, key);
        }
    }

    public VaultWriteResult save(ArrayList<Entry> entries, SecretKey key, boolean autoBackup,
                                UiBasedBackgroundTask.OutcomeCallback<BackupTaskResult> backupCallback) {
        synchronized (VaultSession.MutationLock) {
            if (!authorized(key)) return new VaultWriteResult.Rejected(VaultWriteError.MISSING_KEY);
            VaultWriteResult result = DatabaseHelper.saveVault(context, entries, key);
            if (!(result instanceof VaultWriteResult.Saved)) return result;
            if (autoBackup && BackupHelper.autoBackupType(context) == Constants.BackupType.ENCRYPTED) {
                // Task constructor owns a separate validated snapshot; a failed save never exports.
                EncryptedBackupTask task = new EncryptedBackupTask(context, entries,
                        settings.getBackupPasswordEnc(), null, EncryptedBackupTask.Mode.AUTO_EDIT, () -> true);
                task.setOutcomeCallback(backupCallback);
                task.execute();
            }
            return result;
        }
    }
}
