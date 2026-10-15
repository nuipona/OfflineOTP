package org.shadowice.flocke.andotp.Utilities;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static org.junit.Assert.*;

public class BoundedInputReaderTest {
    @Test public void acceptsEmptyAndExactlyAtLimit() throws Exception {
        assertArrayEquals(new byte[0], BoundedInputReader.read(new ByteArrayInputStream(new byte[0]), 0));
        byte[] data = {0, 1, (byte) 255};
        assertArrayEquals(data, BoundedInputReader.read(new ByteArrayInputStream(data), data.length));
    }

    @Test public void rejectsLimitPlusOne() {
        assertThrows(IOException.class, () -> BoundedInputReader.read(new ByteArrayInputStream(new byte[4]), 3));
        assertThrows(IOException.class, () -> BoundedInputReader.read(new ByteArrayInputStream(new byte[1]), 0));
    }

    @Test public void rejectsMissingStreamAndInvalidLimits() {
        assertThrows(IOException.class, () -> BoundedInputReader.read(null, 10));
        assertThrows(IllegalArgumentException.class,
                () -> BoundedInputReader.read(new ByteArrayInputStream(new byte[0]), -1));
        assertThrows(IllegalArgumentException.class,
                () -> BoundedInputReader.read(new ByteArrayInputStream(new byte[0]), Integer.MAX_VALUE));
    }

    @Test public void stopsUnendingProviderAfterLimitPlusOne() {
        int[] consumed = {0};
        InputStream unending = new InputStream() {
            @Override public int read() { consumed[0]++; return 7; }
        };
        assertThrows(IOException.class, () -> BoundedInputReader.read(unending, 9000));
        assertEquals(9001, consumed[0]);
    }

    @Test public void acceptsShortReadsAndZeroReadWithoutSpinning() throws Exception {
        byte[] data = new byte[9000];
        Arrays.fill(data, (byte) 7);
        InputStream source = new ByteArrayInputStream(data) {
            private boolean returnZero = true;
            @Override public synchronized int read(byte[] bytes, int offset, int count) {
                if (returnZero) { returnZero = false; return 0; }
                return super.read(bytes, offset, Math.min(count, 3));
            }
        };
        assertArrayEquals(data, BoundedInputReader.read(source, data.length));
    }

    @Test public void propagatesProviderFailureAndDoesNotCloseCallerStream() throws Exception {
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("provider failed"); }
        };
        assertThrows(IOException.class, () -> BoundedInputReader.read(broken, 10));
        boolean[] closed = {false};
        InputStream owned = new ByteArrayInputStream(new byte[] {1}) {
            @Override public void close() { closed[0] = true; }
        };
        BoundedInputReader.read(owned, 1);
        assertFalse(closed[0]);
    }
}
