// Sandbox-only stand-ins, see android_content.kt.
package com.google.android.material.textfield

import android.view.View
import android.widget.EditText

open class TextInputEditText : EditText()

/**
 * The end-icon listener is recorded so the checks can drive the show/hide toggle exactly like the
 * platform click would; no drawable/state machinery exists here.
 */
open class TextInputLayout : View() {
    var endIconClickListener: View.OnClickListener? = null
        private set
    var refreshCount = 0
        private set

    fun setEndIconOnClickListener(listener: View.OnClickListener?) {
        endIconClickListener = listener
    }

    override fun refreshDrawableState() {
        refreshCount++
    }
}
