package org.shadowice.flocke.andotp.Utilities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.preference.PreferenceManager
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Receivers.EncryptedBackupBroadcastReceiver
import org.shadowice.flocke.andotp.Tasks.EncryptedBackupWorker
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.util.Arrays
import java.util.HashSet
import java.util.List
import java.util.concurrent.TimeUnit

/** Real-platform characterization of the converted backup components. */
@RunWith(AndroidJUnit4::class)
class KotlinBackupComponentsInteropTest {
    companion object {
        private const val PASSWORD = "synthetic-password"
        private const val FAKE_LOCATION = "content://org.example.disposable/tree/root"
    }

    private var context: Context? = null
    private var work: WorkManager? = null

    @Before
    fun setup() {
        val currentContext = InstrumentationRegistry.getInstrumentation().targetContext
        context = currentContext
        val currentWork = WorkManager.getInstance(currentContext)
        work = currentWork
        currentWork.cancelAllWork().getResult().get(10, TimeUnit.SECONDS)
        currentWork.pruneWork().getResult().get(10, TimeUnit.SECONDS)
        DatabaseHelper.wipeDatabase(currentContext)
        KeyStoreHelper.wipeKeys(currentContext)
        assertTrue(PreferenceManager.getDefaultSharedPreferences(currentContext).edit().clear().commit())
    }

    @After
    fun cleanup() {
        work?.cancelAllWork()?.getResult()?.get(10, TimeUnit.SECONDS)
        context?.let { currentContext ->
            DatabaseHelper.wipeDatabase(currentContext)
            KeyStoreHelper.wipeKeys(currentContext)
            PreferenceManager.getDefaultSharedPreferences(currentContext).edit().clear().commit()
        }
    }

    /** Stores the encrypted backup password the receiver and the worker require. */
    private fun storeEncryptedPassword() {
        val currentContext = requireNotNull(context)
        val pair: KeyPair? = KeyStoreHelper.loadOrGenerateAsymmetricKeyPair(
            currentContext, Constants.KEYSTORE_ALIAS_PASSWORD
        )
        assertNotNull(pair)
        val encrypted = EncryptionHelper.encrypt(
            pair!!.public, PASSWORD.toByteArray(StandardCharsets.UTF_8)
        )
        try {
            assertTrue(
                PreferenceManager.getDefaultSharedPreferences(currentContext).edit()
                    .putString(
                        currentContext.getString(R.string.settings_key_backup_password_enc),
                        Base64.encodeToString(encrypted, Base64.URL_SAFE)
                    ).commit()
            )
        } finally {
            Arrays.fill(encrypted, 0.toByte())
        }
    }

    private fun enableBroadcast() {
        val currentContext = requireNotNull(context)
        assertTrue(
            PreferenceManager.getDefaultSharedPreferences(currentContext).edit()
                .putStringSet(
                    currentContext.getString(R.string.settings_key_backup_broadcasts),
                    HashSet(Arrays.asList("encrypted"))
                ).commit()
        )
    }

    private fun queuedWork(): List<WorkInfo> = requireNotNull(work)
        .getWorkInfosForUniqueWork(EncryptedBackupBroadcastReceiver.UNIQUE_WORK_NAME)
        .get(10, TimeUnit.SECONDS)

    @Test
    fun aCompleteConfigurationQueuesTheEncryptedBackup() {
        val currentContext = requireNotNull(context)
        storeEncryptedPassword()
        enableBroadcast()
        val settings = Settings(currentContext)
        settings.setBackupLocation(Uri.parse(FAKE_LOCATION))
        assertTrue(settings.isEncryptedBackupBroadcastEnabled())
        assertEquals(PASSWORD, settings.getBackupPasswordEnc())

        EncryptedBackupBroadcastReceiver().onReceive(
            currentContext, Intent(EncryptedBackupBroadcastReceiver.ACTION)
        )
        assertTrue("the broadcast did not queue the backup", queuedWork().isNotEmpty())
    }

    @Test
    fun aDisabledBroadcastQueuesNothing() {
        val currentContext = requireNotNull(context)
        storeEncryptedPassword()
        val settings = Settings(currentContext)
        settings.setBackupLocation(Uri.parse(FAKE_LOCATION))
        assertFalse(settings.isEncryptedBackupBroadcastEnabled())

        EncryptedBackupBroadcastReceiver().onReceive(
            currentContext, Intent(EncryptedBackupBroadcastReceiver.ACTION)
        )
        assertTrue("the disabled broadcast queued the backup", queuedWork().isEmpty())
    }

    @Test
    fun anotherActionQueuesNothing() {
        val currentContext = requireNotNull(context)
        storeEncryptedPassword()
        enableBroadcast()
        Settings(currentContext).setBackupLocation(Uri.parse(FAKE_LOCATION))

        EncryptedBackupBroadcastReceiver().onReceive(
            currentContext, Intent("org.shadowice.flocke.andotp.broadcast.SOMETHING_ELSE")
        )
        assertTrue("an unrelated broadcast queued the backup", queuedWork().isEmpty())
    }

    @Test
    fun queuedWorkRunsTheWorkerAndFinishes() {
        val currentContext = requireNotNull(context)
        storeEncryptedPassword()
        enableBroadcast()
        Settings(currentContext).setBackupLocation(Uri.parse(FAKE_LOCATION))

        EncryptedBackupBroadcastReceiver().onReceive(
            currentContext, Intent(EncryptedBackupBroadcastReceiver.ACTION)
        )
        assertTrue(queuedWork().isNotEmpty())

        var info: WorkInfo? = null
        for (attempt in 0 until 30) {
            val infos = queuedWork()
            if (infos.isNotEmpty() && isFinished(infos[0].state)) {
                info = infos[0]
                break
            }
            Thread.sleep(1000)
        }
        assertNotNull("the queued work did not finish", info)
        assertTrue(
            "the work did not reach a terminal state: ${info!!.state}",
            info.state == WorkInfo.State.SUCCEEDED || info.state == WorkInfo.State.FAILED
        )
        assertEquals(
            EncryptedBackupWorker::class.java.name,
            requireNotNull(work).getWorkInfosForUniqueWork(
                EncryptedBackupBroadcastReceiver.UNIQUE_WORK_NAME
            ).get(10, TimeUnit.SECONDS)[0].workerClassName
        )
    }

    private fun isFinished(state: WorkInfo.State): Boolean =
        state == WorkInfo.State.SUCCEEDED || state == WorkInfo.State.FAILED ||
            state == WorkInfo.State.CANCELLED
}
