package org.shadowice.flocke.andotp.Utilities;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.Assert.*;

public class EncryptedBackupHeaderTest {
    private byte[] backup(int iterations) {
        byte[] data = new byte[EncryptedBackupHeader.MIN_BACKUP_BYTES];
        ByteBuffer.wrap(data).putInt(iterations);
        Arrays.fill(data, 4, data.length, (byte) 7);
        return data;
    }

    @Test public void acceptsHistoricalCurrentAndMaximumReadCounts() {
        for (int count : new int[] {1, 10000, 140000, 160000, EncryptedBackupHeader.MAX_ITERATIONS}) {
            EncryptedBackupHeader header = EncryptedBackupHeader.parse(backup(count));
            assertEquals(count, header.getIterations());
            assertEquals(12, header.getSalt().length);
            assertEquals(28, header.getEncrypted().length);
        }
    }

    @Test public void rejectsEveryTruncatedHeaderBeforeDerivation() {
        assertThrows(IllegalArgumentException.class, () -> EncryptedBackupHeader.parse(null));
        for (int length = 0; length < EncryptedBackupHeader.MIN_BACKUP_BYTES; length++) {
            byte[] truncated = Arrays.copyOf(backup(140000), length);
            assertThrows(IllegalArgumentException.class, () -> EncryptedBackupHeader.parse(truncated));
        }
    }

    @Test public void rejectsNegativeZeroAndCpuExhaustionCounts() {
        for (int count : new int[] {Integer.MIN_VALUE, -1, 0,
                EncryptedBackupHeader.MAX_ITERATIONS + 1, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> EncryptedBackupHeader.parse(backup(count)));
        }
    }

    @Test public void rejectsOversizedBackup() {
        byte[] oversized = new byte[BoundedInputReader.MAX_DOCUMENT_BYTES + 1];
        ByteBuffer.wrap(oversized).putInt(140000);
        assertThrows(IllegalArgumentException.class, () -> EncryptedBackupHeader.parse(oversized));
    }

    @Test public void usesBigEndianAndDoesNotAliasUntrustedBytes() {
        byte[] data = backup(140000);
        EncryptedBackupHeader header = EncryptedBackupHeader.parse(data);
        Arrays.fill(data, (byte) 0);
        assertEquals(140000, header.getIterations());
        assertEquals(7, header.getSalt()[0]);
        assertEquals(7, header.getEncrypted()[0]);
        header.getSalt()[0] = 0;
        header.getEncrypted()[0] = 0;
        assertEquals(7, header.getSalt()[0]);
        assertEquals(7, header.getEncrypted()[0]);
    }
}
