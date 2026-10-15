package org.shadowice.flocke.andotp.Utilities;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Validates current backup framing before any expensive password derivation. */
public final class EncryptedBackupHeader {
    private static final int ITERATION_BYTES = 4;
    private static final int SALT_BYTES = 12;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    public static final int MIN_BACKUP_BYTES = ITERATION_BYTES + SALT_BYTES + NONCE_BYTES + TAG_BYTES;
    // Read compatibility includes historical counts below the current writer's range.
    // Counts above this ceiling require explicit review, not unbounded CPU consumption.
    public static final int MAX_ITERATIONS = 1_000_000;

    private final int iterations;
    private final byte[] salt;
    private final byte[] encrypted;

    private EncryptedBackupHeader(int iterations, byte[] salt, byte[] encrypted) {
        this.iterations = iterations;
        this.salt = salt;
        this.encrypted = encrypted;
    }

    public static EncryptedBackupHeader parse(byte[] data) {
        if (data == null || data.length < MIN_BACKUP_BYTES) {
            throw new IllegalArgumentException("Truncated encrypted backup");
        }
        if (data.length > BoundedInputReader.MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("Encrypted backup exceeds size limit");
        }
        int iterations = ByteBuffer.wrap(data, 0, ITERATION_BYTES).getInt();
        if (iterations < 1 || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException("Unsupported backup iteration count");
        }
        return new EncryptedBackupHeader(iterations,
                Arrays.copyOfRange(data, ITERATION_BYTES, ITERATION_BYTES + SALT_BYTES),
                Arrays.copyOfRange(data, ITERATION_BYTES + SALT_BYTES, data.length));
    }

    public int getIterations() { return iterations; }
    public byte[] getSalt() { return salt.clone(); }
    public byte[] getEncrypted() { return encrypted.clone(); }
}
