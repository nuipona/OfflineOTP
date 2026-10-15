// Sandbox-only stand-ins for the Android platform surface used by the checked classes.
// They exist so the Kotlin sources can be type-checked without the Android SDK. They are NOT
// the Android platform and prove nothing about SDK behaviour. Real Java getters are modelled as
// Kotlin properties because Kotlin only synthesises properties for Java declarations.
package android.content

import android.content.res.Resources

open class Context {
    val theme: Resources.Theme get() = Resources.Theme()
    val resources: Resources get() = Resources()
    fun getString(resId: Int): String = "stub"
    /** `open` so checks can return a recording service instead of the null default. */
    open fun getSystemService(name: String): Any? = null

    companion object {
        const val CLIPBOARD_SERVICE = "clipboard"
        const val INPUT_METHOD_SERVICE = "input_method"
    }
}

class ClipData private constructor() {
    companion object {
        @JvmStatic
        fun newPlainText(label: CharSequence?, text: CharSequence?): ClipData = ClipData()
    }
}

class ClipboardManager {
    fun setPrimaryClip(clip: ClipData) {}
}

/** Field/type-only stand-in; the checked code never touches Intent internals. */
class Intent

/** The nested listener mirrors the platform nesting; `fun interface` keeps SAM conversion working. */
interface DialogInterface {
    fun interface OnClickListener {
        fun onClick(dialog: DialogInterface, which: Int)
    }

    companion object {
        const val BUTTON_POSITIVE = -1
    }
}
