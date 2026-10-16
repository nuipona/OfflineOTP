// Sandbox-only stand-ins, see android_content.kt.
package android.view

/**
 * Stand-in for the platform window.
 *
 * It only records the navigation bar color the converted base activity sets; there is no window,
 * decor, insets or system bar behaviour here.
 */
open class Window {
    /** The navigation bar color that was set. */
    var navigationBarColor: Int = 0
        private set

    open fun setNavigationBarColor(color: Int) {
        navigationBarColor = color
    }
}
