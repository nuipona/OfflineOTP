// Sandbox-only stand-ins, see android_content.kt.
package android.view

/**
 * Stand-in for the platform insets. Only the type masks are used; the platform class also
 * carries the insets of the system windows.
 */
class WindowInsets {
    /** The inset types; the platform class holds the masks of the system bars. */
    class Type {
        companion object {
            /** The platform's mask values. */
            @JvmStatic
            fun statusBars(): Int = 1

            @JvmStatic
            fun navigationBars(): Int = 2
        }
    }
}

/**
 * Stand-in for the platform insets controller. It records what was hidden and which behaviour was
 * requested; the platform animates the system bars.
 */
class WindowInsetsController {
    /** The inset types that were hidden. */
    var hiddenTypes: Int = 0
        private set

    /** The system bar behaviour that was requested. */
    var behavior: Int = 0
        private set

    fun hide(types: Int) {
        hiddenTypes = hiddenTypes or types
    }

    fun setSystemBarsBehavior(behavior: Int) {
        this.behavior = behavior
    }

    companion object {
        /** The platform's behaviour constants. */
        const val BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE: Int = 2
    }
}
