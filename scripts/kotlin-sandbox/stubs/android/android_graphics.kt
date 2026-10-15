// Sandbox-only stand-ins, see android_content.kt.
package android.graphics

abstract class ColorFilter

class Canvas(bitmap: Bitmap? = null) {
    fun setBitmap(bitmap: Bitmap?) {}
    fun drawColor(color: Int) {}
    fun drawText(text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: Paint) {}
}

class PorterDuffColorFilter(color: Int, mode: PorterDuff.Mode) : ColorFilter()

class PorterDuff private constructor() {
    enum class Mode { CLEAR, SRC, DST, SRC_OVER, DST_OVER, SRC_IN, DST_IN, SRC_OUT, DST_OUT, SRC_ATOP, DST_ATOP, XOR, DARKEN, LIGHTEN, MULTIPLY, SCREEN, ADD, OVERLAY }
}

/** Type-only stand-ins for the letter-tile/bitmap surface the resolved Java sources mention. */
class Bitmap {
    enum class Config { ARGB_8888, RGB_565, ALPHA_8 }

    fun getWidth(): Int = 1
    fun getHeight(): Int = 1
    fun recycle() {}

    companion object {
        @JvmStatic
        fun createBitmap(width: Int, height: Int, config: Config?): Bitmap = Bitmap()
    }
}

object BitmapFactory {
    @JvmStatic
    fun decodeResource(res: android.content.res.Resources?, id: Int): Bitmap? = null

    @JvmStatic
    fun decodeByteArray(data: ByteArray?, offset: Int, length: Int): Bitmap? = null
}

open class Paint {
    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Align { LEFT, CENTER, RIGHT }

    var color: Int = 0
    var textSize: Float = 0f
    var isAntiAlias: Boolean = false

    fun getTextBounds(text: CharArray, index: Int, count: Int, bounds: Rect) {}
    fun getTextWidths(text: String?, widths: FloatArray): Int = 0
    fun setStyle(style: Style?) {}
    fun setTextAlign(align: Align?) {}
    fun measureText(text: String?): Float = 0f
    fun setTypeface(typeface: Typeface?) {}
}

class Typeface private constructor() {
    companion object {
        const val BOLD = 1
        @JvmStatic
        val DEFAULT: Typeface = Typeface()

        @JvmStatic
        fun create(family: String?, style: Int): Typeface = Typeface()
    }
}

class Rect {
    var left: Int = 0
    var top: Int = 0
    var right: Int = 0
    var bottom: Int = 0
    fun width(): Int = right - left
    fun height(): Int = bottom - top
}

object Color {
    const val WHITE: Int = -1
    const val BLACK: Int = -16777216
}
