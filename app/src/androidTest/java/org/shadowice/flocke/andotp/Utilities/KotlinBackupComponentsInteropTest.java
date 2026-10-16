package org.shadowice.flocke.andotp.Utilities;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.shadowice.flocke.andotp.R;
import org.shadowice.flocke.andotp.Receivers.EncryptedBackupBroadcastReceiver;
import org.shadowice.flocke.andotp.Tasks.EncryptedBackupWorker;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Real-platform characterization of the converted backup components: the encrypted backup
 * broadcast receiver and the WorkManager worker it queues.
 *
 * The desktop sandbox can only drive the action gate and the worker's early exits: the failure
 * paths post a notification through the notification channels and the happy path needs the
 * AndroidKeyStore, so the queued work and the worker's terminal state are checked here.
 *
 * Synthetic data only; run on disposable emulators. The backup location points at a provider
 * that does not exist, so the worker is expected to finish the work with a failure instead of
 * writing a backup.
 */
@RunWith(AndroidJUnit4.class)
public class KotlinBackupComponentsInteropTest {
    private static final String PASSWORD = "synthetic-password";
    private static final String FAKE_LOCATION = "content://org.example.disposable/tree/root";

    private Context context;
    private WorkManager work;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
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

    /** Stores the encrypted backup password the receiver and the worker require. */
    private void storeEncryptedPassword() throws Exception {
        KeyPair pair = KeyStoreHelper.loadOrGenerateAsymmetricKeyPair(context, Constants.KEYSTORE_ALIAS_PASSWORD);
        assertNotNull(pair);
        byte[] encrypted = EncryptionHelper.encrypt(pair.getPublic(), PASSWORD.getBytes(StandardCharsets.UTF_8));
        try {
            assertTrue(PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putString(context.getString(R.string.settings_key_backup_password_enc),
                            Base64.encodeToString(encrypted, Base64.URL_SAFE))
                    .commit());
        } finally {
            java.util.Arrays.fill(encrypted, (byte) 0);
        }
    }

    private void enableBroadcast() {
        assertTrue(PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putStringSet(context.getString(R.string.settings_key_backup_broadcasts),
                        new HashSet<>(Arrays.asList("encrypted")))
                .commit());
    }

    private List<WorkInfo> queuedWork() throws Exception {
        return work.getWorkInfosForUniqueWork(EncryptedBackupBroadcastReceiver.UNIQUE_WORK_NAME)
                .get(10, TimeUnit.SECONDS);
    }

    @Test public void aCompleteConfigurationQueuesTheEncryptedBackup() throws Exception {
        storeEncryptedPassword();
        enableBroadcast();
        Settings settings = new Settings(context);
        settings.setBackupLocation(Uri.parse(FAKE_LOCATION));
        assertTrue(settings.isEncryptedBackupBroadcastEnabled());
        assertEquals(PASSWORD, settings.getBackupPasswordEnc());

        Intent intent = new Intent(EncryptedBackupBroadcastReceiver.ACTION);
        new EncryptedBackupBroadcastReceiver().onReceive(context, intent);

        assertFalse("the broadcast did not queue the backup", queuedWork().isEmpty());
    }

    @Test public void aDisabledBroadcastQueuesNothing() throws Exception {
        storeEncryptedPassword();
        Settings settings = new Settings(context);
        settings.setBackupLocation(Uri.parse(FAKE_LOCATION));
        assertFalse(settings.isEncryptedBackupBroadcastEnabled());

        Intent intent = new Intent(EncryptedBackupBroadcastReceiver.ACTION);
        new EncryptedBackupBroadcastReceiver().onReceive(context, intent);

        assertTrue("the disabled broadcast queued the backup", queuedWork().isEmpty());
    }

    @Test public void anotherActionQueuesNothing() throws Exception {
        storeEncryptedPassword();
        enableBroadcast();
        Settings settings = new Settings(context);
        settings.setBackupLocation(Uri.parse(FAKE_LOCATION));

        Intent intent = new Intent("org.shadowice.flocke.andotp.broadcast.SOMETHING_ELSE");
        new EncryptedBackupBroadcastReceiver().onReceive(context, intent);

        assertTrue("an unrelated broadcast queued the backup", queuedWork().isEmpty());
    }

    @Test public void theQueuedWorkRunsTheWorkerAndFinishes() throws Exception {
        storeEncryptedPassword();
        enableBroadcast();
        Settings settings = new Settings(context);
        settings.setBackupLocation(Uri.parse(FAKE_LOCATION));

        Intent intent = new Intent(EncryptedBackupBroadcastReceiver.ACTION);
        new EncryptedBackupBroadcastReceiver().onReceive(context, intent);
        assertFalse(queuedWork().isEmpty());

        // The worker runs for real: the vault is empty, so it must finish the work with a failure
        // instead of leaving the work running or crashing the process.
        WorkInfo info = null;
        for (int attempt = 0; attempt < 30 && info == null; attempt++) {
            List<WorkInfo> infos = queuedWork();
            if (!infos.isEmpty() && isFinished(infos.get(0).getState())) {
                info = infos.get(0);
            } else {
                Thread.sleep(1000);
            }
        }
        assertNotNull("the queued work did not finish", info);
        assertTrue("the work did not reach a terminal state: " + info.getState(),
                info.getState() == WorkInfo.State.SUCCEEDED || info.getState() == WorkInfo.State.FAILED);
        assertEquals(EncryptedBackupWorker.class.getName(),
                work.getWorkInfosForUniqueWork(EncryptedBackupBroadcastReceiver.UNIQUE_WORK_NAME)
                        .get(10, TimeUnit.SECONDS).get(0).getWorkerClassName());
    }

    /** Whether the work reached a terminal state. */
    private static boolean isFinished(WorkInfo.State state) {
        return state == WorkInfo.State.SUCCEEDED || state == WorkInfo.State.FAILED
                || state == WorkInfo.State.CANCELLED;
    }
}
