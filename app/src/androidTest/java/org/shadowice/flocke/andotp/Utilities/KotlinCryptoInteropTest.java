package org.shadowice.flocke.andotp.Utilities;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;

/**
 * Real-platform characterization of the Kotlin EncryptionHelper/KeyStoreHelper and their Java call
 * sites. The desktop sandbox executes the platform-free crypto (PBKDF2 split, RSA helpers, the
 * pre-provider framing guard) and reflects KeyStoreHelper, but it has no AndroidKeyStore and its
 * JDK rejects the unchanged Android GCM nonce type, so the keystore round trip and the AES-GCM
 * provider path need a device. This test uses a dedicated synthetic alias and the app's private
 * files directory, and it wipes both again afterwards; it never touches the vault or a real
 * account. Run on disposable emulators.
 */
@RunWith(AndroidJUnit4.class)
public class KotlinCryptoInteropTest {
    private static final String TEST_ALIAS = "kotlin-crypto-interop";
    private static final String TEST_KEY_FILE = "kotlin-crypto-interop.key";

    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @After public void cleanup() {
        new File(context.getFilesDir(), TEST_KEY_FILE).delete();
        KeyStoreHelper.wipeKeys(context);
    }

    @Test public void aesGcmFramingAndCredentialsMatchTheJavaContract() throws Exception {
        SecretKey key = EncryptionHelper.generateSymmetricKey(new byte[16]);
        assertEquals("AES", key.getAlgorithm());
        assertEquals(16, key.getEncoded().length);

        byte[] plain = "account-Å-☃-日本語".getBytes(StandardCharsets.UTF_8);
        byte[] frame = EncryptionHelper.encrypt(key, plain);
        assertEquals(plain.length + Constants.ENCRYPTION_IV_LENGTH + 16, frame.length);
        assertArrayEquals(plain, EncryptionHelper.decrypt(key, frame));
        assertArrayEquals(new byte[0], EncryptionHelper.decrypt(key, EncryptionHelper.encrypt(key, new byte[0])));

        byte[] otherFrame = EncryptionHelper.encrypt(key, plain);
        assertFalse(Arrays.equals(Arrays.copyOf(frame, 12), Arrays.copyOf(otherFrame, 12)));
        byte[] wrongKey = new byte[16];
        wrongKey[0] = 1;
        try {
            EncryptionHelper.decrypt(new SecretKeySpec(wrongKey, "AES"), frame);
            fail("a wrong key decrypted the frame");
        } catch (BadPaddingException expected) { /* GCM tag mismatch */ }
        assertThrows(BadPaddingException.class, () -> {
            byte[] modified = frame.clone();
            modified[modified.length - 1] ^= 1;
            EncryptionHelper.decrypt(key, modified);
        });
        assertThrows(IllegalArgumentException.class, () -> EncryptionHelper.decrypt(key, (byte[]) null));
        for (int length = 0; length < Constants.ENCRYPTION_IV_LENGTH + 16; length++) {
            final int shortLength = length;
            assertThrows(IllegalArgumentException.class, () -> EncryptionHelper.decrypt(key, new byte[shortLength]));
        }

        // The explicit-nonce overload the instrumentation vectors use.
        byte[] iv = EncryptionHelper.generateRandom(Constants.ENCRYPTION_IV_LENGTH);
        assertEquals(Constants.ENCRYPTION_IV_LENGTH, iv.length);
        assertEquals(16, EncryptionHelper.generateSymmetricKeyFromPassword("synthetic").getEncoded().length);
        SecretKey derivedBackupKey = EncryptionHelper.generateSymmetricKeyPBKDF2("synthetic-password", 1000,
                EncryptionHelper.generateRandom(Constants.PBKDF2_SALT_LENGTH));
        byte[] explicit = EncryptionHelper.encrypt(derivedBackupKey, new IvParameterSpec(iv), plain);
        assertArrayEquals(plain, EncryptionHelper.decrypt(derivedBackupKey, new IvParameterSpec(iv), explicit));

        EncryptionHelper.PBKDF2Credentials credentials =
                EncryptionHelper.generatePBKDF2Credentials("synthetic-password", EncryptionHelper.generateRandom(16), 1000);
        try {
            // Java keeps reading the public fields and can wipe them; both halves are ordinary arrays.
            assertEquals(16, credentials.password.length);
            assertEquals(16, credentials.key.length);
            assertFalse(Arrays.equals(credentials.password, credentials.key));
        } finally {
            Arrays.fill(credentials.password, (byte) 0);
            Arrays.fill(credentials.key, (byte) 0);
        }
        assertThrows(IllegalArgumentException.class,
                () -> EncryptionHelper.generatePBKDF2Credentials("synthetic-password", new byte[7], 1000));
        assertThrows(IllegalArgumentException.class,
                () -> EncryptionHelper.generatePBKDF2Credentials(null, new byte[16], 1000));

        int iterations = EncryptionHelper.generateRandomIterations();
        assertTrue("backup iterations out of range: " + iterations,
                iterations >= 140000 && iterations <= 160000);
        assertTrue(EncryptionHelper.benchmarkIterations("synthetic-password", new byte[16]) >= 30000);
    }

