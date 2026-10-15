// Sandbox-only stand-ins for the zxing core surface the QR-image helper uses, see
// ../../../android/android_content.kt. They are NOT zxing: nothing here binarizes or decodes an
// image. The reader in ./qrcode replays scripted outcomes so the checks can drive every branch.
package com.google.zxing

enum class BarcodeFormat { QR_CODE }

enum class DecodeHintType { POSSIBLE_FORMATS, TRY_HARDER }

abstract class LuminanceSource protected constructor(private val width: Int, private val height: Int) {
    fun getWidth(): Int = width
    fun getHeight(): Int = height
}

/** Keeps the pixels it was given so the checks can compare them with the decoded bitmap. */
class RGBLuminanceSource(width: Int, height: Int, val pixels: IntArray) : LuminanceSource(width, height)

abstract class Binarizer protected constructor(private val source: LuminanceSource) {
    fun getLuminanceSource(): LuminanceSource = source
}

class BinaryBitmap(private val binarizer: Binarizer) {
    /** Sandbox-only accessor: the real class exposes dimensions, not its binarizer. */
    fun binarizerForChecks(): Binarizer = binarizer
}

class Result(private val text: String?) {
    fun getText(): String? = text
}

/** As in zxing, the reader failures are checked exceptions without a captured stack trace. */
abstract class ReaderException internal constructor() : Exception() {
    final override fun fillInStackTrace(): Throwable = this
}

class NotFoundException private constructor() : ReaderException() {
    companion object {
        private val INSTANCE = NotFoundException()

        @JvmStatic
        fun getNotFoundInstance(): NotFoundException = INSTANCE
    }
}

class ChecksumException private constructor() : ReaderException() {
    companion object {
        private val INSTANCE = ChecksumException()

        @JvmStatic
        fun getChecksumInstance(): ChecksumException = INSTANCE
    }
}

class FormatException private constructor() : ReaderException() {
    companion object {
        private val INSTANCE = FormatException()

        @JvmStatic
        fun getFormatInstance(): FormatException = INSTANCE
    }
}
