// Sandbox-only stand-ins, see ../../../android/android_content.kt.
//
// The AppCompat activity surface the converted base activities use. It records what the activity
// did (theme, window color, registered receivers, posted UI-thread work, finished state and
// dispatched touches) so the checks can drive the screen-off and obscured-touch logic. It is NOT
// an activity: there is no window, decor, lifecycle, configuration change or fragment support.
package androidx.appcompat.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.Window
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.FragmentManager

/** Stand-in for the AppCompat activity. */
open class AppCompatActivity : Context() {
    /** The window the activity owns. */
    val activityWindow = Window()

    /** The receivers the activity registered, in registration order. */
    val registeredReceivers = ArrayList<Pair<BroadcastReceiver, IntentFilter>>()

    /** How often the activity was finished. */
    var finishes = 0
        private set

    /** The deliveries that were posted to the UI thread, in order. */
    val uiThreadDeliveries = ArrayList<Runnable>()

    /** The touch events that were dispatched to the activity. */
    val dispatchedTouches = ArrayList<MotionEvent>()

    /** The bundles the activity was created with. */
    val createdWith = ArrayList<Bundle?>()

    /** The title/content resources the activity set, recorded instead of resolved. */
    var titleResource = 0
        private set
    var contentResource = 0
        private set

    /** The toolbar the activity installed as its support action bar. */
    var supportActionBar: Toolbar? = null
        private set

    private val supportFragmentManager = FragmentManager()

    protected open fun onCreate(savedInstanceState: Bundle?) {
        createdWith.add(savedInstanceState)
    }

    protected open fun onResume() {}

    protected open fun onDestroy() {}

    open fun getWindow(): Window = activityWindow

    open fun setTitle(resId: Int) {
        titleResource = resId
    }

    open fun setContentView(resId: Int) {
        contentResource = resId
    }

    open fun setSupportActionBar(toolbar: Toolbar?) {
        supportActionBar = toolbar
    }

    /**
     * `open` so the about checks can supply the toolbar/stub/pager views; the platform resolves
     * the real window hierarchy instead and the getter is final there.
     */
    open fun <T : View> findViewById(id: Int): T? = null

    open fun getSupportFragmentManager(): FragmentManager = supportFragmentManager

    /** The up-navigation hook; the converted about screen finishes instead of navigating. */
    open fun onSupportNavigateUp(): Boolean = false

    open fun onBackPressed() {}

    /** Records the delivery instead of running it on a looper; the checks drain it themselves. */
    open fun runOnUiThread(action: Runnable) {
        uiThreadDeliveries.add(action)
    }

    open fun finish() {
        finishes++
    }

    open fun dispatchTouchEvent(event: MotionEvent): Boolean {
        dispatchedTouches.add(event)
        return true
    }

    open fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter): Intent? {
        registeredReceivers.add(receiver to filter)
        return null
    }

    open fun unregisterReceiver(receiver: BroadcastReceiver) {
        registeredReceivers.removeAll { it.first === receiver }
    }
}