    @Test public void androidKeyStoreRoundTripAndJavaCallbackShapes() throws Exception {
        KeyStoreHelper.wipeKeys(context);
        assertNull(KeyStoreHelper.loadEncryptionKeyFromKeyStore(context, true));
        assertNull(KeyStoreHelper.loadAsymmetricKeyPair(context, TEST_ALIAS));

        // The real AndroidKeyStore path: generate the alias, then load the same pair back.
        KeyPair pair = KeyStoreHelper.loadOrGenerateAsymmetricKeyPair(context, TEST_ALIAS);
        assertNotNull(pair);
        KeyPair reloaded = KeyStoreHelper.loadAsymmetricKeyPair(context, TEST_ALIAS);
        assertNotNull(reloaded);
        assertArrayEquals(pair.getPublic().getEncoded(), reloaded.getPublic().getEncoded());

        // RSA wrap/unwrap of the wrapped-key file, then read it back from disk.
        File keyFile = new File(context.getFilesDir(), TEST_KEY_FILE);
        keyFile.delete();
        SecretKey created = EncryptionHelper.loadOrGenerateWrappedKey(keyFile, pair);
        assertEquals(Constants.ENCRYPTION_KEY_LENGTH, created.getEncoded().length);
        assertTrue(keyFile.exists());
        SecretKey reread = EncryptionHelper.loadOrGenerateWrappedKey(keyFile, pair);
        assertArrayEquals(created.getEncoded(), reread.getEncoded());
        assertArrayEquals(created.getEncoded(), new SecretKeyWrapper(pair).unwrap(readAll(keyFile)).getEncoded());

        // The vault-key entry points still work from Java.
        SecretKey vaultKey = KeyStoreHelper.createEncryptionKey(context);
        assertNotNull(vaultKey);
        SecretKey loaded = KeyStoreHelper.loadEncryptionKeyFromKeyStore(context, false);
        assertNotNull(loaded);
        assertArrayEquals(vaultKey.getEncoded(), loaded.getEncoded());

        // The Kotlin SAM callback stays implementable and callable from Java.
        final SecretKey[] received = new SecretKey[1];
        final Constants.EncryptionType[] receivedType = new Constants.EncryptionType[1];
        EncryptionHelper.EncryptionChangeCallback callback = (newType, newKey) -> {
            receivedType[0] = newType;
            received[0] = newKey;
        };
        callback.onSuccessfulEncryptionChange(Constants.EncryptionType.KEYSTORE, vaultKey);
        assertEquals(Constants.EncryptionType.KEYSTORE, receivedType[0]);
        assertEquals(vaultKey, received[0]);
        assertEquals(EncryptionHelper.EncryptionChangeResult.SUCCESS,
                EncryptionHelper.EncryptionChangeResult.valueOf("SUCCESS"));

        KeyStoreHelper.wipeKeys(context);
        assertNull(KeyStoreHelper.loadAsymmetricKeyPair(context, TEST_ALIAS));
        assertNull(KeyStoreHelper.loadEncryptionKeyFromKeyStore(context, true));
    }

    private static byte[] readAll(File file) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            int read = 0;
            while (read < bytes.length) {
                int count = input.read(bytes, read, bytes.length - read);
                if (count < 0) break;
                read += count;
            }
        }
        return bytes;
    }
}
