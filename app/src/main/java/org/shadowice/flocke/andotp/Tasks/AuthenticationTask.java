package org.shadowice.flocke.andotp.Tasks;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.shadowice.flocke.andotp.Utilities.Constants.AuthMethod;
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper;
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper.PBKDF2Credentials;
import org.shadowice.flocke.andotp.Utilities.Settings;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;

/** Verifies a password/PIN and returns the database key, upgrading legacy hashes when needed. */
public class AuthenticationTask extends UiBasedBackgroundTask<AuthenticationOutcome> {

    private final Settings settings;

    private final boolean isAuthUpgrade;
    private final String existingAuthCredentials;
    private final String plainPassword;

    @Override protected void discardResult(AuthenticationOutcome result) {
        if (result instanceof AuthenticationOutcome.Verified) ((AuthenticationOutcome.Verified) result).close();
    }

    /**
     * @param context Context used to query settings (only its application context is kept).
     * @param isAuthUpgrade true to upgrade the stored credentials instead of only confirming them.
     * @param existingAuthCredentials The currently stored hashed authentication credentials.
     * @param plainPassword The plaintext password or PIN entered by the user.
     */
    public AuthenticationTask(Context context, boolean isAuthUpgrade, String existingAuthCredentials, String plainPassword) {
        super(new AuthenticationOutcome.Rejected(AuthenticationError.DENIED));
        Context applicationContext = context.getApplicationContext();
        this.settings = new Settings(applicationContext);

        this.isAuthUpgrade = isAuthUpgrade;
        this.existingAuthCredentials = existingAuthCredentials;
        this.plainPassword = plainPassword;
    }

    @Override
    @NonNull
    protected AuthenticationOutcome doInBackground() {
        if (isCanceled() || plainPassword == null || plainPassword.length() > org.shadowice.flocke.andotp.Utilities.InputPolicy.MAX_TEXT_CHARS) return new AuthenticationOutcome.Rejected(AuthenticationError.DENIED);
        if (isAuthUpgrade) {
            return upgradeAuthentication();
        } else {
            return confirmAuthentication();
        }
    }

    /** Upgrades a legacy SHA-256 hash to PBKDF2 credentials and returns the new key. */
    @NonNull
    private AuthenticationOutcome upgradeAuthentication() {
        String hashedPassword = new String(Hex.encodeHex(DigestUtils.sha256(plainPassword)));
        if (!hashedPassword.equals(existingAuthCredentials))
            return new AuthenticationOutcome.Rejected(AuthenticationError.DENIED);

        byte[] key = settings.setAuthCredentials(plainPassword, () -> !isCanceled()
                && settings.getAuthCredentials().isEmpty()
                && existingAuthCredentials.equals(settings.getOldCredentials(settings.getAuthMethod())));

        if (key == null)
            return new AuthenticationOutcome.Rejected(AuthenticationError.LEGACY_MIGRATION_FAILED);
        else {
            try { return AuthenticationOutcome.verified(key, taskGeneration()); }
            finally { Arrays.fill(key, (byte) 0); }
        }
    }

    /** Checks the entered password/PIN against the stored PBKDF2 credentials. */
    @NonNull
    private AuthenticationOutcome confirmAuthentication() {
        PBKDF2Credentials credentials = null;
        byte[] passwordArray = null;
        try {
            credentials = EncryptionHelper.generatePBKDF2Credentials(plainPassword, settings.getSalt(), settings.getIterations());
            passwordArray = Base64.decode(existingAuthCredentials, Base64.URL_SAFE);
            if (java.security.MessageDigest.isEqual(passwordArray, credentials.password)) return AuthenticationOutcome.verified(credentials.key, taskGeneration());
            return new AuthenticationOutcome.Rejected(AuthenticationError.DENIED);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException | IllegalStateException e) {
            Log.e("AuthenticationTask", "Problem decoding password");
            return new AuthenticationOutcome.Rejected(AuthenticationError.INVALID_CONFIGURATION);
        } finally {
            if (credentials != null) {
                Arrays.fill(credentials.password, (byte) 0);
                Arrays.fill(credentials.key, (byte) 0);
            }
            if (passwordArray != null) Arrays.fill(passwordArray, (byte) 0);
        }
    }

}
