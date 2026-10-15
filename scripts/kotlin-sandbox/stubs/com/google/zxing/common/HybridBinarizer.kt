// Sandbox-only stand-in, see ../Zxing.kt. No binarization happens here.
package com.google.zxing.common

import com.google.zxing.Binarizer
import com.google.zxing.LuminanceSource

class HybridBinarizer(source: LuminanceSource) : Binarizer(source)
