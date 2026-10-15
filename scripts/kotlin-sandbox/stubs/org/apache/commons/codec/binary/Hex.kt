// Sandbox-only stand-in for commons-codec's Hex (lowercase encode, like the real class).
//
// Replication, not an inert surface: the entry model's mOTP secret check calls decodeHex and treats
// a thrown exception as "not a valid secret". The decoder mirrors the real contract that matters
// there: an empty string decodes to nothing, anything else must be an even number of hex digits.
// The real DecoderException type is not replicated; callers catch Exception.

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

    @JvmStatic
    fun decodeHex(data: String?): CharArray =
        decodeHex(requireNotNull(data).toCharArray())

    @JvmStatic
    fun decodeHex(data: CharArray): CharArray {
        if (data.size % 2 != 0) throw IllegalArgumentException("Odd number of characters")
        val out = CharArray(data.size / 2)
        for (i in data.indices step 2) {
            val high = digit(data[i])
            val low = digit(data[i + 1])
            out[i / 2] = ((high shl 4) or low).toChar()
        }
        return out
    }

    private fun digit(character: Char): Int = when (character) {
        in '0'..'9' -> character - '0'
        in 'a'..'f' -> character - 'a' + 10
        in 'A'..'F' -> character - 'A' + 10
        else -> throw IllegalArgumentException("Illegal hexadecimal character: $character")
    }
}
