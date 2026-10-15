// Sandbox-only stand-ins, see android_content.kt.
package android.security.keystore

import java.math.BigInteger
import java.security.spec.AlgorithmParameterSpec
import java.util.Date

import javax.security.auth.x500.X500Principal

/** Constant-only stand-in for the key purposes/paddings the keystore helper builds with. */
object KeyProperties {
    const val PURPOSE_ENCRYPT: Int = 1
    const val PURPOSE_DECRYPT: Int = 2
    const val ENCRYPTION_PADDING_RSA_PKCS1: String = "RSA_PKCS1"
}

/** Builder-only stand-in; the sandbox never generates a real AndroidKeyStore key. */
class KeyGenParameterSpec private constructor() : AlgorithmParameterSpec {
    class Builder(keyStoreAlias: String?, purposes: Int) {
        fun setCertificateSubject(subject: X500Principal?): Builder = this
        fun setEncryptionPaddings(vararg paddings: String?): Builder = this
        fun setCertificateSerialNumber(serialNumber: BigInteger?): Builder = this
        fun setCertificateNotBefore(date: Date?): Builder = this
        fun setCertificateNotAfter(date: Date?): Builder = this
        fun setKeySize(keySize: Int): Builder = this
        fun build(): KeyGenParameterSpec = KeyGenParameterSpec()
    }
}
