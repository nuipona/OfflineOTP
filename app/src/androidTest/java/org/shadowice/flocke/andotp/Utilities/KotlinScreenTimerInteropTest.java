package org.shadowice.flocke.andotp.Utilities;

import android.app.Instrumentation;
import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real Java Handler/Scheduler -> Kotlin controller -> Java callback bridge. No vault fixture. */
@RunWith(AndroidJUnit4.class)
public class KotlinScreenTimerInteropTest {
    @Test public void javaSchedulerDrivesConvertedTimerOnMainLooper() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        AtomicReference<ScreenTimerController> owner = new AtomicReference<>();
        AtomicBoolean mainThread = new AtomicBoolean(true);
        AtomicInteger tickCount = new AtomicInteger();
        AtomicInteger expiryCount = new AtomicInteger();
        CountDownLatch ticked = new CountDownLatch(1);
        CountDownLatch expired = new CountDownLatch(1);
        try {
            instrumentation.runOnMainSync(() -> {
                ScreenTimerController timers = new ScreenTimerController(new HandlerTimerScheduler(), () -> {
                    if (Looper.myLooper() != Looper.getMainLooper()) mainThread.set(false);
                    tickCount.incrementAndGet();
                    owner.get().stopTicks();
                    ticked.countDown();
                }, () -> {
                    if (Looper.myLooper() != Looper.getMainLooper()) mainThread.set(false);
                    expiryCount.incrementAndGet();
                    expired.countDown();
                });
                owner.set(timers);
                timers.startTicks();
                timers.startTicks();
                timers.restartInactivity(20);
            });
            assertTrue(ticked.await(5, TimeUnit.SECONDS));
            assertTrue(expired.await(5, TimeUnit.SECONDS));
            assertTrue(mainThread.get());
            assertEquals(1, tickCount.get());
            assertEquals(1, expiryCount.get());
        } finally {
            instrumentation.runOnMainSync(() -> { if (owner.get() != null) owner.get().close(); });
        }
    }
}
