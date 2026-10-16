/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.shadowice.flocke.andotp.Activities

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent

import org.shadowice.flocke.andotp.Utilities.SessionLockReason
import org.shadowice.flocke.andotp.Utilities.VaultSession

/**
 * Base activity that reacts to screen-off events to lock or close the screen.
 *
 * Converted from the Java class of the same name. The screen-off receiver, the lock listener and
 * the obscured-touch rejection are unchanged; the nested callback interface was package-private
 * in Java and is public here, because Kotlin has no package-private visibility.
 */
abstract class BaseActivity : ThemedActivity() {
    private var screenOffReceiver: ScreenOffReceiver? = null
    private val lockListener = Runnable { runOnUiThread { onVaultLocked() } }
    private var broadcastReceivedCallback: BroadcastReceivedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        VaultSession.addLockListener(lockListener)
        val receiver = ScreenOffReceiver()
        screenOffReceiver = receiver
        registerReceiver(receiver, receiver.filter)
    }

    override fun onDestroy() {
        VaultSession.removeLockListener(lockListener)
        // The Java version unregistered a field that was null only if `onCreate` had failed.
        screenOffReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    /** Lets the activity react when the screen is turned off. */
    public fun setBroadcastCallback(cb: BroadcastReceivedCallback?) {
        broadcastReceivedCallback = cb
    }

    /** Finishes the activity (and notifies the callback) when the screen turns off. */
    inner class ScreenOffReceiver : BroadcastReceiver() {
        /** The filter this receiver is registered with. */
        var filter: IntentFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)

        override fun onReceive(context: Context, intent: Intent?) {
            if (intent != null && Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                if (settings!!.getRelockOnScreenOff()) VaultSession.lock(SessionLockReason.SCREEN_OFF)
                broadcastReceivedCallback?.onReceivedScreenOff()
                if (shouldDestroyOnScreenOff()) finish()
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        var obscured = MotionEvent.FLAG_WINDOW_IS_OBSCURED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            obscured = obscured or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        }
        return (event.getFlags() and obscured) == 0 && super.dispatchTouchEvent(event)
    }

    protected open fun requiresVaultSession(): Boolean = false

    protected open fun onVaultLocked() {
        finish()
    }

    public override fun onResume() {
        super.onResume()
        if (requiresVaultSession() && VaultSession.getKey() == null) finish()
    }

    /** True if this activity should be closed when the screen turns off. */
    protected open fun shouldDestroyOnScreenOff(): Boolean = true

    /**
     * Callback for the screen-off event.
     *
     * The Java interface was package-private; Kotlin has no package-private visibility, so it is
     * public here. It stays a single-method interface, so the Java call sites keep passing a
     * lambda.
     */
    fun interface BroadcastReceivedCallback {
        fun onReceivedScreenOff()
    }
}
