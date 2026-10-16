package org.shadowice.flocke.andotp.Database

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Utilities.InputPolicy
import org.shadowice.flocke.andotp.Utilities.OtpHashAlgorithm
import org.shadowice.flocke.andotp.Utilities.OtpKind
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Locale

/**
 * The entry document boundary (`EntryJson`) and its Android adapter (`Entry`).
 * Complements `EntryInputSecurityTest`, which asserts that malformed documents throw: here the
 * stored format, the closed error set and the seed ownership are characterized directly.
 */
@RunWith(AndroidJUnit4::class)
class EntryJsonTest {
    private fun document(): JSONObject = JSONObject().put("secret", SEED).put("label", "synthetic")

    private fun value(json: JSONObject): ValidatedEntry {
        val result = EntryJson.parse(json)
        assertTrue("expected a parsed entry, got $result", result is EntryJsonResult.Success)
        return (result as EntryJsonResult.Success).value
    }

    private fun error(json: JSONObject): EntryJsonError {
        val result = EntryJson.parse(json)
        assertTrue("expected a rejected entry, got $result", result is EntryJsonResult.Failure)
        return (result as EntryJsonResult.Failure).error
    }

    @Test fun storedDocumentsKeepTheFormatDefaultsAndFieldOrder() {
        val parsed = value(JSONObject(STORED_JSON))
        try {
            assertEquals(OtpKind.TOTP, parsed.kind)
            assertEquals("5 von 5 Sterne", parsed.label)
            assertEquals("", parsed.issuer)
            assertEquals(30, parsed.period)
            assertEquals(6, parsed.digits)
            assertEquals(0L, parsed.counter)
            assertEquals(OtpHashAlgorithm.SHA1, parsed.algorithm)
            assertEquals("Default", parsed.thumbnailName)
            assertEquals(listOf("test1", "test2"), parsed.tags)
            assertArrayEquals("Das System ist sicher".toByteArray(StandardCharsets.UTF_8), parsed.copySecret())

            // Same names, same order, same values as the previous serializer.
            val rewritten = EntryJson.serialize(
                parsed.kind,
                parsed.copySecret(),
                parsed.issuer,
                parsed.label,
                parsed.digits,
                parsed.period,
                parsed.counter,
                parsed.algorithm,
                parsed.thumbnailName ?: "Default",
                parsed.lastUsed,
                parsed.usedFrequency,
                parsed.tags
            )
            assertEquals(STORED_JSON, rewritten.toString())
        } finally {
            parsed.close()
        }

        val legacy = value(JSONObject().put("secret", SEED).put("label", "legacy"))
        try {
            assertEquals(OtpKind.TOTP, legacy.kind)
            assertEquals(30, legacy.period)
            assertEquals(6, legacy.digits)
            assertEquals(0L, legacy.counter)
            assertEquals(OtpHashAlgorithm.SHA1, legacy.algorithm)
            assertEquals("", legacy.issuer)
            assertTrue(legacy.tags.isEmpty())
            assertNull(legacy.thumbnailName)
            assertEquals(0L, legacy.lastUsed)
            assertEquals(0L, legacy.usedFrequency)
        } finally {
            legacy.close()
        }

        // Steam keeps its own digit default; numbers may also arrive as strings.
        val steam = value(document().put("type", "STEAM"))
        try {
            assertEquals(OtpKind.STEAM, steam.kind)
            assertEquals(5, steam.digits)
        } finally {
            steam.close()
        }
        val numericStrings = value(document().put("period", "0030").put("digits", "8"))
        try {
            assertEquals(30, numericStrings.period)
            assertEquals(8, numericStrings.digits)
        } finally {
            numericStrings.close()
        }
    }

