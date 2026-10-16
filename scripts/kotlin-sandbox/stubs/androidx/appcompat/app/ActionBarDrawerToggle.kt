// Sandbox-only stand-ins, see ../../../android/android_content.kt.
package androidx.appcompat.app

import android.content.res.Configuration
import android.view.MenuItem
import android.view.View
import androidx.drawerlayout.widget.DrawerLayout

/** The drawer toggle of the tag drawer. */
open class ActionBarDrawerToggle(
    private val activity: android.app.Activity,
    private val drawerLayout: DrawerLayout,
    private val openDrawerRes: Int,
    private val closeDrawerRes: Int
) : DrawerLayout.DrawerListener {

    /** Whether the drawer indicator is shown. */
    var drawerIndicatorEnabled: Boolean = false
        private set

    /** How often the state was synced. */
    var syncs = 0
        private set

    fun setDrawerIndicatorEnabled(enabled: Boolean) {
        drawerIndicatorEnabled = enabled
    }

    fun syncState() {
        syncs++
    }

    fun onConfigurationChanged(newConfig: Configuration) {}

    fun onOptionsItemSelected(item: MenuItem): Boolean = false

    override fun onDrawerOpened(drawerView: View) {}

    override fun onDrawerClosed(drawerView: View) {}

    override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}

    override fun onDrawerStateChanged(newState: Int) {}
}
