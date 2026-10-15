package org.shadowice.flocke.andotp.Tasks

import android.content.Context
import android.util.Base64
import android.util.Log

import org.apache.commons.codec.binary.Hex
import org.apache.commons.codec.digest.DigestUtils
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper.PBKDF2Credentials
import org.shadowice.flocke.andotp.Utilities.InputPolicy
import org.shadowice.flocke.andotp.Utilities.Settings

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.spec.InvalidKeySpecException

import java.util.Arrays

/** Verifies a password/PIN and returns the database key, upgrading legacy hashes when needed. */
/* The Java class was non-final and stayed extendable; Kotlin classes are final by default. */
open class AuthenticationTask(context: Context, private val isAuthUpgrade: Boolean,
                              private val existingAuthCredentials: String, private val plainPassword: String?) :
        UiBasedBackgroundTask<AuthenticationOutcome>(AuthenticationOutcome.Rejected(AuthenticationError.DENIED)) {
    /*
     * The Java code dereferenced existingAuthCredentials without a null check (Base64.decode and
     * the upgrade guard), and every caller passes a settings-derived string, so it stays a
     * required non-null constructor parameter: a null now fails fast with a clear exception
     * instead of surfacing later. plainPassword is explicitly null-checked below and stays
     * nullable. Only the application context is kept.
     */
    private val settings: Settings = Settings(context.getApplicationContext())

    protected override fun discardResult(result: AuthenticationOutcome) {
        if (result is AuthenticationOutcome.Verified) result.close()
    }

    protected override fun doInBackground(): AuthenticationOutcome {
        if (isCanceled() || plainPassword == null || plainPassword.length > InputPolicy.MAX_TEXT_CHARS) return AuthenticationOutcome.Rejected(AuthenticationError.DENIED)
        return if (isAuthUpgrade) {
            upgradeAuthentication()
        } else {
            confirmAuthentication()
        }
    }

    /** Upgrades a legacy SHA-256 hash to PBKDF2 credentials and returns the new key. */
    private fun upgradeAuthentication(): AuthenticationOutcome {
        val hashedPassword = String(Hex.encodeHex(DigestUtils.sha256(plainPassword)))
        if (hashedPassword != existingAuthCredentials)
            return AuthenticationOutcome.Rejected(AuthenticationError.DENIED)

        val key = settings.setAuthCredentials(plainPassword) {
            !isCanceled()
                    && settings.getAuthCredentials().isEmpty()
                    && existingAuthCredentials == settings.getOldCredentials(settings.getAuthMethod())
        }

        if (key == null)
            return AuthenticationOutcome.Rejected(AuthenticationError.LEGACY_MIGRATION_FAILED)
        else {
            try {
                return AuthenticationOutcome.verified(key, taskGeneration())
            } finally {
                Arrays.fill(key, 0.toByte())
            }
        }
    }

    /** Checks the entered password/PIN against the stored PBKDF2 credentials. */
    private fun confirmAuthentication(): AuthenticationOutcome {
        var credentials: PBKDF2Credentials? = null
        var passwordArray: ByteArray? = null
        try {
            val derived = EncryptionHelper.generatePBKDF2Credentials(plainPassword, settings.getSalt(), settings.getIterations())
            credentials = derived
            passwordArray = Base64.decode(existingAuthCredentials, Base64.URL_SAFE)
            if (MessageDigest.isEqual(passwordArray, derived.password)) return AuthenticationOutcome.verified(derived.key, taskGeneration())
            return AuthenticationOutcome.Rejected(AuthenticationError.DENIED)
        } catch (error: NoSuchAlgorithmException) {
            Log.e("AuthenticationTask", "Problem decoding password")
            return AuthenticationOutcome.Rejected(AuthenticationError.INVALID_CONFIGURATION)
        } catch (error: InvalidKeySpecException) {
            Log.e("AuthenticationTask", "Problem decoding password")
            return AuthenticationOutcome.Rejected(AuthenticationError.INVALID_CONFIGURATION)
        } catch (error: IllegalArgumentException) {
            Log.e("AuthenticationTask", "Problem decoding password")
            return AuthenticationOutcome.Rejected(AuthenticationError.INVALID_CONFIGURATION)
        } catch (error: IllegalStateException) {
            Log.e("AuthenticationTask", "Problem decoding password")
            return AuthenticationOutcome.Rejected(AuthenticationError.INVALID_CONFIGURATION)
        } finally {
            credentials?.let {
                Arrays.fill(it.password, 0.toByte())
                Arrays.fill(it.key, 0.toByte())
            }
            passwordArray?.let { Arrays.fill(it, 0.toByte()) }
        }
    }
}
