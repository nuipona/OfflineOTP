package org.shadowice.flocke.andotp.Activities

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.preference.PreferenceManager
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeyapps.barcodescanner.CaptureActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.KeyStoreHelper
import java.io.File
import java.lang.reflect.Modifier
import java.util.Arrays
import java.util.HashSet
import java.util.Set
import java.util.concurrent.TimeUnit

/** Real-platform characterization of the panic responder and QR scanner activity. */
@RunWith(AndroidJUnit4::class)
class KotlinSmallActivitiesInteropTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        DatabaseHelper.wipeDatabase(context)
        KeyStoreHelper.wipeKeys(context)
        assertTrue(PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit())
    }

    @After
    fun cleanup() {
        if (::context.isInitialized) {
            DatabaseHelper.wipeDatabase(context)
            KeyStoreHelper.wipeKeys(context)
            PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        }
    }

    private fun storePanicResponse(vararg responses: String) {
        val values = HashSet(Arrays.asList(*responses))
        assertTrue(
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putStringSet(context.getString(R.string.settings_key_panic), values)
                .commit()
        )
    }

    private fun automaticBackupPassword(): String? =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(context.getString(R.string.settings_key_auto_backup_password_enc), null)

    private fun waitUntil(what: String, condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("the activity did not $what")
    }

    @Test
    fun panicActionIsUnchanged() {
        assertEquals("info.guardianproject.panic.action.TRIGGER", PanicResponderActivity.PANIC_TRIGGER_ACTION)
    }

    @Test
    fun foreignActionWipesNothing() {
        storePanicResponse("accounts", "settings")
        val vault = File(context.filesDir, Constants.FILENAME_DATABASE)
        assertTrue(vault.createNewFile())

        context.startActivity(
            Intent(context, PanicResponderActivity::class.java)
                .setAction("org.example.disposable.SOMETHING_ELSE")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Thread.sleep(2000)

        assertTrue("a foreign action wiped the vault", vault.exists())
        assertNotEquals("a foreign action disabled the automatic backups", "off", automaticBackupPassword())
        assertTrue(
            "a foreign action cleared the panic response",
            PreferenceManager.getDefaultSharedPreferences(context)
                .getStringSet(context.getString(R.string.settings_key_panic), HashSet())!!
                .contains("accounts")
        )
    }

    @Test
    fun accountsResponseWipesTheVaultAndDisablesTheBackups() {
        storePanicResponse("accounts")
        val vault = File(context.filesDir, Constants.FILENAME_DATABASE)
        assertTrue(vault.createNewFile())

        context.startActivity(
            Intent(context, PanicResponderActivity::class.java)
                .setAction(PanicResponderActivity.PANIC_TRIGGER_ACTION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        waitUntil("wipe the vault") { !vault.exists() }
        assertEquals("the automatic backups were not disabled", "off", automaticBackupPassword())
        assertTrue(
            "the pending encrypted backup was not cancelled",
            androidx.work.WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(
                    org.shadowice.flocke.andotp.Receivers.EncryptedBackupBroadcastReceiver.UNIQUE_WORK_NAME
                ).get(10, TimeUnit.SECONDS).isEmpty()
        )
    }

    @Test
    fun settingsResponseClearsTheSettings() {
        storePanicResponse("settings")

        context.startActivity(
            Intent(context, PanicResponderActivity::class.java)
                .setAction(PanicResponderActivity.PANIC_TRIGGER_ACTION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        // The "off" marker is written before and after the wipe; wait for the final state.
        waitUntil("clear the settings and disable the backups") {
            val response = PreferenceManager.getDefaultSharedPreferences(context)
                .getStringSet(context.getString(R.string.settings_key_panic), HashSet())
            automaticBackupPassword() == "off" && (response == null || !response.contains("settings"))
        }
        assertEquals("the automatic backups were not disabled", "off", automaticBackupPassword())
    }

    @Test
    fun scannerActivityKeepsItsShape() {
        assertEquals(CaptureActivity::class.java, SecureCaptureActivity::class.java.superclass)
        assertFalse("the scanner activity became final", Modifier.isFinal(SecureCaptureActivity::class.java.modifiers))

        val initialize = SecureCaptureActivity::class.java.getDeclaredMethod("initializeContent")
        assertEquals(SecureCaptureActivity::class.java, initialize.declaringClass)
        assertTrue("the scanner content is not protected any more", Modifier.isProtected(initialize.modifiers))

        val pm: PackageManager = context.packageManager
        val info = pm.getActivityInfo(
            android.content.ComponentName(context, SecureCaptureActivity::class.java), 0
        )
        assertNotNull(info)
        assertFalse("the scanner activity is exported", info.exported)

        assertEquals(0x00002000, WindowManager.LayoutParams.FLAG_SECURE)
        assertEquals(0x00000400, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        assertEquals(0x01000000, WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
    }
}
