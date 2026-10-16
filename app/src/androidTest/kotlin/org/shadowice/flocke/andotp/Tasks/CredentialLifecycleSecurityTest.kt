package org.shadowice.flocke.andotp.Tasks

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper
import org.shadowice.flocke.andotp.Utilities.KeyStoreHelper
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.VaultSession
import java.io.File
import java.nio.file.Files
import java.util.Arrays
import java.util.HashMap
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Uses synthetic ciphertext/credentials and destroys the target's disposable test vault. */
@RunWith(AndroidJUnit4::class)
class CredentialLifecycleSecurityTest {
    private lateinit var context: Context
    private lateinit var settings: Settings
    private lateinit var preferences: SharedPreferences
    private lateinit var oldKey: SecretKey

    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext()
        DatabaseHelper.wipeDatabase(context)
        KeyStoreHelper.wipeKeys(context)
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        assertTrue(preferences.edit().clear().commit())
        settings = Settings(context)
        oldKey = SecretKeySpec(ByteArray(16), "AES")
        assertTrue(
            DatabaseHelper.changeEncryption(context, null, oldKey, true) { id ->
                settings.commitVaultSettings(
                    id,
                    Constants.EncryptionType.PASSWORD,
                    Constants.AuthMethod.PASSWORD,
                    null,
                    true
                )
            }
        )
        assertTrue(
            preferences.edit()
                .putString(context.getString(R.string.settings_key_auth_credentials), "synthetic-prior-verifier")
                .commit()
        )
    }

    @After fun cleanup() {
        DatabaseHelper.wipeDatabase(context)
        KeyStoreHelper.wipeKeys(context)
        preferences.edit().clear().commit()
    }

    private fun vault(): File = File(context.getFilesDir(), Constants.FILENAME_DATABASE)

    /** Java package-protected tests could call this hook directly; expose it through a test subclass in Kotlin. */
    private class DirectCredentialsTask(
        context: Context,
        encryptionType: Constants.EncryptionType,
        oldKey: SecretKey?,
        authMethod: Constants.AuthMethod,
        password: String?
    ) : ChangeCredentialsTask(context, encryptionType, oldKey, authMethod, password) {
        fun runSynchronously(): ChangeCredentialsTask.Result = doInBackground()
    }

    private class DirectEncryptionTask(
        context: Context,
        oldKey: SecretKey?,
        newType: Constants.EncryptionType,
        newKey: ByteArray?
    ) : ChangeEncryptionTask(context, oldKey, newType, newKey) {
        fun runSynchronously(): ChangeEncryptionTask.Result = doInBackground()
    }

    @Test fun failedLoadCannotCommitNewCredentialsOrReplaceCiphertext() {
        val wrongBytes = ByteArray(16).also { it[0] = 1 }
        val wrong = SecretKeySpec(wrongBytes, "AES")
        VaultSession.open(wrong)
        val before = HashMap(preferences.getAll())
        val ciphertext = Files.readAllBytes(vault().toPath())
        val result = DirectCredentialsTask(
            context,
            Constants.EncryptionType.PASSWORD,
            wrong,
            Constants.AuthMethod.PASSWORD,
            "synthetic-new-password"
        ).runSynchronously()
        assertFalse(result.success)
        assertNull(result.newEncryptionKey)
        assertEquals(before, preferences.getAll())
        assertArrayEquals(ciphertext, Files.readAllBytes(vault().toPath()))
    }

    @Test fun successfulChangeCommitsMatchingCiphertextVerifierAndSalt() {
        VaultSession.open(oldKey)
        val password = "synthetic-new-password"
        val result = DirectCredentialsTask(
            context,
            Constants.EncryptionType.PASSWORD,
            oldKey,
            Constants.AuthMethod.PASSWORD,
            password
        ).runSynchronously()
        assertTrue(result.success)
        assertNotNull(result.newEncryptionKey)
        assertEquals(DatabaseHelper.Status.AUTHENTICATION_FAILED, DatabaseHelper.loadDatabaseResult(context, oldKey).status)
        val loaded = DatabaseHelper.loadDatabaseResult(context, result.newEncryptionKey!!)
        assertTrue(loaded.isSuccess())
        loaded.requireEntries().forEach { it.destroy() }
        val derived = EncryptionHelper.generatePBKDF2Credentials(password, settings.getSalt(), settings.getIterations())
        try {
            assertArrayEquals(result.newEncryptionKey!!.encoded, derived.key)
            assertArrayEquals(Base64.decode(settings.getAuthCredentials(), Base64.URL_SAFE), derived.password)
        } finally {
            Arrays.fill(derived.key, 0.toByte())
            Arrays.fill(derived.password, 0.toByte())
        }
        assertEquals(Constants.AuthMethod.PASSWORD, settings.getAuthMethod())
    }

    @Test fun canceledEncryptionChangeCannotCreateReplacementKey() {
        VaultSession.open(oldKey)
        val before = HashMap(preferences.getAll())
        val ciphertext = Files.readAllBytes(vault().toPath())
        val task = DirectEncryptionTask(context, oldKey, Constants.EncryptionType.KEYSTORE, null)
        task.cancel()
        val result = task.runSynchronously()
        assertEquals(EncryptionHelper.EncryptionChangeResult.CHANGE_FAILURE, result.result)
        assertNull(result.newEncryptionKey)
        assertFalse(File(context.getFilesDir(), Constants.FILENAME_ENCRYPTED_KEY).exists())
        assertEquals(before, preferences.getAll())
        assertArrayEquals(ciphertext, Files.readAllBytes(vault().toPath()))
    }
}
