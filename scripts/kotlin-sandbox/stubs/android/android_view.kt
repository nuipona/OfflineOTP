// Sandbox-only stand-ins, see android_content.kt.
package android.view

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
