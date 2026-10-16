package org.shadowice.flocke.andotp.Activities;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.preference.PreferenceManager;
import android.view.WindowManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.journeyapps.barcodescanner.CaptureActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.shadowice.flocke.andotp.R;
import org.shadowice.flocke.andotp.Utilities.Constants;
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper;
import org.shadowice.flocke.andotp.Utilities.KeyStoreHelper;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Real-platform characterization of the converted small activities: the panic responder and the
 * QR scanner activity.
 *
 * The desktop sandbox executes the decision logic of both against hand-written stand-ins, but it
 * cannot start an activity, cannot apply a real theme and has no camera. Here the panic responder
 * is started for real, so the destructive path (the database wipe, the disabled automatic backups
 * and the cancelled pending backup) is observed on the platform; the scanner activity is checked
 * for its shape, because starting it needs a camera the emulators do not have.
 *
 * Synthetic data only; run on disposable emulators. The panic response is set to the destructive
 * values on purpose, so the vault of the test device is wiped.
 */
@RunWith(AndroidJUnit4.class)
public class KotlinSmallActivitiesInteropTest {
    private Context context;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DatabaseHelper.wipeDatabase(context);
        KeyStoreHelper.wipeKeys(context);
        assertTrue(PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit());
    }

    @After public void cleanup() throws Exception {
        if (context != null) {
            DatabaseHelper.wipeDatabase(context);
            KeyStoreHelper.wipeKeys(context);
            PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        }
    }

    /** The panic response that the converted activity reads. */
    private void storePanicResponse(String... responses) {
        Set<String> values = new HashSet<>(Arrays.asList(responses));
        assertTrue(PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putStringSet(context.getString(R.string.settings_key_panic), values)
                .commit());
    }

    /** The automatic backup password marker the panic response writes. */
    private String automaticBackupPassword() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(context.getString(R.string.settings_key_auto_backup_password_enc), null);
    }

    /** Waits until the condition holds, so the asynchronous activity is not raced. */
    private static void waitUntil(String what, Condition condition) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.holds()) return;
            Thread.sleep(100);
        }
        fail("the activity did not " + what);
    }

    private interface Condition {
        boolean holds();
    }

    @Test public void thePanicActionIsUnchanged() {
        assertEquals("info.guardianproject.panic.action.TRIGGER",
                PanicResponderActivity.PANIC_TRIGGER_ACTION);
    }

    @Test public void aForeignActionWipesNothing() throws Exception {
        storePanicResponse("accounts", "settings");
        File vault = new File(context.getFilesDir(), Constants.FILENAME_DATABASE);
        assertTrue(vault.createNewFile());

        context.startActivity(new Intent(context, PanicResponderActivity.class)
                .setAction("org.example.disposable.SOMETHING_ELSE")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Thread.sleep(2000);

        assertTrue("a foreign action wiped the vault", vault.exists());
        assertNotNull("a foreign action disabled the automatic backups", automaticBackupPassword());
        assertFalse("a foreign action cleared the panic response",
                PreferenceManager.getDefaultSharedPreferences(context)
                        .getStringSet(context.getString(R.string.settings_key_panic), new HashSet<String>())
                        .contains("accounts"));
    }

    @Test public void theAccountsResponseWipesTheVaultAndDisablesTheBackups() throws Exception {
        storePanicResponse("accounts");
        File vault = new File(context.getFilesDir(), Constants.FILENAME_DATABASE);
        assertTrue(vault.createNewFile());

        context.startActivity(new Intent(context, PanicResponderActivity.class)
                .setAction(PanicResponderActivity.PANIC_TRIGGER_ACTION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));

        waitUntil("wipe the vault", () -> !vault.exists());
        assertEquals("the automatic backups were not disabled", "off", automaticBackupPassword());
        assertTrue("the pending encrypted backup was not cancelled",
                androidx.work.WorkManager.getInstance(context)
                        .getWorkInfosForUniqueWork(
                                org.shadowice.flocke.andotp.Receivers.EncryptedBackupBroadcastReceiver.UNIQUE_WORK_NAME)
                        .get(10, java.util.concurrent.TimeUnit.SECONDS)
                        .isEmpty());
    }

    @Test public void theSettingsResponseClearsTheSettings() throws Exception {
        storePanicResponse("settings");

        context.startActivity(new Intent(context, PanicResponderActivity.class)
                .setAction(PanicResponderActivity.PANIC_TRIGGER_ACTION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));

        waitUntil("clear the settings", () -> {
            Set<String> response = PreferenceManager.getDefaultSharedPreferences(context)
                    .getStringSet(context.getString(R.string.settings_key_panic), new HashSet<String>());
            return response == null || !response.contains("settings");
        });
        assertEquals("the automatic backups were not disabled", "off", automaticBackupPassword());
    }

    @Test public void theScannerActivityKeepsItsShape() throws Exception {
        assertEquals(CaptureActivity.class, SecureCaptureActivity.class.getSuperclass());
        assertFalse("the scanner activity became final", Modifier.isFinal(SecureCaptureActivity.class.getModifiers()));

        Method initialize = SecureCaptureActivity.class.getDeclaredMethod("initializeContent");
        assertEquals(SecureCaptureActivity.class, initialize.getDeclaringClass());
        assertTrue("the scanner content is not protected any more", Modifier.isProtected(initialize.getModifiers()));

        // The activity is declared and not exported.
        PackageManager pm = context.getPackageManager();
        assertNotNull(pm.getActivityInfo(
                new android.content.ComponentName(context, SecureCaptureActivity.class), 0));
        assertFalse("the scanner activity is exported",
                pm.getActivityInfo(new android.content.ComponentName(context, SecureCaptureActivity.class), 0).exported);

        // The window flags the converted activity sets are the platform's.
        assertEquals(0x00002000, WindowManager.LayoutParams.FLAG_SECURE);
        assertEquals(0x00000400, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        assertEquals(0x01000000, WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
    }
}
