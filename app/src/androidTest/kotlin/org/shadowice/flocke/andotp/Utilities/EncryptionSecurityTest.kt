package org.shadowice.flocke.andotp.Utilities

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets
import java.util.Arrays
import javax.crypto.BadPaddingException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class EncryptionSecurityTest {
    private fun key(value: Int): SecretKey {
        val bytes = ByteArray(16)
        Arrays.fill(bytes, value.toByte())
        return SecretKeySpec(bytes, "AES")
    }

    @Test fun emptyAndUnicodePlaintextRoundTrip() {
        for (plain in listOf("", "account-Å-☃-日本語")) {
            val bytes = plain.toByteArray(StandardCharsets.UTF_8)
            val encrypted = EncryptionHelper.encrypt(key(1), bytes)
            assertEquals(bytes.size + 12 + 16, encrypted.size)
            assertArrayEquals(bytes, EncryptionHelper.decrypt(key(1), encrypted))
        }
    }

    @Test fun wrongKeyAndModifiedNonceCiphertextTagFailAuthentication() {
        val key = key(1)
        val encrypted = EncryptionHelper.encrypt(key, "synthetic-secret".toByteArray(StandardCharsets.UTF_8))
        assertThrows(BadPaddingException::class.java) { EncryptionHelper.decrypt(key(2), encrypted) }
        for (position in intArrayOf(0, 12, encrypted.size - 1)) {
            val modified = encrypted.clone()
            modified[position] = (modified[position].toInt() xor 1).toByte()
            assertThrows(BadPaddingException::class.java) { EncryptionHelper.decrypt(key, modified) }
        }
    }

    @Test fun rejectsNullAndAllShortFrames() {
        val key = key(1)
        assertThrows(IllegalArgumentException::class.java) { EncryptionHelper.decrypt(key, null) }
        for (length in 0 until 28) {
            val frame = ByteArray(length)
            assertThrows(IllegalArgumentException::class.java) { EncryptionHelper.decrypt(key, frame) }
        }
    }

    @Test fun repeatedEncryptionUsesDifferentNonces() {
        val key = key(1)
        val plain = "synthetic-secret".toByteArray(StandardCharsets.UTF_8)
        val first = EncryptionHelper.encrypt(key, plain)
        val second = EncryptionHelper.encrypt(key, plain)
        // Collision probability for independent 96-bit nonces is negligible.
        assertFalse(Arrays.equals(Arrays.copyOf(first, 12), Arrays.copyOf(second, 12)))
    }
}
