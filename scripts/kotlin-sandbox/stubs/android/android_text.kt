// Sandbox-only stand-ins, see android_content.kt.
package android.text

/** Minimal `Editable` surface: only the members the converted dialogs read. */
interface Editable : CharSequence

/** Mutable stand-in backing the recording text fields below. */
class SimpleEditable(initial: String = "") : Editable {
    private var value = initial
    override val length: Int get() = value.length
    override fun get(index: Int): Char = value[index]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        value.subSequence(startIndex, endIndex)
    fun set(text: String) {
        value = text
    }
    override fun toString(): String = value
}

interface TextWatcher {
    fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    fun afterTextChanged(s: Editable?) {}
}

/**
 * Stand-in for the platform input-filter interface and its nested `LengthFilter`, the only filter
 * the converted dialogs install (the PIN dialog caps what can be typed). The stand-in records the
 * cap; it does not rewrite text.
 */
interface InputFilter {
    fun filter(source: CharSequence?, start: Int, end: Int, dest: CharSequence?, dstart: Int,
               dend: Int): CharSequence?

    class LengthFilter(private val max: Int) : InputFilter {
        /** The configured cap. */
        fun getMax(): Int = max

        override fun filter(source: CharSequence?, start: Int, end: Int, dest: CharSequence?,
                            dstart: Int, dend: Int): CharSequence? = source
    }
}

/** Constants-only stand-in for the platform input-type flags. */
interface InputType {
    companion object {
        const val TYPE_CLASS_TEXT = 0x00000001
        const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000080
        const val TYPE_CLASS_NUMBER = 0x00000002
        const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010
    }
}
