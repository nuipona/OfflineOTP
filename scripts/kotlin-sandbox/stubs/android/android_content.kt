// Sandbox-only stand-ins for the Android platform surface used by the checked classes.
// They exist so the Kotlin sources can be type-checked without the Android SDK. They are NOT
// the Android platform and prove nothing about SDK behaviour. Real Java getters are modelled as
// Kotlin properties because Kotlin only synthesises properties for Java declarations.
package android.content

import android.content.res.Resources

open class Context {
    val theme: Resources.Theme get() = Resources.Theme()
    fun getString(resId: Int): String = "stub"
    fun getSystemService(name: String): Any? = null

    companion object {
        const val CLIPBOARD_SERVICE = "clipboard"
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
