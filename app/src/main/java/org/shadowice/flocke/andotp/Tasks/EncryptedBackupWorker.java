package org.shadowice.flocke.andotp.Tasks;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.shadowice.flocke.andotp.Database.Entry;
import org.shadowice.flocke.andotp.R;
import org.shadowice.flocke.andotp.Utilities.Constants;
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper;
import org.shadowice.flocke.andotp.Utilities.KeyStoreHelper;
import org.shadowice.flocke.andotp.Utilities.NotificationHelper;
import org.shadowice.flocke.andotp.Utilities.Settings;

import java.util.ArrayList;

import javax.crypto.SecretKey;

/** Runs the password-encrypted backup triggered by the ENCRYPTED_BACKUP broadcast. */
public class EncryptedBackupWorker extends Worker {
    private static final String TAG = EncryptedBackupWorker.class.getSimpleName();

    public EncryptedBackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        Settings settings = new Settings(context);

        // Settings may have changed since the broadcast was received, so check again.
        // Never write a backup without a password.
        String password = settings.getBackupPasswordEnc();
        if (password == null || password.isEmpty()) {
            return fail(context, R.string.backup_receiver_no_password_set);
        }
        if (!settings.isBackupLocationSet()) {
            return fail(context, R.string.backup_receiver_no_location_set);
        }
        if (settings.getEncryption() != Constants.EncryptionType.KEYSTORE) {
            return fail(context, R.string.backup_receiver_custom_encryption_failed);
        }

        try {
            SecretKey encryptionKey = KeyStoreHelper.loadEncryptionKeyFromKeyStore(context, false);
            ArrayList<Entry> entries = DatabaseHelper.loadDatabase(context, encryptionKey);

            BackupTaskResult result = new EncryptedBackupTask(context, entries, password, null).doInBackground();

            if (result.success) {
                NotificationHelper.notify(context, Constants.NotificationChannel.BACKUP_SUCCESS, R.string.backup_receiver_title_backup_success, result.payload);
                return Result.success();
            }
            return fail(context, result.messageId);
        } catch (RuntimeException e) {
            Log.e(TAG, "Encrypted backup failed", e);
            return fail(context, R.string.backup_receiver_unexpected_error);
        }
    }

    /** Shows the failure notification. No retry: a failed backup would most likely fail again. */
    private Result fail(Context context, int messageId) {
        NotificationHelper.notify(context, Constants.NotificationChannel.BACKUP_FAILED, R.string.backup_receiver_title_backup_failed, messageId);
        return Result.failure();
    }
}
