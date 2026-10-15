// Sandbox-only stand-in, see ../../../../../../android/android_content.kt.
package org.apache.commons.codec.binary

/**
 * Method-surface stand-in for the commons-codec Base32 the OTP parser round-trips secrets
 * through. Encoding is inert; the real library behaviour is a Gradle/device gate.
 */
class Base32 {
    fun decode(data: String?): ByteArray = ByteArray(0)
    fun decode(data: ByteArray?): ByteArray = ByteArray(0)
    fun encode(data: ByteArray?): ByteArray = ByteArray(0)
    fun encodeAsString(data: ByteArray?): String = ""
}
