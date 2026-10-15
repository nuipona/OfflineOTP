// Sandbox-only stand-ins, see android_content.kt.
package android.text

/** Inert stand-in for the empty-check the thumbnail selection branches on. */
object TextUtils {
    @JvmStatic
    fun isEmpty(text: CharSequence?): Boolean = text == null || text.length == 0
}

/** Inert graphics stand-in; no glyphs are drawn in the sandbox. */
class TextPaint : android.graphics.Paint()
