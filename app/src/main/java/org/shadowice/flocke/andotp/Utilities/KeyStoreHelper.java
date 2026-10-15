/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
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
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.shadowice.flocke.andotp.R;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Calendar;
import java.util.GregorianCalendar;

import javax.crypto.SecretKey;
import javax.security.auth.x500.X500Principal;

/** Manages the app's Android KeyStore entries: the RSA pair that wraps the AES key in KeyStore mode. */
public class KeyStoreHelper {

    /** Deletes the wrapped key file and the KeyStore entry (PANIC response and "clear keystore"). */
    public static void wipeKeys(Context context) {
        VaultSession.lock(SessionLockReason.RESET);
        synchronized (DatabaseHelper.DatabaseFileLock) {
            FileHelper.delete(new File(context.getFilesDir(), Constants.FILENAME_ENCRYPTED_KEY));
            try {
                KeyStore store = KeyStore.getInstance("AndroidKeyStore");
                store.load(null);
                for (String alias : new String[] {Constants.KEYSTORE_ALIAS_WRAPPING, Constants.KEYSTORE_ALIAS_PASSWORD}) {
                    if (store.containsAlias(alias)) store.deleteEntry(alias);
                }
            } catch (GeneralSecurityException | IOException | RuntimeException error) { /* No replacement keys on error. */ }
        }
    }

    public static KeyPair loadAsymmetricKeyPair(Context context, String alias) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(alias)) return null;
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) store.getEntry(alias, null);
        return entry == null ? null : new KeyPair(entry.getCertificate().getPublicKey(), entry.getPrivateKey());
    }

    /** Returns the RSA key pair stored under the alias, generating it first if needed. */
    public static KeyPair loadOrGenerateAsymmetricKeyPair(Context context, String alias)
            throws GeneralSecurityException, IOException {
        final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);

        if (! keyStore.containsAlias(alias)) {
            final Calendar start = new GregorianCalendar();
            final Calendar end = new GregorianCalendar();
            end.add(Calendar.YEAR, 100);

            AlgorithmParameterSpec spec = new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setCertificateSubject(new X500Principal("CN=" + alias))
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(start.getTime())
                    .setCertificateNotAfter(end.getTime())
                    .build();

            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");

            gen.initialize(spec);
            gen.generateKeyPair();
        }

        final KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(alias, null);

        if (entry != null)
            return new KeyPair(entry.getCertificate().getPublicKey(), entry.getPrivateKey());
        else
            return null;
    }

    /** Read-only key lookup. Missing files/aliases never generate replacements. */
    public static SecretKey loadEncryptionKeyFromKeyStore(Context context, boolean failSilent) {
        synchronized (DatabaseHelper.DatabaseFileLock) {
            try {
                File wrapped = new File(context.getFilesDir(), Constants.FILENAME_ENCRYPTED_KEY);
                if (!FileHelper.exists(wrapped)) return null;
                KeyPair pair = loadAsymmetricKeyPair(context, Constants.KEYSTORE_ALIAS_WRAPPING);
                if (pair == null) return null;
                return new SecretKeyWrapper(pair).unwrap(FileHelper.readFileToBytes(wrapped));
            } catch (GeneralSecurityException | IOException | RuntimeException error) { return null; }
        }
    }

    /** Only explicit new-vault/reset/migration paths may call this. */
    public static SecretKey createEncryptionKey(Context context) {
        synchronized (DatabaseHelper.DatabaseFileLock) {
            File wrapped = new File(context.getFilesDir(), Constants.FILENAME_ENCRYPTED_KEY);
            if (FileHelper.exists(wrapped)) return loadEncryptionKeyFromKeyStore(context, true);
            try {
                KeyPair pair = loadOrGenerateAsymmetricKeyPair(context, Constants.KEYSTORE_ALIAS_WRAPPING);
                if (pair == null) return null;
                return EncryptionHelper.loadOrGenerateWrappedKey(wrapped, pair);
            } catch (GeneralSecurityException | IOException | RuntimeException error) { return null; }
        }
    }
}
