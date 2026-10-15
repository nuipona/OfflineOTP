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
        androidx.documentfile.provider.DocumentFile created = null;
        boolean success = false;
        try {
            if (!canWrite()) return BackupTaskResult.failure(BackupTaskResult.ResultType.BACKUP, R.string.backup_toast_export_failed);
            String fileName;
            if (uri == null) {
                BackupHelper.BackupFile backup = BackupHelper.backupFile(applicationContext, settings.getBackupLocation(), type);
                if (backup.file == null) return BackupTaskResult.failure(BackupTaskResult.ResultType.BACKUP, backup.errorMessage);
                created = backup.file;
                uri = created.getUri();
                fileName = StorageAccessHelper.getContentFileName(applicationContext, uri);
            } else fileName = StorageAccessHelper.getContentFileName(applicationContext, uri);
            success = canWrite() && doBackup() && canWrite();
            return success ? BackupTaskResult.success(BackupTaskResult.ResultType.BACKUP, fileName)
                    : BackupTaskResult.failure(BackupTaskResult.ResultType.BACKUP, R.string.backup_toast_export_failed);
        } catch (RuntimeException error) {
            return BackupTaskResult.failure(BackupTaskResult.ResultType.BACKUP, R.string.backup_toast_export_failed);
        } finally {
            if (!success && created != null) {
                try { created.delete(); } catch (RuntimeException ignored) { }
            }
        }
    }

    protected boolean canWrite() {
        return !isCanceled() && org.shadowice.flocke.andotp.Utilities.VaultSession.getKey() != null;
    }

    /** The backup type this task writes (used for the file name and MIME type). */
    @NonNull
    protected abstract Constants.BackupType getBackupType();
    /** Writes the backup to {@link #uri} and reports whether it succeeded. */
    protected abstract boolean doBackup();
}