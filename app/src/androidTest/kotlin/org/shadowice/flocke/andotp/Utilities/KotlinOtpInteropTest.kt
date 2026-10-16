package org.shadowice.flocke.andotp.Utilities

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Database.Entry
import java.nio.charset.StandardCharsets
import java.util.Arrays

/** The Kotlin parser and the Android Entry adapter must agree. */
@RunWith(AndroidJUnit4::class)
class KotlinOtpInteropTest {
    @Test
    fun allVariantsRoundTripWithoutChangingOtpParameters() {
        val base32 = "GEZDGNBVGY3TQOJQ"
        val fixtures = arrayOf(
            "otpauth://totp/ACME%3Aalice?secret=$base32&issuer=ACME&digits=8&algorithm=sha256&tags=work",
            "otpauth://hotp/alice?secret=$base32&counter=42&digits=7&algorithm=SHA512",
            "otpauth://steam/alice?secret=$base32",
            "otpauth://motp/alice?secret=0123456789abcdef"
        )
        for (fixture in fixtures) {
            val result = OtpUriParser.parse(fixture)
            assertTrue(result.toString(), result is OtpParseResult.Success)
            val value = (result as OtpParseResult.Success).value
            val entry = Entry(fixture)
            var roundTrip: Entry? = null
            try {
                assertEquals(value.kind.name, entry.getType().name)
                assertEquals(value.algorithm.name, entry.getAlgorithm().name)
                assertEquals(value.period, entry.getPeriod())
                assertEquals(value.digits, entry.getDigits())
                assertEquals(value.counter, entry.getCounter())
                assertEquals(value.issuer, entry.getIssuer())
                assertEquals(value.label, entry.getLabel())
                assertEquals(value.tags, entry.getTags())
                assertArrayEquals(value.copySecret(), entry.getSecret())
                roundTrip = Entry(entry.toUri()!!.toString())
                assertEquals(entry, roundTrip)
                assertEquals(entry.getTags(), roundTrip.getTags())
            } finally {
                value.close()
                entry.destroy()
                roundTrip?.destroy()
            }
        }
    }

    @Test
    fun rfcSeedDecodingAndLeadingZeroTokenVectorRemainUnchanged() {
        val entry = Entry("otpauth://totp/rfc?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        try {
            assertArrayEquals("12345678901234567890".toByteArray(StandardCharsets.UTF_8), entry.getSecret())
            // RFC 6238 SHA1 at 1111111109, eight digits: 07081804.
            val token = TokenCalculator.TOTP_RFC6238(
                requireNotNull(entry.getSecret()), entry.getPeriod(), 1111111109L, 8, entry.getAlgorithm()
            )
            assertEquals(7081804, token)
            assertEquals("07081804", Tools.formatTokenString(token, 8))
        } finally {
            entry.destroy()
        }
    }

    @Test
    fun utf8DecodingAndPlusRulesAlsoHoldOnAndroid() {
        val entry = Entry(
            "otpauth://totp/ACME%3Aalice%2B%CE%B1?secret=GEZDGNBVGY3TQOJQ&issuer=ACME&tags=work+space&tags=a%2Bb"
        )
        try {
            assertEquals("alice+α", entry.getLabel())
            assertEquals(Arrays.asList("work space", "a+b"), entry.getTags())
        } finally {
            entry.destroy()
        }
        for (bad in arrayOf("%C0%AF", "%ED%A0%80", "%FF", "%E2%82")) {
            val result = OtpUriParser.parse("otpauth://totp/$bad?secret=GEZDGNBVGY3TQOJQ")
            assertTrue(result is OtpParseResult.Failure)
            assertEquals(OtpParseError.INVALID_URI, (result as OtpParseResult.Failure).error)
        }
    }

    @Test
    fun callersCanConsumeExplicitFailureWithoutExceptionsOrRawInput() {
        val result = OtpUriParser.parse("otpauth://hotp/private-label?secret=GEZDGNBVGY3TQOJQ")
        assertTrue(result is OtpParseResult.Failure)
        assertEquals(OtpParseError.MISSING_COUNTER, (result as OtpParseResult.Failure).error)
        assertFalse(result.toString().contains("private-label"))
        assertEquals(Entry.OTPType.values().size, OtpKind.values().size)
    }
}
