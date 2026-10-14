package org.shadowice.flocke.andotp.Tasks;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.shadowice.flocke.andotp.R;
import org.shadowice.flocke.andotp.Utilities.BackupHelper;
import org.shadowice.flocke.andotp.Utilities.Constants;
import org.shadowice.flocke.andotp.Utilities.Settings;
import org.shadowice.flocke.andotp.Utilities.StorageAccessHelper;

/** Base for backup tasks: resolves the target file, then delegates to {@link #doBackup()}. */
public abstract class GenericBackupTask extends UiBasedBackgroundTask<BackupTaskResult> {
    protected final Context applicationContext;
    protected final Settings settings;
    protected final Constants.BackupType type;
    // Target file; resolved in doInBackground() when null
    protected Uri uri;

    public GenericBackupTask(Context context, @Nullable Uri uri) {
        super(BackupTaskResult.failure(BackupTaskResult.ResultType.BACKUP, R.string.backup_toast_export_failed));

        this.applicationContext = context.getApplicationContext();
        this.settings = new Settings(applicationContext);

        this.type = getBackupType();
        this.uri = uri;
    }

    /** Picks or creates the target file and then runs {@link #doBackup()}. */
    @Override
    @NonNull
    protected BackupTaskResult doInBackground() {
        String fileName;

        if (uri == null) {
            BackupHelper.BackupFile backupFile = BackupHelper.backupFile(applicationContext, settings.getBackupLocation(), type);

            if (backupFile.file == null)
                return new BackupTaskResult(BackupTaskResult.ResultType.BACKUP,false, null, backupFile.errorMessage);

            uri = backupFile.file.getUri();
            fileName = backupFile.file.getName();
        } else {
            fileName = StorageAccessHelper.getContentFileName(applicationContext, uri);
        }

        boolean success = doBackup();

        if (success)
            return BackupTaskResult.success(BackupTaskResult.ResultType.BACKUP ,fileName);
        else
            return BackupTaskResult.failure(BackupTaskResult.ResultType.BACKUP, R.string.backup_toast_export_failed);
    }

    /** The backup type this task writes (used for the file name and MIME type). */
    @NonNull
    protected abstract Constants.BackupType getBackupType();
    /** Writes the backup to {@link #uri} and reports whether it succeeded. */
    protected abstract boolean doBackup();
}