package org.shadowice.flocke.andotp.Database;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.shadowice.flocke.andotp.Utilities.InputPolicy;
import org.shadowice.flocke.andotp.Utilities.OtpHashAlgorithm;
import org.shadowice.flocke.andotp.Utilities.OtpKind;
import org.shadowice.flocke.andotp.Utilities.TokenCalculator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * The entry document boundary (`EntryJson`) and the Java/Android adapter over it (`Entry`).
 * Complements {@code EntryInputSecurityTest}, which asserts that malformed documents throw: here the
 * stored format, the closed error set and the seed ownership are characterized directly.
 */
@RunWith(AndroidJUnit4.class)
public class EntryJsonTest {
    /** Canonical, unpadded Base32 for the ten ASCII bytes "1234567890". */
    private static final String SEED = "GEZDGNBVGY3TQOJQ";
    private static final byte[] SEED_BYTES = "1234567890".getBytes(StandardCharsets.US_ASCII);

    /** What the serializer writes for a TOTP entry, in the stored field order. */
    private static final String STORED_JSON =
            "{\"secret\":\"IRQXGICTPFZXIZLNEBUXG5BAONUWG2DFOI======\",\"issuer\":\"\","
                    + "\"label\":\"5 von 5 Sterne\",\"digits\":6,\"type\":\"TOTP\",\"algorithm\":\"SHA1\","
                    + "\"thumbnail\":\"Default\",\"last_used\":0,\"used_frequency\":0,\"period\":30,"
                    + "\"tags\":[\"test1\",\"test2\"]}";

    private JSONObject document() throws Exception {
        return new JSONObject().put("secret", SEED).put("label", "synthetic");
    }

    private ValidatedEntry value(JSONObject json) {
        EntryJsonResult result = EntryJson.parse(json);
        assertTrue("expected a parsed entry, got " + result, result instanceof EntryJsonResult.Success);
        return ((EntryJsonResult.Success) result).getValue();
    }

    private EntryJsonError error(JSONObject json) {
        EntryJsonResult result = EntryJson.parse(json);
        assertTrue("expected a rejected entry, got " + result, result instanceof EntryJsonResult.Failure);
        return ((EntryJsonResult.Failure) result).getError();
    }

    @Test public void storedDocumentsKeepTheFormatDefaultsAndFieldOrder() throws Exception {
        ValidatedEntry parsed = value(new JSONObject(STORED_JSON));
        try {
            assertEquals(OtpKind.TOTP, parsed.getKind());
            assertEquals("5 von 5 Sterne", parsed.getLabel());
            assertEquals("", parsed.getIssuer());
            assertEquals(30, parsed.getPeriod());
            assertEquals(6, parsed.getDigits());
            assertEquals(0L, parsed.getCounter());
            assertEquals(OtpHashAlgorithm.SHA1, parsed.getAlgorithm());
            assertEquals("Default", parsed.getThumbnailName());
            assertEquals(Arrays.asList("test1", "test2"), parsed.getTags());
            assertArrayEquals("Das System ist sicher".getBytes(StandardCharsets.UTF_8), parsed.copySecret());

            // Same names, same order, same values as the previous Java serializer.
            JSONObject rewritten = EntryJson.serialize(parsed.getKind(), parsed.copySecret(), parsed.getIssuer(),
                    parsed.getLabel(), parsed.getDigits(), parsed.getPeriod(), parsed.getCounter(),
                    parsed.getAlgorithm(), parsed.getThumbnailName(), parsed.getLastUsed(),
                    parsed.getUsedFrequency(), parsed.getTags());
            assertEquals(STORED_JSON, rewritten.toString());
        } finally {
            parsed.close();
        }

        ValidatedEntry legacy = value(new JSONObject().put("secret", SEED).put("label", "legacy"));
        try {
            assertEquals(OtpKind.TOTP, legacy.getKind());
            assertEquals(30, legacy.getPeriod());
            assertEquals(6, legacy.getDigits());
            assertEquals(0L, legacy.getCounter());
            assertEquals(OtpHashAlgorithm.SHA1, legacy.getAlgorithm());
            assertEquals("", legacy.getIssuer());
            assertTrue(legacy.getTags().isEmpty());
            assertNull(legacy.getThumbnailName());
            assertEquals(0L, legacy.getLastUsed());
            assertEquals(0L, legacy.getUsedFrequency());
        } finally {
            legacy.close();
        }

        // Steam keeps its own digit default; numbers may also arrive as strings.
        ValidatedEntry steam = value(document().put("type", "STEAM"));
        try {
            assertEquals(OtpKind.STEAM, steam.getKind());
            assertEquals(5, steam.getDigits());
        } finally {
            steam.close();
        }
        ValidatedEntry numericStrings = value(document().put("period", "0030").put("digits", "8"));
        try {
            assertEquals(30, numericStrings.getPeriod());
            assertEquals(8, numericStrings.getDigits());
        } finally {
            numericStrings.close();
        }
    }

