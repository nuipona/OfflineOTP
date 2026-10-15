package org.shadowice.flocke.andotp.Utilities;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.shadowice.flocke.andotp.Database.Entry;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

/** The pure Kotlin parser and the existing Java/Android Entry adapter must agree. */
@RunWith(AndroidJUnit4.class)
public class KotlinOtpInteropTest {
    @Test public void allVariantsRoundTripThroughJavaWithoutChangingOtpParameters() throws Exception {
        String base32 = "GEZDGNBVGY3TQOJQ";
        String[] fixtures = {
                "otpauth://totp/ACME%3Aalice?secret=" + base32 + "&issuer=ACME&digits=8&algorithm=sha256&tags=work",
                "otpauth://hotp/alice?secret=" + base32 + "&counter=42&digits=7&algorithm=SHA512",
                "otpauth://steam/alice?secret=" + base32,
                "otpauth://motp/alice?secret=0123456789abcdef"
        };
        for (String fixture : fixtures) {
            OtpParseResult result = OtpUriParser.parse(fixture);
            assertTrue(result.toString(), result instanceof OtpParseResult.Success);
            ValidatedOtp value = ((OtpParseResult.Success) result).getValue();
            Entry entry = new Entry(fixture);
            Entry roundTrip = null;
            try {
                assertEquals(value.getKind().name(), entry.getType().name());
                assertEquals(value.getAlgorithm().name(), entry.getAlgorithm().name());
                assertEquals(value.getPeriod(), entry.getPeriod());
                assertEquals(value.getDigits(), entry.getDigits());
                assertEquals(value.getCounter(), entry.getCounter());
                assertEquals(value.getIssuer(), entry.getIssuer());
                assertEquals(value.getLabel(), entry.getLabel());
                assertEquals(value.getTags(), entry.getTags());
                assertArrayEquals(value.copySecret(), entry.getSecret());
                roundTrip = new Entry(entry.toUri().toString());
                assertEquals(entry, roundTrip);
                assertEquals(entry.getTags(), roundTrip.getTags());
            } finally {
                value.close();
                entry.destroy();
                if (roundTrip != null) roundTrip.destroy();
            }
        }
    }

    @Test public void rfcSeedDecodingAndLeadingZeroTokenVectorRemainUnchanged() throws Exception {
        Entry entry = new Entry("otpauth://totp/rfc?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        try {
            assertArrayEquals("12345678901234567890".getBytes(StandardCharsets.UTF_8), entry.getSecret());
            // RFC 6238 SHA1 at 1111111109, eight digits: 07081804.
            int token = TokenCalculator.TOTP_RFC6238(entry.getSecret(), entry.getPeriod(), 1111111109L, 8, entry.getAlgorithm());
            assertEquals(7081804, token);
            assertEquals("07081804", Tools.formatTokenString(token, 8));
        } finally { entry.destroy(); }
    }

    @Test public void utf8DecodingAndPlusRulesAlsoHoldOnAndroid() throws Exception {
        Entry entry = new Entry("otpauth://totp/ACME%3Aalice%2B%CE%B1?secret=GEZDGNBVGY3TQOJQ&issuer=ACME&tags=work+space&tags=a%2Bb");
        try {
            assertEquals("alice+α", entry.getLabel());
            assertEquals(Arrays.asList("work space", "a+b"), entry.getTags());
        } finally { entry.destroy(); }
        for (String bad : new String[] {"%C0%AF", "%ED%A0%80", "%FF", "%E2%82"}) {
            OtpParseResult result = OtpUriParser.parse("otpauth://totp/" + bad + "?secret=GEZDGNBVGY3TQOJQ");
            assertTrue(result instanceof OtpParseResult.Failure);
            assertEquals(OtpParseError.INVALID_URI, ((OtpParseResult.Failure) result).getError());
        }
    }

    @Test public void javaCallersCanConsumeExplicitFailureWithoutExceptionsOrRawInput() {
        OtpParseResult result = OtpUriParser.parse("otpauth://hotp/private-label?secret=GEZDGNBVGY3TQOJQ");
        assertTrue(result instanceof OtpParseResult.Failure);
        assertEquals(OtpParseError.MISSING_COUNTER, ((OtpParseResult.Failure) result).getError());
        assertFalse(result.toString().contains("private-label"));
        assertEquals(Arrays.asList(Entry.OTPType.values()).size(), OtpKind.values().length);
    }
}
