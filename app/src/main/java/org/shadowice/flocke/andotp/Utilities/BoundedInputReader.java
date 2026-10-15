package org.shadowice.flocke.andotp.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Bounded reads of untrusted documents; does not rely on provider-reported sizes. */
public final class BoundedInputReader {
    // Applies to encrypted backup and QR image bytes. Decoded-image limits are separate.
    public static final int MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;

    private BoundedInputReader() { }

    /** Caller owns the stream. Reads at most limit + 1 bytes to detect oversized input. */
    public static byte[] read(InputStream input, int limit) throws IOException {
        if (input == null) {
            throw new IOException("Document provider returned no stream");
        }
        if (limit < 0 || limit == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid document size limit");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        while (true) {
            int count = input.read(buffer, 0, Math.min(buffer.length, limit - total + 1));
            if (count == -1) {
                return output.toByteArray();
            }
            // Some providers return zero without reaching EOF. Guarantee progress.
            if (count == 0) {
                int next = input.read();
                if (next == -1) {
                    return output.toByteArray();
                }
                buffer[0] = (byte) next;
                count = 1;
            }
            if (count > limit - total) {
                throw new IOException("Document exceeds size limit");
            }
            output.write(buffer, 0, count);
            total += count;
        }
    }
}
