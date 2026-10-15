package org.shadowice.flocke.andotp.Utilities

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo

/** Normalizes the IME "done"/"enter" editor actions across the different editors. */
object EditorActionHelper {
    /**
     * True for the IME "done" action or a hardware enter key press.
     *
     * {@code event} is null for actions reported by the IME itself, so it is only inspected when
     * the action is not "done"; a missing event for any other action fails like it did before.
     */
    @JvmStatic
    fun isActionDoneOrKeyboardEnter(actionId: Int, event: KeyEvent?): Boolean {
        if (actionId == EditorInfo.IME_ACTION_DONE) return true
        val keyEvent = event ?: throw NullPointerException("Missing key event for editor action")
        return keyEvent.action == KeyEvent.ACTION_DOWN &&
                (keyEvent.keyCode == KeyEvent.KEYCODE_ENTER || keyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
    }

    /** True for the enter key release that follows a key press. A null event stays a caller error. */
    @JvmStatic
    fun isActionUpKeyboardEnter(event: KeyEvent?): Boolean {
        val keyEvent = event ?: throw NullPointerException("Missing key event for editor action")
        return keyEvent.action == KeyEvent.ACTION_UP &&
                (keyEvent.keyCode == KeyEvent.KEYCODE_ENTER || keyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
    }
}
