// Sandbox-only stand-ins, see android_content.kt.
package android.view.inputmethod

import android.os.IBinder
import android.view.View

class EditorInfo private constructor() {
    companion object {
        const val IME_ACTION_DONE = 6
    }
}

/**
 * The methods are `open` so the checks can record which view/token requests were made; no real
 * input-method window behaviour is exercised.
 */
open class InputMethodManager {
    companion object {
        const val SHOW_FORCED = 2
        const val HIDE_IMPLICIT_ONLY = 1
    }

    open fun showSoftInput(view: View, flags: Int): Boolean = true
    open fun hideSoftInputFromWindow(windowToken: IBinder?, flags: Int): Boolean = true
    open fun toggleSoftInput(showFlags: Int, hideFlags: Int) {}
}
