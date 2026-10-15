// Sandbox-only stand-ins, see android_content.kt.
package android.view

/**
 * `postDelayed` is left `open` here purely so the checks can capture what a listener posts; the
 * platform method is not overridable in the same way. No platform timing is exercised.
 */
open class View {
    interface OnClickListener {
        fun onClick(v: View)
    }

    open fun postDelayed(runnable: Runnable, delayMillis: Long): Boolean = true
}

class KeyEvent(private val eventAction: Int, private val eventKeyCode: Int) {
    val action: Int get() = eventAction
    val keyCode: Int get() = eventKeyCode

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val KEYCODE_ENTER = 66
        const val KEYCODE_NUMPAD_ENTER = 160
    }
}
