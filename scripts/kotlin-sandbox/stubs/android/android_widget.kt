// Sandbox-only stand-ins, see android_content.kt.
package android.widget

import android.content.Context
import android.text.method.TransformationMethod
import android.view.View

class Toast private constructor() {
    fun show() {}

    companion object {
        const val LENGTH_LONG = 1

        @JvmStatic
        fun makeText(context: Context, resId: Int, duration: Int): Toast = Toast()
    }
}

/**
 * Stand-in for the masked-input surface used by the password helper. The selection/transformation
 * members are `open` so the checks can drive a fake field; no text layout is modelled.
 */
open class EditText : View() {
    open var transformationMethod: TransformationMethod? = null
    open val selectionEnd: Int = -1
    open fun setSelection(index: Int) {}
}
