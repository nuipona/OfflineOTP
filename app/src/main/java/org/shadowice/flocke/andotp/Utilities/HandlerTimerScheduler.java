package org.shadowice.flocke.andotp.Utilities;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Android main-loop bridge; ScreenTimerController owns cancellation and lifecycle. */
public final class HandlerTimerScheduler implements ScreenTimerController.Scheduler {
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Override public void postDelayed(Runnable runnable, long delayMillis) {
        handler.postDelayed(runnable, delayMillis);
    }
    @Override public void remove(Runnable runnable) { handler.removeCallbacks(runnable); }
    @Override public long elapsedRealtime() { return SystemClock.elapsedRealtime(); }
}
