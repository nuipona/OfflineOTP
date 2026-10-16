package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Arrays

class BoundedInputReaderTest {
    @Test fun acceptsEmptyAndExactlyAtLimit() {
        assertArrayEquals(byteArrayOf(), BoundedInputReader.read(ByteArrayInputStream(byteArrayOf()), 0))
        val data = byteArrayOf(0, 1, 0xff.toByte())
        assertArrayEquals(data, BoundedInputReader.read(ByteArrayInputStream(data), data.size))
    }

    @Test fun rejectsLimitPlusOne() {
        assertThrows(IOException::class.java) { BoundedInputReader.read(ByteArrayInputStream(ByteArray(4)), 3) }
        assertThrows(IOException::class.java) { BoundedInputReader.read(ByteArrayInputStream(ByteArray(1)), 0) }
    }

    @Test fun rejectsMissingStreamAndInvalidLimits() {
        assertThrows(IOException::class.java) { BoundedInputReader.read(null, 10) }
        assertThrows(IllegalArgumentException::class.java) {
            BoundedInputReader.read(ByteArrayInputStream(byteArrayOf()), -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundedInputReader.read(ByteArrayInputStream(byteArrayOf()), Int.MAX_VALUE)
        }
    }

    @Test fun stopsUnendingProviderAfterLimitPlusOne() {
        var consumed = 0
        val unending = object : InputStream() {
            override fun read(): Int {
                consumed++
                return 7
            }
        }
        assertThrows(IOException::class.java) { BoundedInputReader.read(unending, 9000) }
        assertEquals(9001, consumed)
    }

    @Test fun acceptsShortReadsAndZeroReadWithoutSpinning() {
        val data = ByteArray(9000)
        Arrays.fill(data, 7.toByte())
        val source = object : ByteArrayInputStream(data) {
            private var returnZero = true

            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                if (returnZero) {
                    returnZero = false
                    return 0
                }
                return super.read(bytes, offset, minOf(count, 3))
            }
        }
        assertArrayEquals(data, BoundedInputReader.read(source, data.size))
    }

    @Test fun propagatesProviderFailureAndDoesNotCloseCallerStream() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("provider failed")
        }
        assertThrows(IOException::class.java) { BoundedInputReader.read(broken, 10) }
        var closed = false
        val owned = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() {
                closed = true
            }
        }
        BoundedInputReader.read(owned, 1)
        assertFalse(closed)
    }
}
