package org.shadowice.flocke.andotp.Utilities

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.util.Arrays
import javax.crypto.BadPaddingException
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Real-platform characterization of the converted crypto helpers and their callers. */
@RunWith(AndroidJUnit4::class)
class KotlinCryptoInteropTest {
    companion object {
        private const val TEST_ALIAS = "kotlin-crypto-interop"
        private const val TEST_KEY_FILE = "kotlin-crypto-interop.key"
    }

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanup() {
        File(context.filesDir, TEST_KEY_FILE).delete()
        KeyStoreHelper.wipeKeys(context)
    }

    @Test
    fun aesGcmFramingAndCredentialsMatchTheContract() {
        val key = EncryptionHelper.generateSymmetricKey(ByteArray(16))
        assertEquals("AES", key.algorithm)
        assertEquals(16, key.encoded.size)

        val plain = "account-Å-☃-日本語".toByteArray(StandardCharsets.UTF_8)
        val frame = EncryptionHelper.encrypt(key, plain)
        assertEquals(plain.size + Constants.ENCRYPTION_IV_LENGTH + 16, frame.size)
        assertArrayEquals(plain, EncryptionHelper.decrypt(key, frame))
        assertArrayEquals(ByteArray(0), EncryptionHelper.decrypt(key, EncryptionHelper.encrypt(key, ByteArray(0))))

        val otherFrame = EncryptionHelper.encrypt(key, plain)
        assertFalse(Arrays.equals(frame.copyOf(12), otherFrame.copyOf(12)))
        val wrongKey = ByteArray(16)
        wrongKey[0] = 1
        try {
            EncryptionHelper.decrypt(SecretKeySpec(wrongKey, "AES"), frame)
            fail("a wrong key decrypted the frame")
        } catch (_: BadPaddingException) {
            // GCM tag mismatch
        }
        assertThrows(BadPaddingException::class.java) {
            val modified = frame.clone()
            modified[modified.size - 1] = (modified[modified.size - 1].toInt() xor 1).toByte()
            EncryptionHelper.decrypt(key, modified)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EncryptionHelper.decrypt(key, null)
        }
        for (length in 0 until Constants.ENCRYPTION_IV_LENGTH + 16) {
            assertThrows(IllegalArgumentException::class.java) {
                EncryptionHelper.decrypt(key, ByteArray(length))
            }
        }

        // The explicit-nonce overload used by the instrumentation vectors.
        val iv = EncryptionHelper.generateRandom(Constants.ENCRYPTION_IV_LENGTH)
        assertEquals(Constants.ENCRYPTION_IV_LENGTH, iv.size)
        // The legacy backup key is a plain SHA-256 digest, so it is 32 bytes.
        assertEquals(32, EncryptionHelper.generateSymmetricKeyFromPassword("synthetic").encoded.size)
        val derivedBackupKey = EncryptionHelper.generateSymmetricKeyPBKDF2(
            "synthetic-password", 1000,
            EncryptionHelper.generateRandom(Constants.PBKDF2_SALT_LENGTH)
        )
        val explicit = EncryptionHelper.encrypt(derivedBackupKey, IvParameterSpec(iv), plain)
        assertArrayEquals(plain, EncryptionHelper.decrypt(derivedBackupKey, IvParameterSpec(iv), explicit))

        val credentials: EncryptionHelper.PBKDF2Credentials =
            EncryptionHelper.generatePBKDF2Credentials(
                "synthetic-password", EncryptionHelper.generateRandom(16), 1000
            )
        try {
            assertEquals(16, credentials.password.size)
            assertEquals(16, credentials.key.size)
            assertFalse(Arrays.equals(credentials.password, credentials.key))
        } finally {
            Arrays.fill(credentials.password, 0.toByte())
            Arrays.fill(credentials.key, 0.toByte())
        }
        assertThrows(IllegalArgumentException::class.java) {
            EncryptionHelper.generatePBKDF2Credentials("synthetic-password", ByteArray(7), 1000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EncryptionHelper.generatePBKDF2Credentials(null, ByteArray(16), 1000)
        }

        val iterations = EncryptionHelper.generateRandomIterations()
        assertTrue("backup iterations out of range: $iterations", iterations in 140000..160000)
        assertTrue(EncryptionHelper.benchmarkIterations("synthetic-password", ByteArray(16)) >= 30000)
    }

    @Test
    fun androidKeyStoreRoundTripAndCallbackShapes() {
        KeyStoreHelper.wipeKeys(context)
        assertNull(KeyStoreHelper.loadEncryptionKeyFromKeyStore(context, true))
        assertNull(KeyStoreHelper.loadAsymmetricKeyPair(context, TEST_ALIAS))

        val pair: KeyPair? = KeyStoreHelper.loadOrGenerateAsymmetricKeyPair(context, TEST_ALIAS)
        assertNotNull(pair)
        val requiredPair = requireNotNull(pair)
        val reloaded = KeyStoreHelper.loadAsymmetricKeyPair(context, TEST_ALIAS)
        assertNotNull(reloaded)
        assertArrayEquals(requiredPair.public.encoded, reloaded!!.public.encoded)

        val keyFile = File(context.filesDir, TEST_KEY_FILE)
        keyFile.delete()
        val created = EncryptionHelper.loadOrGenerateWrappedKey(keyFile, requiredPair)
        assertEquals(Constants.ENCRYPTION_KEY_LENGTH, created.encoded.size)
        assertTrue(keyFile.exists())
        val reread = EncryptionHelper.loadOrGenerateWrappedKey(keyFile, requiredPair)
        assertArrayEquals(created.encoded, reread.encoded)
        assertArrayEquals(created.encoded, SecretKeyWrapper(requiredPair).unwrap(readAll(keyFile)).encoded)

        // The vault-key entry points still work.
        val vaultKey = KeyStoreHelper.createEncryptionKey(context)
        val requiredVaultKey = requireNotNull(vaultKey)
        val loaded = KeyStoreHelper.loadEncryptionKeyFromKeyStore(context, false)
        assertNotNull(loaded)
        assertArrayEquals(requiredVaultKey.encoded, loaded!!.encoded)

        val received = arrayOfNulls<SecretKey>(1)
        val receivedType = arrayOfNulls<Constants.EncryptionType>(1)
        val callback = EncryptionHelper.EncryptionChangeCallback { newType, newKey ->
            receivedType[0] = newType
            received[0] = newKey
        }
        callback.onSuccessfulEncryptionChange(Constants.EncryptionType.KEYSTORE, requiredVaultKey)
        assertEquals(Constants.EncryptionType.KEYSTORE, receivedType[0])
        assertEquals(requiredVaultKey, received[0])
        assertEquals(
            EncryptionHelper.EncryptionChangeResult.SUCCESS,
            EncryptionHelper.EncryptionChangeResult.valueOf("SUCCESS")
        )

        KeyStoreHelper.wipeKeys(context)
        assertNull(KeyStoreHelper.loadAsymmetricKeyPair(context, TEST_ALIAS))
        assertNull(KeyStoreHelper.loadEncryptionKeyFromKeyStore(context, true))
    }

    private fun readAll(file: File): ByteArray {
        val bytes = ByteArray(file.length().toInt())
        FileInputStream(file).use { input ->
            var read = 0
            while (read < bytes.size) {
                val count = input.read(bytes, read, bytes.size - read)
                if (count < 0) break
                read += count
            }
        }
        return bytes
    }
}
