/*
 * Copyright (C) 2018-2020 Jakob Nixdorf
 * Copyright (C) 2018-2020 Richy HBM
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.shadowice.flocke.andotp.Receivers;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.shadowice.flocke.andotp.Database.Entry;
import org.shadowice.flocke.andotp.R;
import org.shadowice.flocke.andotp.Tasks.BackupTaskResult;
import org.shadowice.flocke.andotp.Tasks.EncryptedBackupTask;
import org.shadowice.flocke.andotp.Utilities.Constants;
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper;
import org.shadowice.flocke.andotp.Utilities.KeyStoreHelper;
import org.shadowice.flocke.andotp.Utilities.NotificationHelper;
import org.shadowice.flocke.andotp.Utilities.Settings;

import java.util.ArrayList;

import javax.crypto.SecretKey;

// Test in the dev build (Android 8+ needs an explicit package, hence -p):
//   adb shell am broadcast -a org.shadowice.flocke.andotp.broadcast.ENCRYPTED_BACKUP -p org.shadowice.flocke.andotp.dev
/** Creates a password-encrypted backup when the corresponding broadcast is received. */
public class EncryptedBackupBroadcastReceiver extends BackupBroadcastReceiver {
    private Context context;

    @Override
    public void onReceive(Context context, Intent intent) {
        this.context = context;
        Settings settings = new Settings(context);

        if (!settings.isEncryptedBackupBroadcastEnabled()) {
            NotificationHelper.notify(context, Constants.NotificationChannel.BACKUP_FAILED, R.string.backup_receiver_title_backup_failed, R.string.backup_receiver_encrypted_disabled);
            return;
        }

        if (!canSaveBackup(context)) {
            NotificationHelper.notify(context, Constants.NotificationChannel.BACKUP_FAILED, R.string.backup_receiver_title_backup_failed, R.string.backup_receiver_no_location_set);
            return;
        }

        String password = settings.getBackupPasswordEnc();

        if (password.isEmpty()) {
            NotificationHelper.notify(context, Constants.NotificationChannel.BACKUP_FAILED, R.string.backup_receiver_title_backup_failed, R.string.backup_receiver_no_password_set);
            return;
        }

        if (settings.getEncryption() != Constants.EncryptionType.KEYSTORE) {
            NotificationHelper.notify(context, Constants.NotificationChannel.BACKUP_FAILED, R.string.backup_receiver_title_backup_failed, R.string.backup_receiver_custom_encryption_failed);
            return;
        }

        // Reading the key and database can take a while, so keep it off the main thread.
        // goAsync() keeps the broadcast alive until finish() is called once the backup is done.
        final PendingResult pendingResult = goAsync();
        final Context appContext = context.getApplicationContext();

        new Thread(() -> {
            try {
                SecretKey encryptionKey = KeyStoreHelper.loadEncryptionKeyFromKeyStore(appContext, false);
                ArrayList<Entry> entries = DatabaseHelper.loadDatabase(appContext, encryptionKey);

                EncryptedBackupTask task = new EncryptedBackupTask(appContext, entries, password, null);
                task.setCallback(result -> {
                    handleTaskResult(result);
                    pendingResult.finish();
                });
                task.execute();
            } catch (RuntimeException e) {
                Log.e(EncryptedBackupBroadcastReceiver.class.getSimpleName(), "Encrypted backup failed to start", e);
                NotificationHelper.notify(appContext, Constants.NotificationChannel.BACKUP_FAILED, R.string.backup_receiver_title_backup_failed, R.string.backup_receiver_unexpected_error);
                pendingResult.finish();
            }
        }).start();
    }

    /** Shows the success or failure notification for the finished backup. */
    private void handleTaskResult(BackupTaskResult result) {
        if (result.success) {
            NotificationHelper.notify(context, Constants.NotificationChannel.BACKUP_SUCCESS, R.string.backup_receiver_title_backup_success, result.payload);
        } else {
            NotificationHelper.notify(context, Constants.NotificationChannel.BACKUP_FAILED, R.string.backup_receiver_title_backup_failed, result.messageId);
        }
    }
}
