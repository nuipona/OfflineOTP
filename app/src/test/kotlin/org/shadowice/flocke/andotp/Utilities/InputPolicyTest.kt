package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InputPolicyTest {
    @Test fun imageBoundsRejectFailedDecodeAndOverflowBeforeAllocation() {
        assertFalse(InputPolicy.validImage(0, 100))
        assertFalse(InputPolicy.validImage(-1, -1))
        assertFalse(InputPolicy.validImage(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(InputPolicy.validImage(8192, 8192))
        assertTrue(InputPolicy.validImage(2000, 2000))
        assertFalse(InputPolicy.validImage(2001, 2000))
    }

    @Test fun deeplyNestedJsonIsRejectedBeforeRecursiveParsing() {
        val deep = StringBuilder()
        for (ignored in 0..InputPolicy.MAX_JSON_DEPTH) deep.append('[')
        assertThrows(IllegalArgumentException::class.java) { InputPolicy.jsonDepth(deep.toString()) }
        InputPolicy.jsonDepth("[{\"label\":\"brackets [] {} and \\\"escaped quote\"}]")
        assertThrows(IllegalArgumentException::class.java) { InputPolicy.jsonDepth("[{}") }
    }

    @Test fun unsafeOtpMathIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { InputPolicy.tokenParameters(0, 6, 0) }
        assertThrows(IllegalArgumentException::class.java) { InputPolicy.tokenParameters(86401, 6, 0) }
        assertThrows(IllegalArgumentException::class.java) { InputPolicy.tokenParameters(30, 10, 0) }
        assertThrows(IllegalArgumentException::class.java) { InputPolicy.tokenParameters(30, 6, -1) }
        assertThrows(IllegalArgumentException::class.java) {
            InputPolicy.tokenParameters(30, 6, Long.MAX_VALUE)
        }
        InputPolicy.tokenParameters(1, 1, 0)
        InputPolicy.tokenParameters(86400, 9, Long.MAX_VALUE - 1)
    }

    @Test fun providerOutputCannotBecomeSuccessfulPartialData() {
        val output = BoundedOutput(3)
        output.write(byteArrayOf(1, 2, 3), 0, 3)
        assertThrows(IllegalStateException::class.java) { output.write(4) }
        assertTrue(output.overflowed())
        assertEquals(3, output.size())
    }
}
