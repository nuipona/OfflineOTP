package org.shadowice.flocke.andotp.Utilities;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class EncryptionSecurityTest {
    private SecretKey key(int value) {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, (byte) value);
        return new SecretKeySpec(bytes, "AES");
    }

    @Test public void emptyAndUnicodePlaintextRoundTrip() throws Exception {
        for (String plain : new String[] {"", "account-Å-☃-日本語"}) {
            byte[] bytes = plain.getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = EncryptionHelper.encrypt(key(1), bytes);
            assertEquals(bytes.length + 12 + 16, encrypted.length);
            assertArrayEquals(bytes, EncryptionHelper.decrypt(key(1), encrypted));
        }
    }

    @Test public void wrongKeyAndModifiedNonceCiphertextTagFailAuthentication() throws Exception {
        SecretKey key = key(1);
        byte[] encrypted = EncryptionHelper.encrypt(key, "synthetic-secret".getBytes(StandardCharsets.UTF_8));
        assertThrows(BadPaddingException.class, () -> EncryptionHelper.decrypt(key(2), encrypted));
        for (int position : new int[] {0, 12, encrypted.length - 1}) {
            byte[] modified = encrypted.clone();
            modified[position] ^= 1;
            assertThrows(BadPaddingException.class, () -> EncryptionHelper.decrypt(key, modified));
        }
    }

    @Test public void rejectsNullAndAllShortFrames() {
        SecretKey key = key(1);
        assertThrows(IllegalArgumentException.class, () -> EncryptionHelper.decrypt(key, (byte[]) null));
        for (int length = 0; length < 28; length++) {
            byte[] frame = new byte[length];
            assertThrows(IllegalArgumentException.class, () -> EncryptionHelper.decrypt(key, frame));
        }
    }

    @Test public void repeatedEncryptionUsesDifferentNonces() throws Exception {
        SecretKey key = key(1);
        byte[] plain = "synthetic-secret".getBytes(StandardCharsets.UTF_8);
        byte[] first = EncryptionHelper.encrypt(key, plain);
        byte[] second = EncryptionHelper.encrypt(key, plain);
        // Collision probability for independent 96-bit nonces is negligible.
        assertFalse(Arrays.equals(Arrays.copyOf(first, 12), Arrays.copyOf(second, 12)));
    }
}
