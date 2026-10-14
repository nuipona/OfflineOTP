package org.shadowice.flocke.andotp.Tasks;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.shadowice.flocke.andotp.Tasks.AuthenticationTask.Result;
import org.shadowice.flocke.andotp.Utilities.Constants.AuthMethod;
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper;
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper.PBKDF2Credentials;
import org.shadowice.flocke.andotp.Utilities.Settings;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;

/** Verifies a password/PIN and returns the database key, upgrading legacy hashes when needed. */
public class AuthenticationTask extends UiBasedBackgroundTask<Result> {

    private final Settings settings;

    private final boolean isAuthUpgrade;
    private final String existingAuthCredentials;
    private final String plainPassword;

    /**
     * @param context Context used to query settings (only its application context is kept).
     * @param isAuthUpgrade true to upgrade the stored credentials instead of only confirming them.
     * @param existingAuthCredentials The currently stored hashed authentication credentials.
     * @param plainPassword The plaintext password or PIN entered by the user.
     */
    public AuthenticationTask(Context context, boolean isAuthUpgrade, String existingAuthCredentials, String plainPassword) {
        super(Result.failure());
        Context applicationContext = context.getApplicationContext();
        this.settings = new Settings(applicationContext);

        this.isAuthUpgrade = isAuthUpgrade;
        this.existingAuthCredentials = existingAuthCredentials;
        this.plainPassword = plainPassword;
    }

    @Override
    @NonNull
    protected Result doInBackground() {
        if (isAuthUpgrade) {
            return upgradeAuthentication();
        } else {
            return confirmAuthentication();
        }
    }

    /** Upgrades a legacy SHA-256 hash to PBKDF2 credentials and returns the new key. */
    @NonNull
    private Result upgradeAuthentication() {
        String hashedPassword = new String(Hex.encodeHex(DigestUtils.sha256(plainPassword)));
        if (!hashedPassword.equals(existingAuthCredentials))
            return Result.failure();

        byte[] key = settings.setAuthCredentials(plainPassword);

        AuthMethod authMethod = settings.getAuthMethod();
        if (authMethod == AuthMethod.PASSWORD)
            settings.removeAuthPasswordHash();
        else if (authMethod == AuthMethod.PIN)
            settings.removeAuthPINHash();

        if (key == null)
            return Result.upgradeFailure();
        else
            return Result.success(key);
    }

    /** Checks the entered password/PIN against the stored PBKDF2 credentials. */
    @NonNull
    private Result confirmAuthentication() {
        try {
            PBKDF2Credentials credentials = EncryptionHelper.generatePBKDF2Credentials(plainPassword, settings.getSalt(), settings.getIterations());
            byte[] passwordArray = Base64.decode(existingAuthCredentials, Base64.URL_SAFE);

            if (Arrays.equals(passwordArray, credentials.password)) {
                return Result.success(credentials.key);
            }
            return Result.failure();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e) {
            Log.e("AuthenticationTask", "Problem decoding password", e);
            return Result.failure();
        }
    }

    /** The derived encryption key (null on failure) plus the auth-upgrade failure flag. */
    public static class Result {
        @Nullable
        public final byte[] encryptionKey;
        public final boolean authUpgradeFailed;

        public Result(@Nullable byte[] encryptionKey, boolean authUpgradeFailed) {
            this.encryptionKey = encryptionKey;
            this.authUpgradeFailed = authUpgradeFailed;
        }

        /** The password/PIN was verified; carries the derived key. */
        public static Result success(byte[] encryptionKey) {
            return new Result(encryptionKey, false);
        }

        /** The legacy hash matched but re-hashing it failed. */
        public static Result upgradeFailure() {
            return new Result(null, true);
        }

        /** The password/PIN was wrong (or the algorithm is unsupported). */
        public static Result failure() {
            return new Result(null, false);
        }
    }
}
