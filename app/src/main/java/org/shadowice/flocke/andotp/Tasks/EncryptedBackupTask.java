package org.shadowice.flocke.andotp.Tasks;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.shadowice.flocke.andotp.Database.Entry;
import org.shadowice.flocke.andotp.Utilities.BackupHelper;
import org.shadowice.flocke.andotp.Utilities.Constants;
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper;

import java.util.ArrayList;

/** Writes a password-encrypted backup file. */
public class EncryptedBackupTask extends GenericBackupTask {
    public enum Mode { USER, AUTO_EDIT, BROADCAST }
    private final Mode mode;
    private final Uri configuredLocation;
    private final java.util.function.BooleanSupplier permitted;
    private final String password;
    private final ArrayList<Entry> entries;

    public EncryptedBackupTask(Context context, ArrayList<Entry> entries, String password, @Nullable Uri uri) {
        this(context, entries, password, uri, Mode.USER, () -> true);
    }

    public EncryptedBackupTask(Context context, ArrayList<Entry> entries, String password, Uri uri, Mode mode,
                               java.util.function.BooleanSupplier permitted) {
        super(context, uri);
        // Own a validated snapshot; UI mutations/lock cleanup must not alter an export in flight.
        this.entries = DatabaseHelper.stringToEntries(DatabaseHelper.entriesToString(entries));
        this.password = password;
        this.mode = mode;
        this.configuredLocation = settings.isBackupLocationSet() ? settings.getBackupLocation() : null;
        this.permitted = permitted;
    }

    private void destroySnapshot() {
        synchronized (entries) {
            for (Entry entry : entries) entry.destroy();
            entries.clear();
        }
    }

    @Override protected void onCancellation() { destroySnapshot(); }
    @Override protected void discardResult(BackupTaskResult result) { destroySnapshot(); }

    @Override public boolean isCanceled() { return mode == Mode.BROADCAST ? wasCanceled() : super.isCanceled(); }

    @Override protected boolean canWrite() {
        if (isCanceled() || !permitted.getAsBoolean() || password == null || password.isEmpty()) return false;
        if (mode == Mode.USER) return org.shadowice.flocke.andotp.Utilities.VaultSession.getKey() != null;
        if (configuredLocation == null || !settings.isBackupLocationSet()
                || !configuredLocation.equals(settings.getBackupLocation()) || !password.equals(settings.getBackupPasswordEnc())) return false;
        if (mode == Mode.BROADCAST) return settings.isEncryptedBackupBroadcastEnabled()
                && settings.getEncryption() == Constants.EncryptionType.KEYSTORE;
        return org.shadowice.flocke.andotp.Utilities.VaultSession.getKey() != null && settings.getAutoBackupEncryptedPasswordsEnabled();
    }

    @Override @NonNull protected BackupTaskResult doInBackground() {
        try { return super.doInBackground(); }
        finally { destroySnapshot(); }
    }

    @Override
    @NonNull
    protected Constants.BackupType getBackupType() {
        return Constants.BackupType.ENCRYPTED;
    }

    @Override
    protected boolean doBackup() {
        String payload;
        synchronized (entries) { payload = DatabaseHelper.entriesToString(entries); }
        return BackupHelper.backupToFile(applicationContext, uri, password, payload, this::canWrite);
    }
}
