package org.shadowice.flocke.andotp.Utilities;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.shadowice.flocke.andotp.Database.Entry;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class EntryInputSecurityTest {
    private static final String URI = "otpauth://totp/synthetic?secret=GEZDGNBVGY3TQOJQ";
    private JSONObject json() throws Exception {
        return new JSONObject("{\"secret\":\"GEZDGNBVGY3TQOJQ\",\"label\":\"synthetic\"}");
    }

    @Test public void validLegacyAndCustomEntriesStillWork() throws Exception {
        Entry legacy = new Entry(json());
        assertEquals(30, legacy.getPeriod());
        assertEquals(6, legacy.getDigits());
        legacy.destroy();
        Entry custom = new Entry(URI + "&period=86400&digits=9");
        assertEquals(86400, custom.getPeriod());
        assertEquals(9, custom.getDigits());
        custom.destroy();
    }

    @Test public void uriRejectsAmbiguousAndUnsafeParameters() {
        for (String suffix : new String[] {"&period=0", "&period=-1", "&period=86401", "&digits=0", "&digits=10", "&digits=2147483648", "&period=30&period=31", "&secret=GEZDGNBVGY3TQOJQ"}) {
            assertThrows(suffix, Exception.class, () -> new Entry(URI + suffix));
        }
        assertThrows(Exception.class, () -> new Entry("otpauth://hotp/synthetic?secret=GEZDGNBVGY3TQOJQ"));
        assertThrows(Exception.class, () -> new Entry("otpauth://hotp/synthetic?secret=GEZDGNBVGY3TQOJQ&counter=-1"));
        assertThrows(Exception.class, () -> new Entry("otpauth://hotp/synthetic?secret=GEZDGNBVGY3TQOJQ&counter=9223372036854775807"));
    }

    @Test public void jsonCannotCoerceOrWrapNumericParameters() throws Exception {
        for (Object invalid : new Object[] {0, -1, 86401, 4294967326L, 1.5, "1.5", "9223372036854775808"}) {
            JSONObject value = json().put("period", invalid);
            assertThrows(Exception.class, () -> new Entry(value));
        }
        assertThrows(Exception.class, () -> new Entry(json().put("type", "HOTP").put("counter", Long.MAX_VALUE)));
        assertThrows(Exception.class, () -> new Entry(json().put("digits", 10)));
        assertThrows(Exception.class, () -> new Entry(json().put("type", "STEAM").put("digits", 6)));
    }

    @Test public void oversizedUriAndTagsFailClosed() throws Exception {
        StringBuilder huge = new StringBuilder(URI);
        while (huge.length() <= InputPolicy.MAX_URI_CHARS) huge.append('x');
        assertThrows(Exception.class, () -> new Entry(huge.toString()));
        org.json.JSONArray tags = new org.json.JSONArray();
        for (int i = 0; i <= InputPolicy.MAX_TAGS; i++) tags.put("tag");
        assertThrows(Exception.class, () -> new Entry(json().put("tags", tags)));
        assertThrows(Exception.class, () -> new Entry(json().put("tags", new org.json.JSONArray().put(42))));
    }
}
