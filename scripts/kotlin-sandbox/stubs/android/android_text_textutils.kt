// Sandbox-only stand-ins, see android_content.kt.
package android.text

/** Inert stand-in for the empty-check the thumbnail selection branches on. */
object TextUtils {
    @JvmStatic
    fun isEmpty(text: CharSequence?): Boolean = text == null || text.length == 0

    /** Content comparison of two character sequences, matching the platform helper's contract. */
    @JvmStatic
    fun equals(a: CharSequence?, b: CharSequence?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        return a.length == b.length && a.toString() == b.toString()
    }
}

/** Inert graphics stand-in; no glyphs are drawn in the sandbox. */
class TextPaint : android.graphics.Paint()
