/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 * Copyright (C) 2015 Bruno Bierbaumer
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.shadowice.flocke.andotp

import android.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import org.apache.commons.codec.binary.Base32
import org.apache.commons.codec.binary.Hex
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper
import org.shadowice.flocke.andotp.Utilities.KeyStoreHelper
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Arrays
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** OTP calculation, entry parsing and database/crypto tests against published test vectors. */
class ApplicationTest {
    @Test fun testTOTPCalculation() {
        // Test vectors from RFC 6238, appendix B
        val keySHA1 = "12345678901234567890".toByteArray(StandardCharsets.US_ASCII)
        val keySHA256 = "12345678901234567890123456789012".toByteArray(StandardCharsets.US_ASCII)
        val keySHA512 = "1234567890123456789012345678901234567890123456789012345678901234"
            .toByteArray(StandardCharsets.US_ASCII)

        assertEquals(94287082, TokenCalculator.TOTP_RFC6238(keySHA1, TokenCalculator.TOTP_DEFAULT_PERIOD, 59L, 8, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals(46119246, TokenCalculator.TOTP_RFC6238(keySHA256, TokenCalculator.TOTP_DEFAULT_PERIOD, 59L, 8, TokenCalculator.HashAlgorithm.SHA256))
        assertEquals(90693936, TokenCalculator.TOTP_RFC6238(keySHA512, TokenCalculator.TOTP_DEFAULT_PERIOD, 59L, 8, TokenCalculator.HashAlgorithm.SHA512))

        assertEquals(7081804, TokenCalculator.TOTP_RFC6238(keySHA1, TokenCalculator.TOTP_DEFAULT_PERIOD, 1111111109L, 8, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals(68084774, TokenCalculator.TOTP_RFC6238(keySHA256, TokenCalculator.TOTP_DEFAULT_PERIOD, 1111111109L, 8, TokenCalculator.HashAlgorithm.SHA256))
        assertEquals(25091201, TokenCalculator.TOTP_RFC6238(keySHA512, TokenCalculator.TOTP_DEFAULT_PERIOD, 1111111109L, 8, TokenCalculator.HashAlgorithm.SHA512))

        assertEquals(14050471, TokenCalculator.TOTP_RFC6238(keySHA1, TokenCalculator.TOTP_DEFAULT_PERIOD, 1111111111L, 8, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals(67062674, TokenCalculator.TOTP_RFC6238(keySHA256, TokenCalculator.TOTP_DEFAULT_PERIOD, 1111111111L, 8, TokenCalculator.HashAlgorithm.SHA256))
        assertEquals(99943326, TokenCalculator.TOTP_RFC6238(keySHA512, TokenCalculator.TOTP_DEFAULT_PERIOD, 1111111111L, 8, TokenCalculator.HashAlgorithm.SHA512))

        assertEquals(89005924, TokenCalculator.TOTP_RFC6238(keySHA1, TokenCalculator.TOTP_DEFAULT_PERIOD, 1234567890L, 8, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals(91819424, TokenCalculator.TOTP_RFC6238(keySHA256, TokenCalculator.TOTP_DEFAULT_PERIOD, 1234567890L, 8, TokenCalculator.HashAlgorithm.SHA256))
        assertEquals(93441116, TokenCalculator.TOTP_RFC6238(keySHA512, TokenCalculator.TOTP_DEFAULT_PERIOD, 1234567890L, 8, TokenCalculator.HashAlgorithm.SHA512))

        assertEquals(69279037, TokenCalculator.TOTP_RFC6238(keySHA1, TokenCalculator.TOTP_DEFAULT_PERIOD, 2000000000L, 8, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals(90698825, TokenCalculator.TOTP_RFC6238(keySHA256, TokenCalculator.TOTP_DEFAULT_PERIOD, 2000000000L, 8, TokenCalculator.HashAlgorithm.SHA256))
        assertEquals(38618901, TokenCalculator.TOTP_RFC6238(keySHA512, TokenCalculator.TOTP_DEFAULT_PERIOD, 2000000000L, 8, TokenCalculator.HashAlgorithm.SHA512))

        assertEquals(65353130, TokenCalculator.TOTP_RFC6238(keySHA1, TokenCalculator.TOTP_DEFAULT_PERIOD, 20000000000L, 8, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals(77737706, TokenCalculator.TOTP_RFC6238(keySHA256, TokenCalculator.TOTP_DEFAULT_PERIOD, 20000000000L, 8, TokenCalculator.HashAlgorithm.SHA256))
        assertEquals(47863826, TokenCalculator.TOTP_RFC6238(keySHA512, TokenCalculator.TOTP_DEFAULT_PERIOD, 20000000000L, 8, TokenCalculator.HashAlgorithm.SHA512))
    }

    @Test fun testHOTPCalculation() {
        // Test cases from RFC 4226, appendix D
        val keySHA1 = "12345678901234567890".toByteArray(StandardCharsets.US_ASCII)

        assertEquals("755224", TokenCalculator.HOTP(keySHA1, 0, 6, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals("287082", TokenCalculator.HOTP(keySHA1, 1, 6, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals("359152", TokenCalculator.HOTP(keySHA1, 2, 6, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals("969429", TokenCalculator.HOTP(keySHA1, 3, 6, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals("338314", TokenCalculator.HOTP(keySHA1, 4, 6, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals("254676", TokenCalculator.HOTP(keySHA1, 5, 6, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals("287922", TokenCalculator.HOTP(keySHA1, 6, 6, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals("162583", TokenCalculator.HOTP(keySHA1, 7, 6, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals("399871", TokenCalculator.HOTP(keySHA1, 8, 6, TokenCalculator.HashAlgorithm.SHA1))
        assertEquals("520489", TokenCalculator.HOTP(keySHA1, 9, 6, TokenCalculator.HashAlgorithm.SHA1))
    }

    @Test fun testEntry() {
        val secret = "Das System ist sicher".toByteArray()
        val label = "5 von 5 Sterne"
        val period = 30

        val json = "{\"secret\":\"${String(Base32().encode(secret), StandardCharsets.US_ASCII)}\"," +
            "\"issuer\":\"\"," +
            "\"label\":\"$label\"," +
            "\"digits\":6," +
            "\"type\":\"TOTP\"," +
            "\"algorithm\":\"SHA1\"," +
            "\"thumbnail\":\"Default\"," +
            "\"last_used\":0," +
            "\"used_frequency\":0," +
            "\"period\":$period," +
            "\"tags\":[\"test1\",\"test2\"]}"

        val entry = Entry(JSONObject(json))
        assertTrue(Arrays.equals(secret, entry.getSecret()))
        assertEquals(label, entry.getLabel())

        val tags = arrayOf("test1", "test2")
        assertEquals(tags.size, entry.getTags().size)
        assertTrue(Arrays.equals(tags, entry.getTags().toTypedArray()))
        assertEquals(json, entry.toJSON().toString())
    }

    @Test fun testEntryURL() {
        assertThrows(Exception::class.java) { Entry("DON'T CARE") }
        assertThrows(Exception::class.java) { Entry("https://github.com/0xbb/") }
        assertThrows(Exception::class.java) {
            Entry("otpauth://hotp/ACME%20Co:john.doe@email.com?secret=HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ")
        }
        assertThrows(Exception::class.java) { Entry("otpauth://totp/ACME") }

        var entry = Entry("otpauth://totp/ACME%20Co:john.doe@email.com?secret=HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ&issuer=ACME%20Co&ALGORITHM=SHA1&digits=6&period=30")
        assertEquals("john.doe@email.com", entry.getLabel())

        val entry2 = Entry("otpauth://totp/ :john.doe@email.com?secret=HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ&ALGORITHM=SHA1&digits=6&period=30")
        assertEquals(":john.doe@email.com", entry2.getLabel())

        val entry3 = Entry("otpauth://totp/ :john.doe@email.com?secret=HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ&issuer=%20&ALGORITHM=SHA1&digits=6&period=30")
        assertEquals("john.doe@email.com", entry3.getLabel())

        assertEquals("HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ", String(Base32().encode(entry.getSecret()), StandardCharsets.US_ASCII))

        entry = Entry("otpauth://totp/ACME%20Co:john.doe@email.com?secret=HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ&issuer=ACME%20Co&ALGORITHM=SHA1&digits=6&period=30&tags=test1&tags=test2")
        assertEquals("john.doe@email.com", entry.getLabel())
        assertEquals("HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ", String(Base32().encode(entry.getSecret()), StandardCharsets.US_ASCII))
        val tags = arrayOf("test1", "test2")
        assertEquals(tags.size, entry.getTags().size)
        assertTrue(Arrays.equals(tags, entry.getTags().toTypedArray()))
    }

    @Test fun testSettingsHelper() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        fun cleanup() {
            DatabaseHelper.wipeDatabase(context)
            KeyStoreHelper.wipeKeys(context)
            assertTrue(preferences.edit().clear().commit())
        }

        cleanup()
        val newEntries = ArrayList<Entry>()
        var entries = ArrayList<Entry>()
        try {
            val encryptionKey = KeyStoreHelper.createEncryptionKey(context)
            assertNotNull("The fixture must create a wrapped vault key", encryptionKey)
            assertTrue(
                DatabaseHelper.changeEncryption(context, null, encryptionKey, true) { id ->
                    // Construct here deliberately: Settings startup recovery used to discard the
                    // live journal before the callback committed its marker, yet report success.
                    Settings(context).commitVaultSettings(
                        id,
                        Constants.EncryptionType.KEYSTORE,
                        Constants.AuthMethod.NONE,
                        null,
                        true
                    )
                }
            )
            assertTrue(File(context.filesDir, Constants.FILENAME_DATABASE).isFile)
            assertFalse(File(context.filesDir, DatabaseHelper.PENDING_FILE).exists())
            entries = DatabaseHelper.loadDatabase(context, encryptionKey)
            assertEquals(0, entries.size)

            for ((label, secret) in listOf("label" to "secret", "label2" to "secret2")) {
                val entry = Entry()
                entry.setLabel(label)
                entry.setIssuer("", false)
                entry.setSecret(secret.toByteArray(StandardCharsets.UTF_8))
                newEntries.add(entry)
            }

            assertTrue(DatabaseHelper.saveDatabase(context, newEntries, encryptionKey))
            // A fresh Settings instance and a read-only key lookup must see the committed vault.
            val settings = Settings(context)
            assertEquals(Constants.EncryptionType.KEYSTORE, settings.getEncryption())
            assertEquals(Constants.AuthMethod.NONE, settings.getAuthMethod())
            val storedKey = KeyStoreHelper.loadEncryptionKeyFromKeyStore(context, true)
            assertNotNull(storedKey)
            entries = DatabaseHelper.loadDatabase(context, storedKey)
            assertEquals(newEntries, entries)
        } finally {
            entries.forEach { it.destroy() }
            newEntries.forEach { it.destroy() }
            cleanup()
        }
    }

    @Test fun testEncryptionHelper() {
        // AES-GCM test vectors from https://golang.org/src/crypto/cipher/gcm_test.go
        val testCases = arrayOf(
            arrayOf("11754cd72aec309bf52f7687212e8957", "3c819d9a9bed087615030b65", "", "250327c674aaf477aef2675748cf6971"),
            arrayOf("ca47248ac0b6f8372a97ac43508308ed", "ffd2b598feabc9019262d2be", "", "60d20404af527d248d893ae495707d1a"),
            arrayOf("7fddb57453c241d03efbed3ac44e371c", "ee283a3fc75575e33efd4887", "d5de42b461646c255c87bd2962d3b9a2", "2ccda4a5415cb91e135c2a0f78c9b2fdb36d1df9b9d5e596f83e8b7f52971cb3"),
            arrayOf("ab72c77b97cb5fe9a382d9fe81ffdbed", "54cc7dc2c37ec006bcc6d1da", "007c5e5b3e59df24a7c355584fc1518d", "0e1bde206a07a9c2c1b65300f8c649972b4401346697138c7a4891ee59867d0c"),
            arrayOf("feffe9928665731c6d6a8f9467308308", "cafebabefacedbaddecaf888", "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255", "42831ec2217774244b7221b784d0d49ce3aa212f2c02a4e035c17e2329aca12e21d514b25466931c7d8f6a5aac84aa051ba30b396a0aac973d58e091473f59854d5c2af327cd64a62cf35abd2ba6fab4")
        )

        for (testCase in testCases) {
            val key = SecretKeySpec(Hex.decodeHex(testCase[0]), "AES")
            val iv = IvParameterSpec(Hex.decodeHex(testCase[1]))
            val cipherText = EncryptionHelper.encrypt(key, iv, Hex.decodeHex(testCase[2]))
            val cipher = String(Hex.encodeHex(cipherText))
            assertEquals(testCase[3], cipher)
            assertEquals(testCase[2], String(Hex.encodeHex(EncryptionHelper.decrypt(key, iv, cipherText))))
        }
    }
}