    @Test fun malformedFieldsAreRejectedWithClosedErrors() {
        assertEquals(EntryJsonError.MISSING_SECRET, error(JSONObject().put("label", "no secret")))
        assertEquals(EntryJsonError.INVALID_TEXT, error(document().put("secret", 5)))
        assertEquals(EntryJsonError.INVALID_TEXT, error(document().put("secret", JSONObject.NULL)))
        assertEquals(EntryJsonError.INVALID_SECRET, error(document("")))
        assertEquals(EntryJsonError.INVALID_SECRET, error(document("!!!")))
        // A padded or otherwise non-canonical seed is rejected, exactly as the previous decoder did.
        assertEquals(EntryJsonError.INVALID_SECRET, error(document(SEED + "=")))
        assertEquals(EntryJsonError.INVALID_PARAMETERS, error(document().put("period", 0)))
        assertEquals(EntryJsonError.INVALID_PARAMETERS, error(document().put("period", 86401)))
        assertEquals(EntryJsonError.INVALID_NUMBER, error(document().put("period", 4294967326L)))
        assertEquals(EntryJsonError.INVALID_NUMBER, error(document().put("period", 1.5)))
        assertEquals(EntryJsonError.INVALID_NUMBER, error(document().put("period", "9223372036854775808")))
        assertEquals(EntryJsonError.INVALID_PARAMETERS, error(document().put("digits", 10)))
        assertEquals(EntryJsonError.INVALID_PARAMETERS, error(document().put("type", "STEAM").put("digits", 6)))
        assertEquals(EntryJsonError.MISSING_COUNTER, error(document().put("type", "HOTP")))
        assertEquals(EntryJsonError.INVALID_PARAMETERS, error(document().put("type", "HOTP").put("counter", Long.MAX_VALUE)))
        assertEquals(EntryJsonError.UNSUPPORTED_TYPE, error(document().put("type", "totp")))
        assertEquals(EntryJsonError.INVALID_ALGORITHM, error(document().put("algorithm", "sha1")))
        assertEquals(
            EntryJsonError.TEXT_TOO_LONG,
            error(document().put("label", repeat("x", InputPolicy.MAX_TEXT_CHARS + 1)))
        )
        assertEquals(EntryJsonError.TOO_MANY_TAGS, error(document().put("tags", tags(InputPolicy.MAX_TAGS + 1))))
        assertEquals(EntryJsonError.INVALID_TAGS, error(document().put("tags", JSONArray().put(42))))
        assertEquals(EntryJsonError.INVALID_TAGS, error(document().put("tags", "not an array")))
        assertEquals(
            EntryJsonError.TEXT_TOO_LONG,
            error(document().put("tags", tagsOf(repeat("t", InputPolicy.MAX_TAG_CHARS + 1))))
        )

        // Display metadata is best-effort: an unknown or oversized name is not a rejection.
        val unknown = value(document().put("thumbnail", "NotAThumbnail"))
        try {
            assertEquals("NotAThumbnail", unknown.thumbnailName)
        } finally {
            unknown.close()
        }
        val oversized = value(document().put("thumbnail", repeat("x", InputPolicy.MAX_TEXT_CHARS + 1)))
        try {
            assertNull(oversized.thumbnailName)
        } finally {
            oversized.close()
        }
        val unreadableUsage = value(document().put("last_used", "x"))
        try {
            assertEquals(0L, unreadableUsage.lastUsed)
        } finally {
            unreadableUsage.close()
        }
    }

    @Test fun valuesOwnTheirSeedCloseOnceAndStayRedacted() {
        val parsed = value(document().put("label", "redact-me"))
        val copy = parsed.copySecret()
        copy[0] = 0
        assertArrayEquals("a returned copy must not be the stored seed", SEED_BYTES, parsed.copySecret())
        assertFalse(parsed.toString().contains("redact-me"))
        assertFalse(parsed.toString().contains("GEZD"))
        parsed.close()
        parsed.close() // closing twice stays harmless
        assertThrows(IllegalStateException::class.java) { parsed.copySecret() }

        val failure = EntryJson.parse(document("not-a-seed"))
        assertEquals("Failure(error=INVALID_SECRET)", failure.toString())
    }

    @Test fun entryAdapterRoundTripsAndFailsClosed() {
        val entry = Entry(JSONObject(STORED_JSON))
        try {
            assertArrayEquals("Das System ist sicher".toByteArray(StandardCharsets.UTF_8), entry.getSecret())
            assertEquals("5 von 5 Sterne", entry.getLabel())
            assertEquals(listOf("test1", "test2"), entry.getTags())
            assertEquals(STORED_JSON, entry.toJSON().toString())
        } finally {
            entry.destroy()
        }

        // A rejected document fails with a message that carries only the closed error name.
        val broken = document().put("period", 0)
        val rejected = assertThrows(IllegalArgumentException::class.java) { Entry(broken) }
        assertTrue(rejected.message, rejected.message!!.contains(EntryJsonError.INVALID_PARAMETERS.name))
        assertFalse(rejected.message, rejected.message!!.contains(SEED))
        assertFalse(rejected.message, rejected.message!!.contains("synthetic"))

        val destroyed = Entry(JSONObject(STORED_JSON))
        destroyed.destroy()
        assertNull(destroyed.getSecret())
        assertTrue(destroyed.getTags().isEmpty())
        assertEquals("", destroyed.getCurrentOTP())
        assertThrows(IllegalArgumentException::class.java) { destroyed.validate() }
    }

