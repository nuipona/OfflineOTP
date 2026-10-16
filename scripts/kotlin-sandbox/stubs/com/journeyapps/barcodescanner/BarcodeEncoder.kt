// Sandbox-only stand-ins, see ../../../android/android_content.kt.
package com.journeyapps.barcodescanner

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat

/**
 * The QR code encoder of the entry popup. The stand-in returns an empty bitmap; no barcode is
 * rendered.
 */
open class BarcodeEncoder {
    @Throws(Exception::class)
    open fun encodeBitmap(contents: String, format: BarcodeFormat, width: Int, height: Int): Bitmap =
        Bitmap(width, height)
}
