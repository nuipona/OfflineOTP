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

package org.shadowice.flocke.andotp.Activities;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import org.shadowice.flocke.andotp.Utilities.VaultSession;

/** Base activity that reacts to screen-off events to lock or close the screen. */
public abstract class BaseActivity extends ThemedActivity {
    private ScreenOffReceiver screenOffReceiver;
    private final Runnable lockListener = () -> runOnUiThread(this::onVaultLocked);
    private BroadcastReceivedCallback broadcastReceivedCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        VaultSession.addLockListener(lockListener);
        screenOffReceiver = new ScreenOffReceiver();
        registerReceiver(screenOffReceiver, screenOffReceiver.filter);
    }

    @Override
    protected void onDestroy() {
        VaultSession.removeLockListener(lockListener);
        unregisterReceiver(screenOffReceiver);
        super.onDestroy();
    }

    /** Lets the activity react when the screen is turned off. */
    public void setBroadcastCallback(BroadcastReceivedCallback cb) {
        this.broadcastReceivedCallback = cb;
    }

    /** Finishes the activity (and notifies the callback) when the screen turns off. */
    public class ScreenOffReceiver extends BroadcastReceiver {
        public IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                if (settings.getRelockOnScreenOff()) VaultSession.lock(org.shadowice.flocke.andotp.Utilities.SessionLockReason.SCREEN_OFF);
                if (broadcastReceivedCallback != null) {
                    broadcastReceivedCallback.onReceivedScreenOff();
                }
                if (shouldDestroyOnScreenOff()) {
                    finish();
                }
            }
        }
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        int obscured = android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED;
        if (android.os.Build.VERSION.SDK_INT >= 29) obscured |= android.view.MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED;
        return (event.getFlags() & obscured) == 0 && super.dispatchTouchEvent(event);
    }

    protected boolean requiresVaultSession() { return false; }

    protected void onVaultLocked() { finish(); }

    @Override public void onResume() {
        super.onResume();
        if (requiresVaultSession() && VaultSession.getKey() == null) finish();
    }

    /** True if this activity should be closed when the screen turns off. */
    protected boolean shouldDestroyOnScreenOff() {
        return true;
    }

    /** Callback for the screen-off event. */
    interface BroadcastReceivedCallback {
        void onReceivedScreenOff();
    }
}