    @Test fun entryUrisKeepTheJavaParameterSet() {
        val totp = Entry(
            Entry.OTPType.TOTP, SEED, 30, 6, "ACME", "alice",
            TokenCalculator.HashAlgorithm.SHA1, arrayListOf()
        )
        try {
            val uri = totp.toUri()!!
            assertEquals(SEED, uri.getQueryParameter("secret"))
            assertEquals("ACME", uri.getQueryParameter("issuer"))
            // Default period and digits are not written.
            assertNull(uri.getQueryParameter("period"))
            assertNull(uri.getQueryParameter("digits"))
        } finally {
            totp.destroy()
        }

        // The previous switch fell through from HOTP into the period branch: both parameters are written.
        val hotp = Entry(
            Entry.OTPType.HOTP, SEED, 42L, 6, "ACME", "alice",
            TokenCalculator.HashAlgorithm.SHA1, arrayListOf()
        )
        try {
            val uri = hotp.toUri()!!
            assertEquals("42", uri.getQueryParameter("counter"))
            assertNull(uri.getQueryParameter("period"))
            hotp.setPeriod(60)
            assertEquals("60", hotp.toUri()!!.getQueryParameter("period"))
        } finally {
            hotp.destroy()
        }

        val motp = Entry(Entry.OTPType.MOTP, "0123456789abcdef", "ACME", "alice", arrayListOf("work"))
        try {
            val uri = motp.toUri()!!
            assertEquals("0123456789abcdef", motp.getSecretEncoded())
            assertEquals("0123456789abcdef", uri.getQueryParameter("secret"))
            assertEquals("work", uri.getQueryParameter("tags"))
        } finally {
            motp.destroy()
        }
    }

    @Test fun tokensStillComeFromTheSameCalculators() {
        val totp = Entry(
            Entry.OTPType.TOTP, SEED, 30, 6, "ACME", "alice",
            TokenCalculator.HashAlgorithm.SHA1, arrayListOf()
        )
        try {
            assertTrue(totp.updateOTP(true))
            assertNotNull(totp.getCurrentOTP())
            assertEquals(6, totp.getCurrentOTP()!!.length)
        } finally {
            totp.destroy()
        }
        val hotp = Entry(
            Entry.OTPType.HOTP, SEED, 42L, 6, "ACME", "alice",
            TokenCalculator.HashAlgorithm.SHA1, arrayListOf()
        )
        try {
            assertTrue(hotp.updateOTP(true))
            assertNotNull(hotp.getCurrentOTP())
        } finally {
            hotp.destroy()
        }
        // An mOTP entry without a PIN keeps its placeholder instead of calculating a token.
        val motp = Entry(Entry.OTPType.MOTP, "0123456789abcdef", "ACME", "alice", arrayListOf())
        try {
            assertTrue(motp.updateOTP(true))
            assertEquals("PINREQ", motp.getCurrentOTP())
        } finally {
            motp.destroy()
        }
    }

    @Test fun secretChecksMatchTheManualEntryValidator() {
        assertTrue(Entry.validateSecret(SEED, Entry.OTPType.TOTP))
        assertTrue(Entry.validateSecret(SEED.lowercase(Locale.getDefault()), Entry.OTPType.TOTP))
        assertFalse(Entry.validateSecret(SEED + "=", Entry.OTPType.TOTP))
        assertFalse(Entry.validateSecret("", Entry.OTPType.TOTP))
        assertFalse(Entry.validateSecret(null, Entry.OTPType.TOTP))
        assertFalse(Entry.validateSecret("!!!", Entry.OTPType.TOTP))
        assertFalse(Entry.validateSecret(SEED, Entry.OTPType.MOTP))
        assertTrue(Entry.validateSecret("0123456789abcdef", Entry.OTPType.MOTP))
        assertFalse(Entry.validateSecret("012", Entry.OTPType.MOTP))
        assertArrayEquals(SEED_BYTES, EntryJson.decodeBase32Secret(SEED))
        assertNull(EntryJson.decodeBase32Secret(SEED + "="))
    }

    private fun document(secret: String): JSONObject = JSONObject().put("secret", secret).put("label", "synthetic")

    private fun tags(count: Int): JSONArray = JSONArray().apply {
        repeat(count) { put("tag") }
    }

    private fun tagsOf(tag: String): JSONArray = JSONArray().put(tag)

    private fun repeat(text: String, count: Int): String = buildString(text.length * count) {
        repeat(count) { append(text) }
    }

    companion object {
        private const val SEED = "GEZDGNBVGY3TQOJQ"
        private val SEED_BYTES = "1234567890".toByteArray(StandardCharsets.US_ASCII)
        private const val STORED_JSON =
            "{\"secret\":\"IRQXGICTPFZXIZLNEBUXG5BAONUWG2DFOI======\",\"issuer\":\"\"," +
                "\"label\":\"5 von 5 Sterne\",\"digits\":6,\"type\":\"TOTP\",\"algorithm\":\"SHA1\"," +
                "\"thumbnail\":\"Default\",\"last_used\":0,\"used_frequency\":0,\"period\":30," +
                "\"tags\":[\"test1\",\"test2\"]}"
    }
}
