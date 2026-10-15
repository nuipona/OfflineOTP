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

import android.content.Context;

import org.shadowice.flocke.andotp.Database.Entry;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Crypto helpers: AES/GCM encryption, PBKDF2 password derivation and the wrapped-key storage
 * used in KeyStore mode.
 */
public class EncryptionHelper {
    /** The two halves of a derived credential: the authentication password and the encryption key. */
    public static class PBKDF2Credentials {
        public byte[] password;
        public byte[] key;
    }

    /** Error codes of {@link #tryEncryptionChange}. */
    public enum EncryptionChangeResult {
        SUCCESS, CHANGE_FAILURE, BACKUP_FAILURE, MISSING_NEW_KEY, DECRYPTION_FAILED, TASK_CREATION_FAILED
    }

    /** Called once the database has been re-encrypted with the new key. */
    public interface EncryptionChangeCallback {
        void onSuccessfulEncryptionChange(Constants.EncryptionType newEncryptionType, SecretKey newEncryptionKey);
    }

    /** Re-encrypts only authenticated data; staged settings/file commit survives process death. */
    public static EncryptionChangeResult tryEncryptionChange(Context context, SecretKey oldKey, Constants.EncryptionType newType, byte[] newKey, EncryptionChangeCallback callback) {
        return tryEncryptionChange(context, oldKey, newType, newKey, callback, () -> true);
    }

    public static EncryptionChangeResult tryEncryptionChange(Context context, SecretKey oldKey, Constants.EncryptionType newType, byte[] newKey, EncryptionChangeCallback callback, java.util.function.BooleanSupplier permitted) {
        synchronized (DatabaseHelper.DatabaseFileLock) {
            if (!permitted.getAsBoolean() || oldKey == null || VaultSession.getKey() != oldKey) return EncryptionChangeResult.DECRYPTION_FAILED;
            VaultReadResult loaded = DatabaseHelper.readVault(context, oldKey);
            if (!(loaded instanceof VaultReadResult.Loaded)) return EncryptionChangeResult.DECRYPTION_FAILED;
            ((VaultReadResult.Loaded) loaded).close();
            SecretKey key;
            if (newType == Constants.EncryptionType.KEYSTORE) key = KeyStoreHelper.createEncryptionKey(context);
            else if (newKey != null && (newKey.length == 16 || newKey.length == 24 || newKey.length == 32)) key = generateSymmetricKey(newKey);
            else return EncryptionChangeResult.MISSING_NEW_KEY;
            if (key == null) return EncryptionChangeResult.MISSING_NEW_KEY;
            Settings settings = new Settings(context);
            long generation = VaultSession.generation();
            VaultWriteResult write = DatabaseHelper.changeVaultEncryption(context, oldKey, key, false, id -> {
                if (!permitted.getAsBoolean() || !VaultSession.isCurrent(generation)) return VaultCommitDecision.CANCELED;
                return VaultCommitDecision.fromCommit(settings.commitVaultSettings(id, newType, null, null, false));
            });
            if (!(write instanceof VaultWriteResult.Saved)) return EncryptionChangeResult.CHANGE_FAILURE;
            if (callback != null) callback.onSuccessfulEncryptionChange(newType, key);
            return EncryptionChangeResult.SUCCESS;
        }
    }

    /** Measures PBKDF2 speed and scales the iteration count to the target auth time (never below the minimum). */
    public static int benchmarkIterations(String password, byte[] salt) {
        try {
            SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(Constants.PBKDF2_ALGORITHM);
            char[] passwordChars = password.toCharArray();
            PBEKeySpec keySpec;
            try { keySpec = new PBEKeySpec(passwordChars, salt, Constants.PBKDF2_BENCHMARK_ITERATIONS, Constants.PBKDF2_LENGTH); }
            finally { Arrays.fill(passwordChars, '\0'); }

            long startTime = System.currentTimeMillis();
            try { secretKeyFactory.generateSecret(keySpec); }
            finally { keySpec.clearPassword(); }
            long finishTime = System.currentTimeMillis();

            long timeDiff = Math.max(1, finishTime - startTime);
            int scaledIterationTarget = (int) (((double) Constants.PBKDF2_BENCHMARK_ITERATIONS / (double) timeDiff) * Constants.PBKDF2_TARGET_AUTH_TIME);

            return Math.min(2000000, Math.max(scaledIterationTarget, Constants.PBKDF2_MIN_AUTH_ITERATIONS));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            e.printStackTrace();
            return Constants.PBKDF2_MIN_AUTH_ITERATIONS;
        }
    }

