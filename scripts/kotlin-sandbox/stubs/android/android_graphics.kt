// Sandbox-only stand-ins, see android_content.kt.
package android.graphics

abstract class ColorFilter

class Canvas

class PorterDuffColorFilter(color: Int, mode: PorterDuff.Mode) : ColorFilter()

class PorterDuff private constructor() {
    enum class Mode { CLEAR, SRC, DST, SRC_OVER, DST_OVER, SRC_IN, DST_IN, SRC_OUT, DST_OUT, SRC_ATOP, DST_ATOP, XOR, DARKEN, LIGHTEN, MULTIPLY, SCREEN, ADD, OVERLAY }
}
