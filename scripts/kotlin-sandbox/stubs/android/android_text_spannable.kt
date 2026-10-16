// Sandbox-only stand-ins, see android_content.kt.
package android.text

/** The spanned text interface; the card label uses the exclusive-exclusive span flags. */
interface Spannable {
    fun setSpan(span: Any, start: Int, end: Int, flags: Int)

    companion object {
        const val SPAN_EXCLUSIVE_EXCLUSIVE = 0x21
        const val SPAN_INCLUSIVE_EXCLUSIVE = 0x11
    }
}

/**
 * The spanned text builder of the card label. The stand-in records the appended text and the
 * spans; it does not lay out or measure anything.
 */
class SpannableStringBuilder : Spannable, CharSequence {
    /** The text that was appended. */
    val text: StringBuilder = StringBuilder()

    /** The spans that were set, in order. */
    val spans: MutableList<Pair<Any, IntArray>> = ArrayList()

    fun append(text: CharSequence?): SpannableStringBuilder {
        this.text.append(text)
        return this
    }

    override fun setSpan(span: Any, start: Int, end: Int, flags: Int) {
        spans.add(span to intArrayOf(start, end, flags))
    }

    override val length: Int get() = text.length

    override fun get(index: Int): Char = text[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        text.subSequence(startIndex, endIndex)

    override fun toString(): String = text.toString()
}