    /** Random iteration count for new encrypted backups, so not all backups share one value. */
    public static int generateRandomIterations() {
        Random rand = new Random();
        return rand.nextInt((Constants.PBKDF2_MAX_BACKUP_ITERATIONS - Constants.PBKDF2_MIN_BACKUP_ITERATIONS) + 1) + Constants.PBKDF2_MIN_BACKUP_ITERATIONS;
    }

    /** Returns {@code length} random bytes from {@link SecureRandom}. */
    public static byte[] generateRandom(int length) {
        final byte[] raw = new byte[length];
        new SecureRandom().nextBytes(raw);

        return raw;
    }

    /** Derives the PBKDF2 output and splits it into the auth password and the encryption key. */
    public static PBKDF2Credentials generatePBKDF2Credentials(String password, byte[] salt, int iter)
        throws NoSuchAlgorithmException, InvalidKeySpecException {
        if (password == null || password.length() > InputPolicy.MAX_TEXT_CHARS || salt == null
                || salt.length < 8 || iter < 1 || iter > 20000000) throw new IllegalArgumentException("Invalid credential parameters");
        SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(Constants.PBKDF2_ALGORITHM);
        char[] passwordChars = password.toCharArray();
        PBEKeySpec keySpec;
        try { keySpec = new PBEKeySpec(passwordChars, salt, iter, Constants.PBKDF2_LENGTH); }
        finally { Arrays.fill(passwordChars, '\0'); }

        byte[] array;
        try { array = secretKeyFactory.generateSecret(keySpec).getEncoded(); }
        finally { keySpec.clearPassword(); }

        int halfPoint = array.length / 2;

        PBKDF2Credentials credentials = new PBKDF2Credentials();
        credentials.password = Arrays.copyOfRange(array, halfPoint, array.length);
        credentials.key = Arrays.copyOfRange(array, 0, halfPoint);

        Arrays.fill(array, (byte) 0);
        return credentials;
    }

    /** Wraps raw bytes in an AES {@link SecretKey}. */
    public static SecretKey generateSymmetricKey(byte[] data) {
        return new SecretKeySpec(data, 0, data.length, "AES");
    }

    /** Derives the AES key used for password-encrypted backups. */
    public static SecretKey generateSymmetricKeyPBKDF2(String password, int iter, byte[] salt)
        throws NoSuchAlgorithmException, InvalidKeySpecException {
        if (password == null || password.length() > InputPolicy.MAX_TEXT_CHARS || salt == null
                || salt.length < 8 || iter < 1 || iter > 20000000) throw new IllegalArgumentException("Invalid credential parameters");
        SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(Constants.PBKDF2_ALGORITHM);
        char[] passwordChars = password.toCharArray();
        PBEKeySpec keySpec;
        try { keySpec = new PBEKeySpec(passwordChars, salt, iter, Constants.PBKDF2_LENGTH); }
        finally { Arrays.fill(passwordChars, '\0'); }

        byte[] raw;
        try { raw = secretKeyFactory.generateSecret(keySpec).getEncoded(); }
        finally { keySpec.clearPassword(); }
        try { return new SecretKeySpec(raw, "AES"); }
        finally { Arrays.fill(raw, (byte) 0); }
    }

    /** Legacy key derivation (plain SHA-256 of the password) for old-format encrypted backups. */
    public static SecretKey generateSymmetricKeyFromPassword(String password)
            throws NoSuchAlgorithmException {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");

        return new SecretKeySpec(sha.digest(password.getBytes(StandardCharsets.UTF_8)), "AES");
    }

    /** Encrypts with an explicit nonce. */
    public static byte[] encrypt(SecretKey secretKey, IvParameterSpec iv, byte[] plainText)
            throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, BadPaddingException, IllegalBlockSizeException {
        Cipher cipher = Cipher.getInstance(Constants.ALGORITHM_SYMMETRIC);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, iv);