    @Test public void malformedFieldsAreRejectedWithClosedErrors() throws Exception {
        assertEquals(EntryJsonError.MISSING_SECRET, error(new JSONObject().put("label", "no secret")));
        assertEquals(EntryJsonError.INVALID_TEXT, error(document().put("secret", 5)));
        assertEquals(EntryJsonError.INVALID_TEXT, error(document().put("secret", JSONObject.NULL)));
        assertEquals(EntryJsonError.INVALID_SECRET, error(document("")));
        assertEquals(EntryJsonError.INVALID_SECRET, error(document("!!!")));
        // A padded or otherwise non-canonical seed is rejected, exactly as the Java decoder did.
        assertEquals(EntryJsonError.INVALID_SECRET, error(document(SEED + "=")));
        assertEquals(EntryJsonError.INVALID_PARAMETERS, error(document().put("period", 0)));
        assertEquals(EntryJsonError.INVALID_PARAMETERS, error(document().put("period", 86401)));
        assertEquals(EntryJsonError.INVALID_NUMBER, error(document().put("period", 4294967326L)));
        assertEquals(EntryJsonError.INVALID_NUMBER, error(document().put("period", 1.5)));
        assertEquals(EntryJsonError.INVALID_NUMBER, error(document().put("period", "9223372036854775808")));
        assertEquals(EntryJsonError.INVALID_PARAMETERS, error(document().put("digits", 10)));
        assertEquals(EntryJsonError.INVALID_PARAMETERS, error(document().put("type", "STEAM").put("digits", 6)));
        assertEquals(EntryJsonError.MISSING_COUNTER, error(document().put("type", "HOTP")));
        assertEquals(EntryJsonError.INVALID_PARAMETERS,
                error(document().put("type", "HOTP").put("counter", Long.MAX_VALUE)));
        assertEquals(EntryJsonError.UNSUPPORTED_TYPE, error(document().put("type", "totp")));
        assertEquals(EntryJsonError.INVALID_ALGORITHM, error(document().put("algorithm", "sha1")));
        assertEquals(EntryJsonError.TEXT_TOO_LONG,
                error(document().put("label", repeat("x", InputPolicy.MAX_TEXT_CHARS + 1))));
        assertEquals(EntryJsonError.TOO_MANY_TAGS, error(document().put("tags", tags(InputPolicy.MAX_TAGS + 1))));
        assertEquals(EntryJsonError.INVALID_TAGS, error(document().put("tags", new JSONArray().put(42))));
        assertEquals(EntryJsonError.INVALID_TAGS, error(document().put("tags", "not an array")));
        assertEquals(EntryJsonError.TEXT_TOO_LONG, error(document().put("tags", tagsOf(repeat("t", InputPolicy.MAX_TAG_CHARS + 1)))));

        // Display metadata is best-effort: an unknown or oversized name is not a rejection.
        ValidatedEntry unknown = value(document().put("thumbnail", "NotAThumbnail"));
        try {
            assertEquals("NotAThumbnail", unknown.getThumbnailName());
        } finally {
            unknown.close();
        }
        ValidatedEntry oversized = value(document().put("thumbnail", repeat("x", InputPolicy.MAX_TEXT_CHARS + 1)));
        try {
            assertNull(oversized.getThumbnailName());
        } finally {
            oversized.close();
        }
        ValidatedEntry unreadableUsage = value(document().put("last_used", "x"));
        try {
            assertEquals(0L, unreadableUsage.getLastUsed());
        } finally {
            unreadableUsage.close();
        }
    }

    @Test public void valuesOwnTheirSeedCloseOnceAndStayRedacted() throws Exception {
        ValidatedEntry parsed = value(document().put("label", "redact-me"));
        byte[] copy = parsed.copySecret();
        copy[0] = 0;
        assertArrayEquals("a returned copy must not be the stored seed", SEED_BYTES, parsed.copySecret());
        assertFalse(parsed.toString().contains("redact-me"));
        assertFalse(parsed.toString().contains("GEZD"));
        parsed.close();
        parsed.close(); // closing twice stays harmless
        assertThrows(IllegalStateException.class, parsed::copySecret);

        EntryJsonResult failure = EntryJson.parse(document("not-a-seed"));
        assertEquals("Failure(error=INVALID_SECRET)", failure.toString());
    }

    @Test public void theJavaAdapterRoundTripsAndFailsClosed() throws Exception {
        Entry entry = new Entry(new JSONObject(STORED_JSON));
        try {
            assertArrayEquals("Das System ist sicher".getBytes(StandardCharsets.UTF_8), entry.getSecret());
            assertEquals("5 von 5 Sterne", entry.getLabel());
            assertEquals(Arrays.asList("test1", "test2"), entry.getTags());
            assertEquals(STORED_JSON, entry.toJSON().toString());
        } finally {
            entry.destroy();
        }

        // A rejected document fails with a message that carries only the closed error name.
        JSONObject broken = document().put("period", 0);
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, () -> new Entry(broken));
        assertTrue(rejected.getMessage(), rejected.getMessage().contains(EntryJsonError.INVALID_PARAMETERS.name()));
        assertFalse(rejected.getMessage(), rejected.getMessage().contains(SEED));
        assertFalse(rejected.getMessage(), rejected.getMessage().contains("synthetic"));

