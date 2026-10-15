package org.shadowice.flocke.andotp.Tasks;

import android.content.Context;

import androidx.annotation.NonNull;

import org.shadowice.flocke.andotp.Utilities.Constants;
import org.shadowice.flocke.andotp.Utilities.Settings;

/** Persists the choices made in the intro screen and derives the initial encryption key. */
public class FinishIntroTask extends UiBasedBackgroundTask<FinishIntroTask.Result> {
    private final Settings settings;
    private final Context settingsContext;

    private final Constants.EncryptionType encryptionType;
    private final Constants.AuthMethod authMethod;
    private final String password;
    private final boolean androidSyncEnabled;

    public FinishIntroTask(Context context, Constants.EncryptionType encryptionType, Constants.AuthMethod authMethod, String password, boolean androidSyncEnabled) {
        super(new Result(false, null));

        Context applicationContext = context.getApplicationContext();
        this.settings = new Settings(applicationContext);
        this.settingsContext = applicationContext;

        this.encryptionType = encryptionType;
        this.authMethod = authMethod;
        this.password = password;
        this.androidSyncEnabled = androidSyncEnabled;
    }

    @NonNull
    @Override
    protected Result doInBackground() {
        Settings.PreparedCredentials prepared = null;
        try {
            if (isCanceled() || !org.shadowice.flocke.andotp.Utilities.DatabaseHelper.canInitialize(settingsContext)) return new Result(false, null, taskGeneration());
            if (authMethod == Constants.AuthMethod.PASSWORD || authMethod == Constants.AuthMethod.PIN) prepared = settings.prepareAuthCredentials(password);
            if (encryptionType == Constants.EncryptionType.PASSWORD && prepared == null) return new Result(false, null, taskGeneration());
            javax.crypto.SecretKey key = encryptionType == Constants.EncryptionType.PASSWORD
                    ? org.shadowice.flocke.andotp.Utilities.EncryptionHelper.generateSymmetricKey(prepared.key)
                    : org.shadowice.flocke.andotp.Utilities.KeyStoreHelper.createEncryptionKey(settingsContext);
            if (key == null) return new Result(false, null, taskGeneration());
            Settings.PreparedCredentials credentials = prepared;
            org.shadowice.flocke.andotp.Utilities.VaultWriteResult write = org.shadowice.flocke.andotp.Utilities.DatabaseHelper.changeVaultEncryption(settingsContext,
                    null, key, true, id -> {
                        if (isCanceled()) return org.shadowice.flocke.andotp.Utilities.VaultCommitDecision.CANCELED;
                        return org.shadowice.flocke.andotp.Utilities.VaultCommitDecision.fromCommit(settings.commitVaultSettings(id, encryptionType, authMethod, credentials, true));
                    });
            boolean success = write instanceof org.shadowice.flocke.andotp.Utilities.VaultWriteResult.Saved;
            return new Result(success, success ? key.getEncoded() : null, taskGeneration());
        } catch (Exception error) { return new Result(false, null, taskGeneration()); }
        finally { if (prepared != null) prepared.destroy(); }
    }

    @Override protected void discardResult(Result result) {
        if (result.encryptionKey != null) java.util.Arrays.fill(result.encryptionKey, (byte) 0);
    }

    /** Whether the initial setup was saved, plus the new encryption key. */
    public static class Result {
        public final boolean saveSuccessful;
        public final byte[] encryptionKey;
        public final long generation;

        public Result(boolean saveSuccessful, byte[] encryptionKey) {
            this(saveSuccessful, encryptionKey, org.shadowice.flocke.andotp.Utilities.VaultSession.generation());
        }

        public Result(boolean saveSuccessful, byte[] encryptionKey, long generation) {
            this.generation = generation;
            this.saveSuccessful = saveSuccessful;
            this.encryptionKey = encryptionKey;
        }
    }
}