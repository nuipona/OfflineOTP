// Sandbox-only stand-ins, see android_content.kt.
package android.view

import android.os.IBinder

/**
 * `postDelayed` is left `open` here purely so the checks can capture what a listener posts; the
 * platform method is not overridable in the same way. No platform timing is exercised. The
 * alpha/translation/width/windowToken surface exists for the view-drawing and keyboard helpers;
 * the platform getters are final and the fakes subclass this stand-in instead. The id/visibility/
 * enable/click surface lets the dialog checks drive recording widgets the way the platform would.
 */
open class View {
    /** The platform interface is Java; a `fun interface` keeps lambda/SAM conversion working. */
    fun interface OnClickListener {
        fun onClick(v: View)
    }

    var id: Int = 0
    var layoutParams: ViewGroup.LayoutParams? = null
    var contentDescription: CharSequence? = null
    var alpha: Float = 1.0f
    var translationX: Float = 0.0f
    /** The platform getter is final; the stand-in is writable so checks can size a fake view. */
    var width: Int = 0
    var visibility: Int = VISIBLE
    var isEnabled: Boolean = true
    /** Recorded by the setImportantFor* methods below; distinct names avoid the setter clash. */
    var importantForAccessibilityMode: Int = 0
    var importantForAutofillMode: Int = 0
    val windowToken: IBinder? get() = null

    private var clickListener: OnClickListener? = null

    open fun refreshDrawableState() {}

    open fun postDelayed(runnable: Runnable, delayMillis: Long): Boolean = true

    /** Records the listener so `performClick` can dispatch to it like the platform would. */
    open fun setOnClickListener(listener: OnClickListener?) {
        clickListener = listener
    }

    open fun performClick(): Boolean {
        val listener = clickListener ?: return false
        listener.onClick(this)
        return true
    }

    fun setPaddingRelative(start: Int, top: Int, end: Int, bottom: Int) {}
    fun setImportantForAccessibility(mode: Int) {
        importantForAccessibilityMode = mode
    }

    fun setImportantForAutofill(mode: Int) {
        importantForAutofillMode = mode
    }

    /** Inert lookup; `AppCompatDialog` routes through a scripted provider for the dialog checks. */
    fun <T : View> findViewById(id: Int): T? = null

    companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
        const val IMPORTANT_FOR_ACCESSIBILITY_NO = 2
        const val IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS = 4
        const val IMPORTANT_FOR_AUTOFILL_NO = 2
        const val IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS = 8
    }
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

/** Inert layout stand-ins: no Android measurement or inflation happens here. */
open class ViewGroup : View() {
    open class LayoutParams(val width: Int, val height: Int) {
        companion object {
            const val MATCH_PARENT = -1
            const val WRAP_CONTENT = -2
        }
    }
}

class LayoutInflater private constructor() {
    fun inflate(resource: Int, parent: ViewGroup, attachToRoot: Boolean): View = android.widget.CheckedTextView()
    companion object {
        @JvmStatic fun from(context: android.content.Context): LayoutInflater = LayoutInflater()
    }
}

object Gravity { const val CENTER = 17 }
