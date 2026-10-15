// Sandbox-only stand-in for commons-codec's Hex (lowercase hex, like the real class).
package org.apache.commons.codec.binary

object Hex {
    private const val DIGITS = "0123456789abcdef"

    @JvmStatic
    fun encodeHex(data: ByteArray): CharArray {
        val out = CharArray(data.size * 2)
        for (i in data.indices) {
            val value = data[i].toInt() and 0xFF
            out[i * 2] = DIGITS[value ushr 4]
            out[i * 2 + 1] = DIGITS[value and 0x0F]
        }
        return out
    }
}
