package org.shadowice.flocke.andotp.Tasks;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.shadowice.flocke.andotp.R;
import org.shadowice.flocke.andotp.Database.Entry;
import org.shadowice.flocke.andotp.Utilities.*;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

/** Uses synthetic ciphertext/credentials and destroys the target's disposable test vault. */
@RunWith(AndroidJUnit4.class)
public class CredentialLifecycleSecurityTest {
    private Context context;
    private Settings settings;
    private SharedPreferences preferences;
    private SecretKey oldKey;

    @Before public void setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DatabaseHelper.wipeDatabase(context);
        KeyStoreHelper.wipeKeys(context);
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        assertTrue(preferences.edit().clear().commit());
        settings = new Settings(context);
        oldKey = new SecretKeySpec(new byte[16], "AES");
        assertTrue(DatabaseHelper.changeEncryption(context, null, oldKey, true,
                id -> settings.commitVaultSettings(id, Constants.EncryptionType.PASSWORD, Constants.AuthMethod.PASSWORD, null, true)));
        assertTrue(preferences.edit().putString(context.getString(R.string.settings_key_auth_credentials), "synthetic-prior-verifier").commit());
    }

    @After public void cleanup() {
        DatabaseHelper.wipeDatabase(context);
        KeyStoreHelper.wipeKeys(context);
        preferences.edit().clear().commit();
    }

    private File vault() { return new File(context.getFilesDir(), Constants.FILENAME_DATABASE); }

    @Test public void failedLoadCannotCommitNewCredentialsOrReplaceCiphertext() throws Exception {
        byte[] wrongBytes = new byte[16]; wrongBytes[0] = 1;
        SecretKey wrong = new SecretKeySpec(wrongBytes, "AES");
        VaultSession.open(wrong);
        Map<String, ?> before = new HashMap<>(preferences.getAll());
        byte[] ciphertext = Files.readAllBytes(vault().toPath());
        ChangeCredentialsTask.Result result = new ChangeCredentialsTask(context, Constants.EncryptionType.PASSWORD, wrong, Constants.AuthMethod.PASSWORD, "synthetic-new-password").doInBackground();
        assertFalse(result.success);
        assertNull(result.newEncryptionKey);
        assertEquals(before, preferences.getAll());
        assertArrayEquals(ciphertext, Files.readAllBytes(vault().toPath()));
    }

    @Test public void successfulChangeCommitsMatchingCiphertextVerifierAndSalt() throws Exception {
        VaultSession.open(oldKey);
        String password = "synthetic-new-password";
        ChangeCredentialsTask.Result result = new ChangeCredentialsTask(context, Constants.EncryptionType.PASSWORD, oldKey, Constants.AuthMethod.PASSWORD, password).doInBackground();
        assertTrue(result.success);
        assertNotNull(result.newEncryptionKey);
        assertEquals(DatabaseHelper.Status.AUTHENTICATION_FAILED, DatabaseHelper.loadDatabaseResult(context, oldKey).status);
        DatabaseHelper.LoadResult loaded = DatabaseHelper.loadDatabaseResult(context, result.newEncryptionKey);
        assertTrue(loaded.isSuccess());
        for (Entry entry : loaded.requireEntries()) entry.destroy();
        EncryptionHelper.PBKDF2Credentials derived = EncryptionHelper.generatePBKDF2Credentials(password, settings.getSalt(), settings.getIterations());
        try {
            assertArrayEquals(result.newEncryptionKey.getEncoded(), derived.key);
            assertArrayEquals(Base64.decode(settings.getAuthCredentials(), Base64.URL_SAFE), derived.password);
        } finally { Arrays.fill(derived.key, (byte) 0); Arrays.fill(derived.password, (byte) 0); }
        assertEquals(Constants.AuthMethod.PASSWORD, settings.getAuthMethod());
    }

    @Test public void canceledEncryptionChangeCannotCreateReplacementKey() throws Exception {
        VaultSession.open(oldKey);
        Map<String, ?> before = new HashMap<>(preferences.getAll());
        byte[] ciphertext = Files.readAllBytes(vault().toPath());
        ChangeEncryptionTask task = new ChangeEncryptionTask(context, oldKey, Constants.EncryptionType.KEYSTORE, null);
        task.cancel();
        ChangeEncryptionTask.Result result = task.doInBackground();
        assertEquals(EncryptionHelper.EncryptionChangeResult.CHANGE_FAILURE, result.result);
        assertNull(result.newEncryptionKey);
        assertFalse(new File(context.getFilesDir(), Constants.FILENAME_ENCRYPTED_KEY).exists());
        assertEquals(before, preferences.getAll());
        assertArrayEquals(ciphertext, Files.readAllBytes(vault().toPath()));
    }
}
