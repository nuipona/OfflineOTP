package org.shadowice.flocke.andotp.Utilities

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Database.Entry

@RunWith(AndroidJUnit4::class)
class EntryInputSecurityTest {
    private fun json(): JSONObject = JSONObject("{\"secret\":\"GEZDGNBVGY3TQOJQ\",\"label\":\"synthetic\"}")

    @Test fun validLegacyAndCustomEntriesStillWork() {
        val legacy = Entry(json())
        assertEquals(30, legacy.getPeriod())
        assertEquals(6, legacy.getDigits())
        legacy.destroy()
        val custom = Entry(URI + "&period=86400&digits=9")
        assertEquals(86400, custom.getPeriod())
        assertEquals(9, custom.getDigits())
        custom.destroy()
    }

    @Test fun uriRejectsAmbiguousAndUnsafeParameters() {
        for (suffix in listOf(
            "&period=0", "&period=-1", "&period=86401", "&digits=0", "&digits=10",
            "&digits=2147483648", "&period=30&period=31", "&secret=GEZDGNBVGY3TQOJQ"
        )) {
            assertThrows(suffix, Exception::class.java) { Entry(URI + suffix) }
        }
        assertThrows(Exception::class.java) { Entry("otpauth://hotp/synthetic?secret=GEZDGNBVGY3TQOJQ") }
        assertThrows(Exception::class.java) {
            Entry("otpauth://hotp/synthetic?secret=GEZDGNBVGY3TQOJQ&counter=-1")
        }
        assertThrows(Exception::class.java) {
            Entry("otpauth://hotp/synthetic?secret=GEZDGNBVGY3TQOJQ&counter=9223372036854775807")
        }
    }

    @Test fun jsonCannotCoerceOrWrapNumericParameters() {
        for (invalid in listOf<Any>(0, -1, 86401, 4294967326L, 1.5, "1.5", "9223372036854775808")) {
            val value = json().put("period", invalid)
            assertThrows(Exception::class.java) { Entry(value) }
        }
        assertThrows(Exception::class.java) { Entry(json().put("type", "HOTP").put("counter", Long.MAX_VALUE)) }
        assertThrows(Exception::class.java) { Entry(json().put("digits", 10)) }
        assertThrows(Exception::class.java) { Entry(json().put("type", "STEAM").put("digits", 6)) }
    }

    @Test fun oversizedUriAndTagsFailClosed() {
        val huge = StringBuilder(URI)
        while (huge.length <= InputPolicy.MAX_URI_CHARS) huge.append('x')
        assertThrows(Exception::class.java) { Entry(huge.toString()) }
        val tags = JSONArray()
        for (i in 0..InputPolicy.MAX_TAGS) tags.put("tag")
        assertThrows(Exception::class.java) { Entry(json().put("tags", tags)) }
        assertThrows(Exception::class.java) { Entry(json().put("tags", JSONArray().put(42))) }
    }

    companion object {
        private const val URI = "otpauth://totp/synthetic?secret=GEZDGNBVGY3TQOJQ"
    }
}
