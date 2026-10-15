/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 * Copyright (C) 2015 Bruno Bierbaumer
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.shadowice.flocke.andotp.Utilities;

import org.apache.commons.codec.binary.Hex;

import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Generates one-time passwords: TOTP/HOTP (RFC 6238/4226), Steam Guard and mOTP. */
public class TokenCalculator {
    // Defaults used when an entry or otpauth URI does not specify them
    public static final int TOTP_DEFAULT_PERIOD = 30;
    public static final int TOTP_DEFAULT_DIGITS = 6;
    public static final int HOTP_INITIAL_COUNTER = 1;
    public static final int STEAM_DEFAULT_DIGITS = 5;

    // Steam's token alphabet (excludes easily confused characters)
    private static final char[] STEAMCHARS = new char[] {
            '2', '3', '4', '5', '6', '7', '8', '9', 'B', 'C',
            'D', 'F', 'G', 'H', 'J', 'K', 'M', 'N', 'P', 'Q',
            'R', 'T', 'V', 'W', 'X', 'Y'
    };

    /** Hash algorithms usable for the HMAC part of TOTP/HOTP. */
    public enum HashAlgorithm {
        SHA1, SHA256, SHA512
    }

    public static final HashAlgorithm DEFAULT_ALGORITHM = HashAlgorithm.SHA1;

    /** HMAC of {@code data} with the given algorithm and key. */
    private static byte[] generateHash(HashAlgorithm algorithm, byte[] key, byte[] data)
            throws NoSuchAlgorithmException, InvalidKeyException {
        String algo = "Hmac" + algorithm.toString();

        Mac mac = Mac.getInstance(algo);
        mac.init(new SecretKeySpec(key, algo));

        return mac.doFinal(data);
    }

    // TODO: Rewrite tests so this compatibility wrapper can be removed
    /** Convenience overload without a time step offset. */
    public static int TOTP_RFC6238(byte[] secret, int period, long time, int digits, HashAlgorithm algorithm) {
        return TOTP_RFC6238(secret, period, time, digits, algorithm, 0);
    }

    /** Computes the TOTP for the given unix time; {@code offset} shifts the time step. */
    public static int TOTP_RFC6238(byte[] secret, int period, long time, int digits, HashAlgorithm algorithm, int offset) {
        InputPolicy.tokenParameters(period, digits, 0);
        int fullToken = TOTP(secret, period, time, algorithm, offset);
        int div = (int) Math.pow(10, digits);

        return fullToken % div;
    }

    /** Returns the current TOTP as a zero-padded string. */
    public static String TOTP_RFC6238(byte[] secret, int period, int digits, HashAlgorithm algorithm, int offset) {
        return Tools.formatTokenString(TOTP_RFC6238(secret, period, System.currentTimeMillis() / 1000, digits, algorithm, offset), digits);
    }

    /** Steam Guard variant of TOTP, mapped onto Steam's custom alphabet. */
    public static String TOTP_Steam(byte[] secret, int period, int digits, HashAlgorithm algorithm, int offset) {
        int fullToken = TOTP(secret, period, System.currentTimeMillis() / 1000, algorithm, offset);

        StringBuilder tokenBuilder = new StringBuilder();

        for (int i = 0; i < digits; i++) {
            tokenBuilder.append(STEAMCHARS[fullToken % STEAMCHARS.length]);
            fullToken /= STEAMCHARS.length;
        }

        return tokenBuilder.toString();
    }

    /** Returns the HOTP for the given counter as a zero-padded string. */
    public static String HOTP(byte[] secret, long counter, int digits, HashAlgorithm algorithm) {
        InputPolicy.tokenParameters(TOTP_DEFAULT_PERIOD, digits, counter);
        int fullToken = HOTP(secret, counter, algorithm);
        int div = (int) Math.pow(10, digits);

        return Tools.formatTokenString(fullToken % div, digits);
    }

    /** The raw (untruncated) TOTP for the given time step. */
    private static int TOTP(byte[] key, int period, long time, HashAlgorithm algorithm, int offset) {
        return HOTP(key, (time / period) + offset, algorithm);
    }

    /** The raw (untruncated) HOTP for the given counter. */
    private static int HOTP(byte[] key, long counter, HashAlgorithm algorithm)
    {
        int r = 0;

        try {
            byte[] data = ByteBuffer.allocate(8).putLong(counter).array();
            byte[] hash = generateHash(algorithm, key, data);

            int offset = hash[hash.length - 1] & 0xF;

            int binary = (hash[offset] & 0x7F) << 0x18;
            binary |= (hash[offset + 1] & 0xFF) << 0x10;
            binary |= (hash[offset + 2] & 0xFF) << 0x08;
            binary |= (hash[offset + 3] & 0xFF);

            r = binary;
        } catch (Exception e) {
            throw new IllegalStateException("OTP calculation failed");
        }

        return r;
    }

    /** Legacy mOTP: first 6 hex digits of MD5(epoch/10 + offset + secret + PIN). */
    public static String MOTP(String PIN, String secret, long epoch, int offset)
    {
        String epochText = String.valueOf((epoch / 10) + offset);
        String hashText = epochText + secret + PIN;
        String otp = "";

        try {
            // Create MD5 Hash
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(hashText.getBytes());
            byte[] messageDigest = digest.digest();

            // Create Hex String
            String hexString = new String(Hex.encodeHex(messageDigest));
            otp = hexString.substring(0, 6);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        return otp;
    }
}
