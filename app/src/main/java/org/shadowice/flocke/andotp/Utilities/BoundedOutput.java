package org.shadowice.flocke.andotp.Utilities;

import java.io.ByteArrayOutputStream;

/** Bounds plaintext/ciphertext returned by a selected external OpenPGP provider. */
public final class BoundedOutput extends ByteArrayOutputStream {
    private final int limit;
    private boolean overflowed;
    public BoundedOutput(int limit) {
        if (limit < 0) throw new IllegalArgumentException("Invalid output limit");
        this.limit = limit;
    }
    private void check(int length) {
        if (length < 0 || length > limit - count) {
            overflowed = true;
            throw new IllegalStateException("Provider output exceeds limit");
        }
    }
    @Override public synchronized void write(int value) { check(1); super.write(value); }
    @Override public synchronized void write(byte[] bytes, int offset, int length) { check(length); super.write(bytes, offset, length); }
    public synchronized boolean overflowed() { return overflowed; }
}
