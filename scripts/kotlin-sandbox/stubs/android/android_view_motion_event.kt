// Sandbox-only stand-ins, see android_content.kt.
package android.view

/**
 * Stand-in for the platform motion event.
 *
 * The flag constants carry the platform's values, so the obscured-touch check of the converted
 * base activity can be driven here. No touch dispatch, gesture or coordinate handling exists.
 */
open class MotionEvent {
    /** The flags of the event. */
    var eventFlags: Int = 0

    open fun getFlags(): Int = eventFlags

    companion object {
        const val FLAG_WINDOW_IS_OBSCURED = 0x00000001
        const val FLAG_WINDOW_IS_PARTIALLY_OBSCURED = 0x00000002
    }
}
