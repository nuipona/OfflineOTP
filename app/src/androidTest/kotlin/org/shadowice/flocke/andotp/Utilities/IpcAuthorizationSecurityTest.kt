package org.shadowice.flocke.andotp.Utilities

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.preference.PreferenceManager
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Receivers.EncryptedBackupBroadcastReceiver
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.util.HashMap
import java.util.concurrent.TimeUnit

/** Destructive disposable-install test. Install :security-test-client:installDebug first. */
@RunWith(AndroidJUnit4::class)
class IpcAuthorizationSecurityTest {
    private lateinit var context: Context
    private var work: WorkManager? = null

    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext()
        assertEquals(
            "Test client must only attack the debug application",
            "org.shadowice.flocke.andotp.dev",
            context.getPackageName()
        )
        work = WorkManager.getInstance(context)
        work!!.cancelAllWork().getResult().get(10, TimeUnit.SECONDS)
        work!!.pruneWork().getResult().get(10, TimeUnit.SECONDS)
        DatabaseHelper.wipeDatabase(context)
        KeyStoreHelper.wipeKeys(context)
        assertTrue(PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit())
    }

    @After fun cleanup() {
        work?.cancelAllWork()?.getResult()?.get(10, TimeUnit.SECONDS)
        if (::context.isInitialized) {
            DatabaseHelper.wipeDatabase(context)
            KeyStoreHelper.wipeKeys(context)
            PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command)
        val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        return input.use { String(BoundedInputReader.read(it, 1024 * 1024), StandardCharsets.UTF_8) }
    }

    @Test fun separatelySignedAppCannotWipeOrQueueBackupEvenWhenEnabled() {
        val pm = context.getPackageManager()
        // Missing/invisible client is a test setup failure, never interpreted as denial.
        val clientUid = pm.getApplicationInfo(CLIENT, 0).uid
        assertNotEquals(context.getApplicationInfo().uid, clientUid)
        assertEquals(PackageManager.SIGNATURE_NO_MATCH, pm.checkSignatures(context.getPackageName(), CLIENT))
        val permission = context.getPackageName() + ".permission.CONTROL_VAULT"
        assertEquals(
            PermissionInfo.PROTECTION_SIGNATURE,
            pm.getPermissionInfo(permission, 0).protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        )
        assertEquals(PackageManager.PERMISSION_DENIED, pm.checkPermission(permission, CLIENT))
        assertEquals(PackageManager.PERMISSION_GRANTED, pm.checkPermission(permission, context.getPackageName()))

        val settings = Settings(context)
        val key = KeyStoreHelper.createEncryptionKey(context) ?: throw AssertionError("Missing test encryption key")
        assertTrue(
            DatabaseHelper.changeEncryption(context, null, key, true) { id ->
                settings.commitVaultSettings(
                    id,
                    Constants.EncryptionType.KEYSTORE,
                    Constants.AuthMethod.NONE,
                    null,
                    true
                )
            }
        )
        val backupPair: KeyPair = KeyStoreHelper.loadOrGenerateAsymmetricKeyPair(
            context,
            Constants.KEYSTORE_ALIAS_PASSWORD
        ) ?: throw AssertionError("Missing test backup key pair")
        val encryptedPassword = Base64.encodeToString(
            EncryptionHelper.encrypt(
                backupPair.public,
                "synthetic-password".toByteArray(StandardCharsets.UTF_8)
            ),
            Base64.URL_SAFE
        )
        val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        assertTrue(
            preferences.edit()
                .putStringSet(context.getString(R.string.settings_key_panic), hashSetOf("accounts", "settings"))
                .putStringSet(context.getString(R.string.settings_key_backup_broadcasts), hashSetOf("encrypted"))
                .putString(context.getString(R.string.settings_key_backup_password_enc), encryptedPassword)
                .commit()
        )
        // Syntactically valid provider URI: receiver checks configuration, not provider access.
        settings.setBackupLocation(Uri.parse("content://org.example.disposable/tree/root"))
        assertTrue(settings.isEncryptedBackupBroadcastEnabled())
        assertEquals("synthetic-password", settings.getBackupPasswordEnc())
        val beforeSettings = HashMap<String, Any?>(preferences.getAll())
        val vault = File(context.getFilesDir(), Constants.FILENAME_DATABASE)
        val beforeVault = FileHelper.readFileToBytes(vault)
        val uniqueWork = EncryptedBackupBroadcastReceiver.UNIQUE_WORK_NAME
        assertTrue(work!!.getWorkInfosForUniqueWork(uniqueWork).get(10, TimeUnit.SECONDS).isEmpty())

        shell("logcat -c")
        shell("am start -W -n $CLIENT/.AttackActivity --es target ${context.getPackageName()}")
        // Allow asynchronous broadcast delivery/work scheduling; the caller's own UID does IPC.
        Thread.sleep(1500)
        val logs = shell("logcat -d -s andOTP-SecurityClient:I '*:S'")
        assertTrue(logs, logs.contains("PANIC_DENIED"))
        assertFalse(logs, logs.contains("PANIC_ALLOWED"))
        assertTrue(logs, logs.contains("BROADCAST_ATTEMPTED"))
        assertEquals(beforeSettings, preferences.getAll())
        assertArrayEquals(beforeVault, FileHelper.readFileToBytes(vault))
        assertTrue(
            "Unauthorized broadcast queued work",
            work!!.getWorkInfosForUniqueWork(uniqueWork).get(10, TimeUnit.SECONDS).isEmpty()
        )
    }

    companion object {
        private const val CLIENT = "org.shadowice.flocke.andotp.securityclient"
    }
}
