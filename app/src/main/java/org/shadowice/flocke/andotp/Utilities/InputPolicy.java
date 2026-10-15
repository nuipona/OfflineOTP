package org.shadowice.flocke.andotp.Utilities;

/** Shared limits for all entry/import paths, including legacy JSON. */
public final class InputPolicy {
    public static final int MAX_URI_CHARS = 16384;
    public static final int MAX_ENTRIES = 2000;
    public static final int MAX_JSON_DEPTH = 32;
    public static final int MAX_TEXT_CHARS = 1024;
    public static final int MAX_TAGS = 32;
    public static final int MAX_TAG_CHARS = 128;
    public static final int MAX_SECRET_BYTES = 2048;
    public static final int MAX_PERIOD_SECONDS = 86400;
    public static final int MAX_IMAGE_DIMENSION = 8192;
    public static final long MAX_IMAGE_PIXELS = 4_000_000L;

    private InputPolicy() { }

    public static void text(String value, int limit) {
        if (value != null && value.length() > limit) {
            throw new IllegalArgumentException("Entry text exceeds limit");
        }
    }

    /** Stop deeply nested untrusted JSON before the platform's recursive parser. */
    public static void jsonDepth(String data) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (quoted && c == '\\') { i++; continue; }
            if (c == '"') { quoted = !quoted; continue; }
            if (quoted) continue;
            // Android JSON also accepts single quotes/comments; disallow these extensions
            // so they cannot disagree with this scanner about string boundaries.
            if (c == '\'' || c == '/' || c == '#' || c == '\\') throw new IllegalArgumentException("Invalid JSON syntax");
            if (c == '[' || c == '{') {
                if (++depth > MAX_JSON_DEPTH) throw new IllegalArgumentException("JSON nesting exceeds limit");
            } else if (c == ']' || c == '}') {
                if (--depth < 0) throw new IllegalArgumentException("Invalid JSON nesting");
            }
        }
        // Full JSON syntax/type validation is still performed by the platform parser.
        if (depth != 0 || quoted) throw new IllegalArgumentException("Invalid JSON nesting");
    }

    public static boolean validImage(int width, int height) {
        return width > 0 && height > 0 && width <= MAX_IMAGE_DIMENSION
                && height <= MAX_IMAGE_DIMENSION && (long) width * height <= MAX_IMAGE_PIXELS;
    }

    public static void tokenParameters(int period, int digits, long counter) {
        // Keep legacy custom 1–9 digit OTPs, but never overflow the int modulus.
        if (period < 1 || period > MAX_PERIOD_SECONDS || digits < 1 || digits > 9
                || counter < 0 || counter == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid OTP parameters");
        }
    }
}
