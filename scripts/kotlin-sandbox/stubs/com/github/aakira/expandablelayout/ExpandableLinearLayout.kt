// Sandbox-only stand-ins, see ../../../../android/android_content.kt.
package com.github.aakira.expandablelayout

import android.content.Context
import android.view.ViewGroup

/** The expandable section of the manual entry dialog. */
open class ExpandableLinearLayout(context: Context) : ViewGroup() {
    /** The listener that was set. */
    var listener: ExpandableLayoutListenerAdapter? = null
        private set

    /** Whether the section is expanded. */
    var expanded: Boolean = false
        private set

    /** How often the section was toggled. */
    var toggles = 0
        private set

    fun setListener(listener: ExpandableLayoutListenerAdapter?) {
        this.listener = listener
    }

    fun toggle() {
        toggles++
        expanded = !expanded
    }
}

/** The expansion callbacks of the expandable layout. */
open class ExpandableLayoutListenerAdapter {
    open fun onOpened() {}
    open fun onClosed() {}
}
