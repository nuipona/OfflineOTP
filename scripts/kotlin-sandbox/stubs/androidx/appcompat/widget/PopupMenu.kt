// Sandbox-only stand-ins, see ../../../android/android_content.kt.
package androidx.appcompat.widget

import android.content.Context
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View

/**
 * The per-entry context menu. The stand-in records the menu, the inflater and the click
 * listener; nothing is shown.
 */
open class PopupMenu(context: Context, anchor: View) {
    private val menu = Menu()
    private val inflater = MenuInflater()

    /** The click listener that was set. */
    var onMenuItemClickListener: MenuItem.OnMenuItemClickListener? = null
        private set

    /** How often the menu was shown. */
    var shows = 0
        private set

    fun getMenuInflater(): MenuInflater = inflater

    fun getMenu(): Menu = menu

    fun setOnMenuItemClickListener(listener: MenuItem.OnMenuItemClickListener?) {
        onMenuItemClickListener = listener
    }

    fun show() {
        shows++
    }
}
