// Sandbox-only stand-ins, see ../../../android/android_content.kt.
package androidx.appcompat.widget

import android.content.Context
import android.view.View

/** The search view of the main screen. */
open class SearchView(context: Context) : View() {
    /** The listener that was set. */
    var onQueryTextListener: OnQueryTextListener? = null
        private set

    fun setOnQueryTextListener(listener: OnQueryTextListener?) {
        onQueryTextListener = listener
    }

    /** The platform interface has two methods, so it stays a plain interface. */
    interface OnQueryTextListener {
        fun onQueryTextSubmit(query: String?): Boolean
        fun onQueryTextChange(newText: String?): Boolean
    }
}