        Entry destroyed = new Entry(new JSONObject(STORED_JSON));
        destroyed.destroy();
        assertNull(destroyed.getSecret());
        assertTrue(destroyed.getTags().isEmpty());
        assertEquals("", destroyed.getCurrentOTP());
        assertThrows(IllegalArgumentException.class, destroyed::validate);
    }

    @Test public void entryUrisKeepTheJavaParameterSet() throws Exception {
        Entry totp = new Entry(Entry.OTPType.TOTP, SEED, 30, 6, "ACME", "alice",
                TokenCalculator.HashAlgorithm.SHA1, new ArrayList<>());
        try {
            assertEquals(SEED, totp.toUri().getQueryParameter("secret"));
            assertEquals("ACME", totp.toUri().getQueryParameter("issuer"));
            // Default period and digits are not written.
            assertNull(totp.toUri().getQueryParameter("period"));
            assertNull(totp.toUri().getQueryParameter("digits"));
        } finally {
            totp.destroy();
        }

        // The Java switch fell through from HOTP into the period branch: both parameters are written.
        Entry hotp = new Entry(Entry.OTPType.HOTP, SEED, 42L, 6, "ACME", "alice",
                TokenCalculator.HashAlgorithm.SHA1, new ArrayList<>());
        try {
            assertEquals("42", hotp.toUri().getQueryParameter("counter"));
            assertNull(hotp.toUri().getQueryParameter("period"));
            hotp.setPeriod(60);
            assertEquals("60", hotp.toUri().getQueryParameter("period"));
        } finally {
            hotp.destroy();
        }

        Entry motp = new Entry(Entry.OTPType.MOTP, "0123456789abcdef", "ACME", "alice",
                new ArrayList<>(Arrays.asList("work")));
        try {
            assertEquals("0123456789abcdef", motp.getSecretEncoded());
            assertEquals("0123456789abcdef", motp.toUri().getQueryParameter("secret"));
            assertEquals("work", motp.toUri().getQueryParameter("tags"));
        } finally {
            motp.destroy();
        }
    }

    @Test public void tokensStillComeFromTheSameCalculators() throws Exception {
        Entry totp = new Entry(Entry.OTPType.TOTP, SEED, 30, 6, "ACME", "alice",
                TokenCalculator.HashAlgorithm.SHA1, new ArrayList<>());
        try {
            assertTrue(totp.updateOTP(true));
            assertNotNull(totp.getCurrentOTP());
            assertEquals(6, totp.getCurrentOTP().length());
        } finally {
            totp.destroy();
        }
        Entry hotp = new Entry(Entry.OTPType.HOTP, SEED, 42L, 6, "ACME", "alice",
                TokenCalculator.HashAlgorithm.SHA1, new ArrayList<>());
        try {
            assertTrue(hotp.updateOTP(true));
            assertNotNull(hotp.getCurrentOTP());
        } finally {
            hotp.destroy();
        }
        // An mOTP entry without a PIN keeps its placeholder instead of calculating a token.
        Entry motp = new Entry(Entry.OTPType.MOTP, "0123456789abcdef", "ACME", "alice", new ArrayList<>());
        try {
            assertTrue(motp.updateOTP(true));
            assertEquals("PINREQ", motp.getCurrentOTP());
        } finally {
            motp.destroy();
        }
    }

    @Test public void secretChecksMatchTheManualEntryValidator() {
        assertTrue(Entry.validateSecret(SEED, Entry.OTPType.TOTP));
        assertTrue(Entry.validateSecret(SEED.toLowerCase(), Entry.OTPType.TOTP));
        assertFalse(Entry.validateSecret(SEED + "=", Entry.OTPType.TOTP));
        assertFalse(Entry.validateSecret("", Entry.OTPType.TOTP));
        assertFalse(Entry.validateSecret(null, Entry.OTPType.TOTP));
        assertFalse(Entry.validateSecret("!!!", Entry.OTPType.TOTP));
        assertFalse(Entry.validateSecret(SEED, Entry.OTPType.MOTP));
        assertTrue(Entry.validateSecret("0123456789abcdef", Entry.OTPType.MOTP));
        assertFalse(Entry.validateSecret("012", Entry.OTPType.MOTP));
        assertArrayEquals(SEED_BYTES, EntryJson.decodeBase32Secret(SEED));
        assertNull(EntryJson.decodeBase32Secret(SEED + "="));
    }

    private JSONObject document(String secret) throws Exception {
        return new JSONObject().put("secret", secret).put("label", "synthetic");
    }

    private JSONArray tags(int count) {
        JSONArray array = new JSONArray();
        for (int i = 0; i < count; i++) array.put("tag");
        return array;
    }

    private JSONArray tagsOf(String tag) {
        return new JSONArray().put(tag);
    }

    private static String repeat(String text, int count) {
        StringBuilder builder = new StringBuilder(text.length() * count);
        for (int i = 0; i < count; i++) builder.append(text);
        return builder.toString();
    }
}
