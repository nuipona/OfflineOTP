// Sandbox-only stand-ins, see android_content.kt.
package android.widget

import android.content.Context

class Toast private constructor() {
    fun show() {}

    companion object {
        const val LENGTH_LONG = 1

        @JvmStatic
        fun makeText(context: Context, resId: Int, duration: Int): Toast = Toast()
    }
}
