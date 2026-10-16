package org.shadowice.flocke.andotp.Utilities

import android.app.Instrumentation
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Handler/Scheduler -> Kotlin controller -> callback bridge. No vault fixture. */
@RunWith(AndroidJUnit4::class)
class KotlinScreenTimerInteropTest {
    @Test
    fun schedulerDrivesConvertedTimerOnMainLooper() {
        val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
        val owner = AtomicReference<ScreenTimerController>()
        val mainThread = AtomicBoolean(true)
        val tickCount = AtomicInteger()
        val expiryCount = AtomicInteger()
        val ticked = CountDownLatch(1)
        val expired = CountDownLatch(1)
        try {
            instrumentation.runOnMainSync {
                val timers = ScreenTimerController(
                    HandlerTimerScheduler(),
                    Runnable {
                        if (Looper.myLooper() != Looper.getMainLooper()) mainThread.set(false)
                        tickCount.incrementAndGet()
                        owner.get().stopTicks()
                        ticked.countDown()
                    },
                    Runnable {
                        if (Looper.myLooper() != Looper.getMainLooper()) mainThread.set(false)
                        expiryCount.incrementAndGet()
                        expired.countDown()
                    }
                )
                owner.set(timers)
                timers.startTicks()
                timers.startTicks()
                timers.restartInactivity(20)
            }
            assertTrue(ticked.await(5, TimeUnit.SECONDS))
            assertTrue(expired.await(5, TimeUnit.SECONDS))
            assertTrue(mainThread.get())
            assertEquals(1, tickCount.get())
            assertEquals(1, expiryCount.get())
        } finally {
            instrumentation.runOnMainSync { owner.get()?.close() }
        }
    }
}
