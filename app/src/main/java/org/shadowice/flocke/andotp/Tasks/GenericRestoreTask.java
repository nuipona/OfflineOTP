package org.shadowice.flocke.andotp.Tasks;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.shadowice.flocke.andotp.R;
import org.shadowice.flocke.andotp.Utilities.Settings;

/** Base for restore tasks, which read the given document in {@link #doInBackground()}. */
public abstract class GenericRestoreTask extends UiBasedBackgroundTask<BackupTaskResult> {
    protected final Context applicationContext;
    protected final Settings settings;
    protected Uri uri;

    public GenericRestoreTask(Context context, @Nullable Uri uri) {
        super(BackupTaskResult.failure(BackupTaskResult.ResultType.RESTORE, R.string.backup_toast_import_failed));

        this.applicationContext = context.getApplicationContext();
        this.settings = new Settings(applicationContext);

        this.uri = uri;
    }

    /** Reads and parses the selected backup file (implemented by the concrete task). */
    @Override
    @NonNull
    protected abstract BackupTaskResult doInBackground();
}
