package org.shadowice.flocke.andotp.Utilities;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.WorkManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.shadowice.flocke.andotp.R;
import org.shadowice.flocke.andotp.Receivers.EncryptedBackupBroadcastReceiver;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import static org.junit.Assert.*;

/** Destructive disposable-install test. Install :security-test-client:installDebug first. */
@RunWith(AndroidJUnit4.class)
public class IpcAuthorizationSecurityTest {
    private static final String CLIENT = "org.shadowice.flocke.andotp.securityclient";
    private Context context;
    private WorkManager work;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Test client must only attack the debug application", "org.shadowice.flocke.andotp.dev", context.getPackageName());
        work = WorkManager.getInstance(context);
        work.cancelAllWork().getResult().get(10, TimeUnit.SECONDS);
        work.pruneWork().getResult().get(10, TimeUnit.SECONDS);
        DatabaseHelper.wipeDatabase(context);
        KeyStoreHelper.wipeKeys(context);
        assertTrue(PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit());
    }

    @After public void cleanup() throws Exception {
        if (work != null) work.cancelAllWork().getResult().get(10, TimeUnit.SECONDS);
        if (context != null) {
            DatabaseHelper.wipeDatabase(context);
            KeyStoreHelper.wipeKeys(context);
            PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        }
    }

    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
             ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            return new String(BoundedInputReader.read(input, 1024 * 1024), StandardCharsets.UTF_8);
        }
    }

    @Test public void separatelySignedAppCannotWipeOrQueueBackupEvenWhenEnabled() throws Exception {
        PackageManager pm = context.getPackageManager();
        // Missing/invisible client is a test setup failure, never interpreted as denial.
        int clientUid = pm.getApplicationInfo(CLIENT, 0).uid;
        assertNotEquals(context.getApplicationInfo().uid, clientUid);
        assertEquals(PackageManager.SIGNATURE_NO_MATCH, pm.checkSignatures(context.getPackageName(), CLIENT));
        String permission = context.getPackageName() + ".permission.CONTROL_VAULT";
        assertEquals(PermissionInfo.PROTECTION_SIGNATURE, pm.getPermissionInfo(permission, 0).protectionLevel & PermissionInfo.PROTECTION_MASK_BASE);
        assertEquals(PackageManager.PERMISSION_DENIED, pm.checkPermission(permission, CLIENT));
        assertEquals(PackageManager.PERMISSION_GRANTED, pm.checkPermission(permission, context.getPackageName()));

        Settings settings = new Settings(context);
        SecretKey key = KeyStoreHelper.createEncryptionKey(context);
        assertNotNull(key);
        assertTrue(DatabaseHelper.changeEncryption(context, null, key, true,
                id -> settings.commitVaultSettings(id, Constants.EncryptionType.KEYSTORE, Constants.AuthMethod.NONE, null, true)));
        KeyPair backupPair = KeyStoreHelper.loadOrGenerateAsymmetricKeyPair(context, Constants.KEYSTORE_ALIAS_PASSWORD);
        String encryptedPassword = Base64.encodeToString(EncryptionHelper.encrypt(backupPair.getPublic(), "synthetic-password".getBytes(StandardCharsets.UTF_8)), Base64.URL_SAFE);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        assertTrue(preferences.edit()
                .putStringSet(context.getString(R.string.settings_key_panic), new HashSet<>(Arrays.asList("accounts", "settings")))
                .putStringSet(context.getString(R.string.settings_key_backup_broadcasts), new HashSet<>(Arrays.asList("encrypted")))
                .putString(context.getString(R.string.settings_key_backup_password_enc), encryptedPassword)
                .commit());
        // Syntactically valid provider URI: receiver checks configuration, not provider access.
        settings.setBackupLocation(Uri.parse("content://org.example.disposable/tree/root"));
        assertTrue(settings.isEncryptedBackupBroadcastEnabled());
        assertEquals("synthetic-password", settings.getBackupPasswordEnc());
        Map<String, ?> beforeSettings = new HashMap<>(preferences.getAll());
        File vault = new File(context.getFilesDir(), Constants.FILENAME_DATABASE);
        byte[] beforeVault = FileHelper.readFileToBytes(vault);
        assertTrue(work.getWorkInfosForUniqueWork(EncryptedBackupBroadcastReceiver.UNIQUE_WORK_NAME).get(10, TimeUnit.SECONDS).isEmpty());

        shell("logcat -c");
        shell("am start -W -n " + CLIENT + "/.AttackActivity --es target " + context.getPackageName());
        // Allow asynchronous broadcast delivery/work scheduling; the caller's own UID does IPC.
        Thread.sleep(1500);
        String logs = shell("logcat -d -s andOTP-SecurityClient:I '*:S'");
        assertTrue(logs, logs.contains("PANIC_DENIED"));
        assertFalse(logs, logs.contains("PANIC_ALLOWED"));
        assertTrue(logs, logs.contains("BROADCAST_ATTEMPTED"));
        assertEquals(beforeSettings, preferences.getAll());
        assertArrayEquals(beforeVault, FileHelper.readFileToBytes(vault));
        assertTrue("Unauthorized broadcast queued work", work.getWorkInfosForUniqueWork(EncryptedBackupBroadcastReceiver.UNIQUE_WORK_NAME).get(10, TimeUnit.SECONDS).isEmpty());
    }
}