        return cipher.doFinal(plainText);
    }

    /** Encrypts and prefixes the random GCM nonce, as used in the database and backup formats. */
    public static byte[] encrypt(SecretKey secretKey, byte[] plaintext)
            throws NoSuchPaddingException, BadPaddingException, InvalidKeyException, NoSuchAlgorithmException, IllegalBlockSizeException, InvalidAlgorithmParameterException {
        final byte[] iv = new byte[Constants.ENCRYPTION_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        byte[] cipherText = encrypt(secretKey, new IvParameterSpec(iv), plaintext);

        byte[] combined = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

        return combined;
    }

    /** RSA-encrypts a small secret (e.g. the stored backup password) with a KeyStore public key. */
    public static byte[] encrypt(PublicKey publicKey, byte[] plaintext)
            throws NoSuchPaddingException, BadPaddingException, InvalidKeyException, NoSuchAlgorithmException, IllegalBlockSizeException {
        Cipher cipher = Cipher.getInstance(Constants.ALGORITHM_ASYMMETRIC);
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);

        return cipher.doFinal(plaintext);
    }

    /** Decrypts with an explicit nonce. */
    public static byte[] decrypt(SecretKey secretKey, IvParameterSpec iv, byte[] cipherText)
            throws NoSuchPaddingException, InvalidKeyException, NoSuchAlgorithmException, IllegalBlockSizeException, BadPaddingException, InvalidAlgorithmParameterException {
        Cipher cipher = Cipher.getInstance(Constants.ALGORITHM_SYMMETRIC);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, iv);

        return cipher.doFinal(cipherText);
    }

    /** Splits the nonce off the front of the blob and decrypts the remaining ciphertext. */
    public static byte[] decrypt(SecretKey secretKey, byte[] cipherText)
            throws NoSuchPaddingException, InvalidKeyException, NoSuchAlgorithmException, IllegalBlockSizeException, BadPaddingException, InvalidAlgorithmParameterException {
        // AES-GCM always contains a nonce and a 128-bit authentication tag,
        // even when the plaintext is empty. Reject framing before provider work.
        if (cipherText == null || cipherText.length < Constants.ENCRYPTION_IV_LENGTH + 16) {
            throw new IllegalArgumentException("Truncated AES-GCM data");
        }
        byte[] iv = Arrays.copyOfRange(cipherText, 0, Constants.ENCRYPTION_IV_LENGTH);
        byte[] encrypted = Arrays.copyOfRange(cipherText, Constants.ENCRYPTION_IV_LENGTH, cipherText.length);

        return decrypt(secretKey, new IvParameterSpec(iv), encrypted);
    }

    /** RSA decryption, the counterpart of {@link #encrypt(PublicKey, byte[])}. */
    public static byte[] decrypt(PrivateKey privateKey, byte[] cipherText)
            throws NoSuchPaddingException, InvalidKeyException, NoSuchAlgorithmException, IllegalBlockSizeException, BadPaddingException {
        Cipher cipher = Cipher.getInstance(Constants.ALGORITHM_ASYMMETRIC);
        cipher.init(Cipher.DECRYPT_MODE, privateKey);

        return cipher.doFinal(cipherText);
    }

    /**
     * Load our symmetric secret key.
     * The symmetric secret key is stored securely on disk by wrapping
     * it with a public/private key pair, possibly backed by hardware.
     */
    public static SecretKey loadOrGenerateWrappedKey(File keyFile, KeyPair keyPair)
            throws GeneralSecurityException, IOException {
        final SecretKeyWrapper wrapper = new SecretKeyWrapper(keyPair);

        // Generate secret key if none exists
        if (!keyFile.exists()) {
            final byte[] raw = EncryptionHelper.generateRandom(Constants.ENCRYPTION_KEY_LENGTH);

            final SecretKey key = new SecretKeySpec(raw, "AES");
            final byte[] wrapped = wrapper.wrap(key);


            FileHelper.writeBytesToFile(keyFile, wrapped);
        }

        // Even if we just generated the key, always read it back to ensure we
        // can read it successfully.
        final byte[] wrapped = FileHelper.readFileToBytes(keyFile);

        return wrapper.unwrap(wrapped);
    }
}
