// Sandbox-only stand-in, see ../Zxing.kt.
package com.google.zxing.qrcode

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.ReaderException
import com.google.zxing.Result

/**
 * Replays [script]: each decode call takes the next outcome (a [Result] to return or a
 * [ReaderException] to throw) and records the bitmap and a copy of the hints it was given. An
 * empty script behaves like an image without a code (`NotFoundException`).
 */
class QRCodeReader {
    @Throws(ReaderException::class)
    fun decode(image: BinaryBitmap, hints: Map<DecodeHintType, *>?): Result {
        calls.add(image to (hints?.let { LinkedHashMap(it) } ?: emptyMap<DecodeHintType, Any?>()))
        val outcome = script.poll() ?: NotFoundException.getNotFoundInstance()
        if (outcome is ReaderException) throw outcome
        return outcome as Result
    }

    companion object {
        @JvmField
        val script: java.util.ArrayDeque<Any> = java.util.ArrayDeque()

        @JvmField
        val calls: MutableList<Pair<BinaryBitmap, Map<DecodeHintType, Any?>>> = ArrayList()
    }
}
