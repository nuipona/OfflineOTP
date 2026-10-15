package org.shadowice.flocke.andotp.Tasks;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;

import org.shadowice.flocke.andotp.Utilities.StorageAccessHelper;

/** Reads an OpenPGP-encrypted backup and hands it to the OpenPGP app for decryption. */
public class PGPRestoreTask extends GenericRestoreTask {
    private final Intent decryptIntent;

    public PGPRestoreTask(Context context, Uri uri, Intent decryptIntent) {
        super(context, uri);
        this.decryptIntent = decryptIntent;
    }

    @Override
    @NonNull
    protected BackupTaskResult doInBackground() {
        try {
            String data = new String(StorageAccessHelper.loadFile(applicationContext, uri), java.nio.charset.StandardCharsets.UTF_8);
            return new BackupTaskResult(BackupTaskResult.ResultType.RESTORE, true, data, 0, true, decryptIntent, uri);
        } catch (java.io.IOException | RuntimeException error) {
            return BackupTaskResult.failure(BackupTaskResult.ResultType.RESTORE, org.shadowice.flocke.andotp.R.string.backup_toast_import_failed);
        }
    }
}
