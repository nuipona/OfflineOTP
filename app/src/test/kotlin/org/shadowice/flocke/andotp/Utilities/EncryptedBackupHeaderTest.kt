package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Arrays

class EncryptedBackupHeaderTest {
    private fun backup(iterations: Int): ByteArray {
        val data = ByteArray(EncryptedBackupHeader.MIN_BACKUP_BYTES)
        ByteBuffer.wrap(data).putInt(iterations)
        Arrays.fill(data, 4, data.size, 7.toByte())
        return data
    }

    @Test fun acceptsHistoricalCurrentAndMaximumReadCounts() {
        for (count in intArrayOf(1, 10000, 140000, 160000, EncryptedBackupHeader.MAX_ITERATIONS)) {
            val header = EncryptedBackupHeader.parse(backup(count))
            assertEquals(count, header.getIterations())
            assertEquals(12, header.getSalt().size)
            assertEquals(28, header.getEncrypted().size)
        }
    }

    @Test fun rejectsEveryTruncatedHeaderBeforeDerivation() {
        assertThrows(IllegalArgumentException::class.java) { EncryptedBackupHeader.parse(null) }
        for (length in 0 until EncryptedBackupHeader.MIN_BACKUP_BYTES) {
            val truncated = Arrays.copyOf(backup(140000), length)
            assertThrows(IllegalArgumentException::class.java) { EncryptedBackupHeader.parse(truncated) }
        }
    }

    @Test fun rejectsNegativeZeroAndCpuExhaustionCounts() {
        for (count in intArrayOf(
            Int.MIN_VALUE, -1, 0, EncryptedBackupHeader.MAX_ITERATIONS + 1, Int.MAX_VALUE
        )) {
            assertThrows(IllegalArgumentException::class.java) { EncryptedBackupHeader.parse(backup(count)) }
        }
    }

    @Test fun rejectsOversizedBackup() {
        val oversized = ByteArray(BoundedInputReader.MAX_DOCUMENT_BYTES + 1)
        ByteBuffer.wrap(oversized).putInt(140000)
        assertThrows(IllegalArgumentException::class.java) { EncryptedBackupHeader.parse(oversized) }
    }

    @Test fun usesBigEndianAndDoesNotAliasUntrustedBytes() {
        val data = backup(140000)
        val header = EncryptedBackupHeader.parse(data)
        Arrays.fill(data, 0.toByte())
        assertEquals(140000, header.getIterations())
        assertEquals(7, header.getSalt()[0].toInt())
        assertEquals(7, header.getEncrypted()[0].toInt())
        header.getSalt()[0] = 0
        header.getEncrypted()[0] = 0
        assertEquals(7, header.getSalt()[0].toInt())
        assertEquals(7, header.getEncrypted()[0].toInt())
    }
}
