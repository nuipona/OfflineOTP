package org.shadowice.flocke.andotp.Utilities;

import org.junit.Test;
import static org.junit.Assert.*;

public class InputPolicyTest {
    @Test public void imageBoundsRejectFailedDecodeAndOverflowBeforeAllocation() {
        assertFalse(InputPolicy.validImage(0, 100));
        assertFalse(InputPolicy.validImage(-1, -1));
        assertFalse(InputPolicy.validImage(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertFalse(InputPolicy.validImage(8192, 8192));
        assertTrue(InputPolicy.validImage(2000, 2000));
        assertFalse(InputPolicy.validImage(2001, 2000));
    }
    @Test public void deeplyNestedJsonIsRejectedBeforeRecursiveParsing() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i <= InputPolicy.MAX_JSON_DEPTH; i++) deep.append('[');
        assertThrows(IllegalArgumentException.class, () -> InputPolicy.jsonDepth(deep.toString()));
        InputPolicy.jsonDepth("[{\"label\":\"brackets [] {} and \\\"escaped quote\"}]");
        assertThrows(IllegalArgumentException.class, () -> InputPolicy.jsonDepth("[{}"));
    }
    @Test public void unsafeOtpMathIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> InputPolicy.tokenParameters(0, 6, 0));
        assertThrows(IllegalArgumentException.class, () -> InputPolicy.tokenParameters(86401, 6, 0));
        assertThrows(IllegalArgumentException.class, () -> InputPolicy.tokenParameters(30, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> InputPolicy.tokenParameters(30, 6, -1));
        assertThrows(IllegalArgumentException.class, () -> InputPolicy.tokenParameters(30, 6, Long.MAX_VALUE));
        InputPolicy.tokenParameters(1, 1, 0);
        InputPolicy.tokenParameters(86400, 9, Long.MAX_VALUE - 1);
    }
    @Test public void providerOutputCannotBecomeSuccessfulPartialData() {
        BoundedOutput output = new BoundedOutput(3);
        output.write(new byte[] {1, 2, 3}, 0, 3);
        assertThrows(IllegalStateException.class, () -> output.write(4));
        assertTrue(output.overflowed());
        assertEquals(3, output.size());
    }
}
