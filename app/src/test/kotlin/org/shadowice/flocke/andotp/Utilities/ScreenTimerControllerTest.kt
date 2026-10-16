package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTimerControllerTest {
    private class Job(val runnable: Runnable, val delay: Long)

    private class Scheduler : ScreenTimerController.Scheduler {
        val pending = mutableListOf<Job>()
        var now = 0L

        override fun postDelayed(runnable: Runnable, delayMillis: Long) {
            pending.add(Job(runnable, delayMillis))
        }

        override fun remove(runnable: Runnable) {
            pending.removeAll { it.runnable === runnable }
        }

        override fun elapsedRealtime(): Long = now

        fun take(): Job = pending.removeAt(0)

        fun runNext() {
            val job = take()
            now += job.delay
            job.runnable.run()
        }
    }

    @Test fun startsImmediatelyThenTicksOncePerSecondWithoutDuplicateLoops() {
        val scheduler = Scheduler()
        var calls = 0
        val timers = ScreenTimerController(scheduler, Runnable { calls++ }, Runnable { fail("idle disabled") })
        timers.startTicks()
        timers.startTicks()
        assertEquals(1, scheduler.pending.size)
        assertEquals(0L, scheduler.pending[0].delay)
        scheduler.runNext()
        assertEquals(1, calls)
        assertEquals(1000L, scheduler.pending[0].delay)
        scheduler.runNext()
        assertEquals(2, calls)
        timers.close()
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test fun stoppedRunnableCannotReviveAnOlderLoopAfterRestart() {
        val scheduler = Scheduler()
        var calls = 0
        val timers = ScreenTimerController(scheduler, Runnable { calls++ }, Runnable {})
        timers.startTicks()
        val stale = scheduler.take().runnable
        timers.stopTicks()
        timers.startTicks()
        stale.run()
        assertEquals(0, calls)
        assertEquals(1, scheduler.pending.size)
        scheduler.runNext()
        assertEquals(1, calls)
        timers.close()
    }

    @Test fun inactivityRestartInvalidatesEvenAnAlreadyDequeuedExpiry() {
        val scheduler = Scheduler()
        var expires = 0
        val timers = ScreenTimerController(scheduler, Runnable {}, Runnable { expires++ })
        timers.restartInactivity(1000)
        val stale = scheduler.take().runnable
        scheduler.now = 1000
        timers.restartInactivity(2000)
        stale.run()
        assertEquals(0, expires)
        scheduler.runNext()
        assertEquals(0, expires)
        scheduler.runNext()
        assertEquals(1, expires)
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test fun elapsedRealtimeExpiryDoesNotWaitForUptimeToCatchUpAfterSleep() {
        val scheduler = Scheduler()
        var expires = 0
        val timers = ScreenTimerController(scheduler, Runnable {}, Runnable { expires++ })
        timers.restartInactivity(30000)
        val first = scheduler.take()
        assertEquals(1000L, first.delay)
        scheduler.now = 60000 // Elapsed time advanced while the main looper was asleep.
        first.runnable.run()
        assertEquals(1, expires)
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test fun disabledInactivityCancelsPreviousExpiry() {
        val scheduler = Scheduler()
        val timers = ScreenTimerController(scheduler, Runnable {}, Runnable { fail("disabled") })
        timers.restartInactivity(1000)
        val old = scheduler.take().runnable
        timers.restartInactivity(0)
        scheduler.now = 10000
        old.run()
        timers.restartInactivity(-1)
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test fun pauseCancelsBothChannelsAndCloseIsPermanent() {
        val scheduler = Scheduler()
        val timers = ScreenTimerController(scheduler, Runnable { fail("closed") }, Runnable { fail("closed") })
        timers.startTicks()
        timers.restartInactivity(2000)
        val tick = scheduler.pending[0].runnable
        val idle = scheduler.pending[1].runnable
        timers.pause()
        assertTrue(scheduler.pending.isEmpty())
        timers.close()
        timers.close()
        timers.startTicks()
        timers.restartInactivity(1000)
        scheduler.now = 5000
        tick.run()
        idle.run()
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test fun callbackMayStopTheTickWithoutItReschedulingItself() {
        val scheduler = Scheduler()
        val owner = arrayOfNulls<ScreenTimerController>(1)
        owner[0] = ScreenTimerController(scheduler, Runnable { owner[0]!!.stopTicks() }, Runnable {})
        owner[0]!!.startTicks()
        scheduler.runNext()
        assertTrue(scheduler.pending.isEmpty())
        owner[0]!!.close()
    }

    @Test fun javaBoundaryKeepsRunnableCallbacksAndPrimitiveLongMethods() {
        assertNotNull(
            ScreenTimerController::class.java.getConstructor(
                ScreenTimerController.Scheduler::class.java,
                Runnable::class.java,
                Runnable::class.java
            )
        )
        assertEquals(
            java.lang.Void.TYPE,
            ScreenTimerController::class.java.getMethod("restartInactivity", java.lang.Long.TYPE).returnType
        )
        assertEquals(java.lang.Void.TYPE, ScreenTimerController::class.java.getMethod("close").returnType)
        assertEquals(
            java.lang.Void.TYPE,
            ScreenTimerController.Scheduler::class.java
                .getMethod("postDelayed", Runnable::class.java, java.lang.Long.TYPE).returnType
        )
        assertEquals(
            java.lang.Long.TYPE,
            ScreenTimerController.Scheduler::class.java.getMethod("elapsedRealtime").returnType
        )
    }

    @Test fun callbackMayCloseControllerWithoutReschedulingOrRetainingExpiry() {
        val scheduler = Scheduler()
        val owner = arrayOfNulls<ScreenTimerController>(1)
        owner[0] = ScreenTimerController(scheduler, Runnable { owner[0]!!.close() }, Runnable { fail("closed") })
        owner[0]!!.startTicks()
        owner[0]!!.restartInactivity(1000)
        val oldIdle = scheduler.pending[1].runnable
        scheduler.runNext()
        assertTrue(scheduler.pending.isEmpty())
        scheduler.now = 2000
        oldIdle.run()
        owner[0]!!.startTicks()
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test fun longInactivityDelayDoesNotTruncateToIntegerMilliseconds() {
        val scheduler = Scheduler()
        var expires = 0
        val timers = ScreenTimerController(scheduler, Runnable {}, Runnable { expires++ })
        val delay = 1000L * Int.MAX_VALUE
        timers.restartInactivity(delay)
        val first = scheduler.take()
        scheduler.now = delay - 1
        first.runnable.run()
        assertEquals(0, expires)
        assertEquals(1L, scheduler.pending[0].delay)
        scheduler.runNext()
        assertEquals(1, expires)
        timers.close()
    }
}
