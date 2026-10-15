package org.shadowice.flocke.andotp.Tasks;

import android.content.Context;

import androidx.annotation.NonNull;

import org.shadowice.flocke.andotp.Utilities.Constants;
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper;
import org.shadowice.flocke.andotp.Utilities.Settings;

import javax.crypto.SecretKey;

/** Changes the authentication credentials and re-encrypts the database if required. */
public class ChangeCredentialsTask extends UiBasedBackgroundTask<ChangeCredentialsTask.Result> {

    private final Context context;
    private final Settings settings;
    private final Constants.EncryptionType encryptionType;
    private final Constants.AuthMethod newAuthMethod;
    private final SecretKey oldEncryptionKey;
    private final String password;

    public ChangeCredentialsTask(Context context, Constants.EncryptionType encryptionType, SecretKey oldKey, Constants.AuthMethod newAuth, String password) {
        super(new Result(false, null, null));

        this.context = context.getApplicationContext();
        this.settings = new Settings(this.context);
        this.encryptionType = encryptionType;
        this.newAuthMethod = newAuth;
        this.oldEncryptionKey = oldKey;
        this.password = password;
    }

    /** Stores the new credentials and re-encrypts the database when password encryption is used. */
    @NonNull
    @Override
    protected Result doInBackground() {
        Settings.PreparedCredentials prepared = null;
        try {
            if (isCanceled() || oldEncryptionKey == null || org.shadowice.flocke.andotp.Utilities.VaultSession.getKey() != oldEncryptionKey) return new Result(false, null, null);
            if (newAuthMethod == Constants.AuthMethod.PASSWORD || newAuthMethod == Constants.AuthMethod.PIN) prepared = settings.prepareAuthCredentials(password);
            if (encryptionType == Constants.EncryptionType.PASSWORD && prepared == null) return new Result(false, null, null);
            SecretKey nextKey = encryptionType == Constants.EncryptionType.PASSWORD
                    ? EncryptionHelper.generateSymmetricKey(prepared.key) : oldEncryptionKey;
            Settings.PreparedCredentials credentials = prepared;
            long epoch = org.shadowice.flocke.andotp.Utilities.VaultSession.generation();
            synchronized (org.shadowice.flocke.andotp.Utilities.DatabaseHelper.DatabaseFileLock) {
                if (isCanceled() || org.shadowice.flocke.andotp.Utilities.VaultSession.getKey() != oldEncryptionKey) return new Result(false, null, null);
                org.shadowice.flocke.andotp.Utilities.VaultWriteResult write = org.shadowice.flocke.andotp.Utilities.DatabaseHelper.changeVaultEncryption(context,
                        oldEncryptionKey, nextKey, false, id -> {
                            if (isCanceled() || !org.shadowice.flocke.andotp.Utilities.VaultSession.isCurrent(epoch)) return org.shadowice.flocke.andotp.Utilities.VaultCommitDecision.CANCELED;
                            return org.shadowice.flocke.andotp.Utilities.VaultCommitDecision.fromCommit(settings.commitVaultSettings(id, encryptionType, newAuthMethod, credentials, false));
                        });
                boolean success = write instanceof org.shadowice.flocke.andotp.Utilities.VaultWriteResult.Saved;
                return new Result(success, success ? nextKey : null, encryptionType);
            }
        } catch (Exception error) { return new Result(false, null, null); }
        finally { if (prepared != null) prepared.destroy(); }
    }

    /** Outcome of the change, including the new key and encryption type. */
    public static class Result {
        public final boolean success;
        public final SecretKey newEncryptionKey;
        public final Constants.EncryptionType encryptionType;

        public Result(boolean success, SecretKey newKey, Constants.EncryptionType encryptionType) {
            this.success = success;
            this.newEncryptionKey = newKey;
            this.encryptionType = encryptionType;
        }
    }
}
