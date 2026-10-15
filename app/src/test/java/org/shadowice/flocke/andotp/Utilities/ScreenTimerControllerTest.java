package org.shadowice.flocke.andotp.Utilities;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class ScreenTimerControllerTest {
    private static final class Job {
        final Runnable runnable;
        final long delay;
        Job(Runnable runnable, long delay) { this.runnable = runnable; this.delay = delay; }
    }
    private static final class Scheduler implements ScreenTimerController.Scheduler {
        final List<Job> pending = new ArrayList<>();
        long now;
        @Override public void postDelayed(Runnable runnable, long delay) { pending.add(new Job(runnable, delay)); }
        @Override public void remove(Runnable runnable) { pending.removeIf(job -> job.runnable == runnable); }
        @Override public long elapsedRealtime() { return now; }
        Job take() { return pending.remove(0); }
        void runNext() { Job job = take(); now += job.delay; job.runnable.run(); }
    }

    @Test public void startsImmediatelyThenTicksOncePerSecondWithoutDuplicateLoops() {
        Scheduler scheduler = new Scheduler();
        int[] calls = {0};
        ScreenTimerController timers = new ScreenTimerController(scheduler, () -> calls[0]++, () -> fail("idle disabled"));
        timers.startTicks();
        timers.startTicks();
        assertEquals(1, scheduler.pending.size());
        assertEquals(0L, scheduler.pending.get(0).delay);
        scheduler.runNext();
        assertEquals(1, calls[0]);
        assertEquals(1000L, scheduler.pending.get(0).delay);
        scheduler.runNext();
        assertEquals(2, calls[0]);
        timers.close();
        assertTrue(scheduler.pending.isEmpty());
    }

    @Test public void stoppedRunnableCannotReviveAnOlderLoopAfterRestart() {
        Scheduler scheduler = new Scheduler();
        int[] calls = {0};
        ScreenTimerController timers = new ScreenTimerController(scheduler, () -> calls[0]++, () -> {});
        timers.startTicks();
        Runnable stale = scheduler.take().runnable;
        timers.stopTicks();
        timers.startTicks();
        stale.run();
        assertEquals(0, calls[0]);
        assertEquals(1, scheduler.pending.size());
        scheduler.runNext();
        assertEquals(1, calls[0]);
        timers.close();
    }

    @Test public void inactivityRestartInvalidatesEvenAnAlreadyDequeuedExpiry() {
        Scheduler scheduler = new Scheduler();
        int[] expires = {0};
        ScreenTimerController timers = new ScreenTimerController(scheduler, () -> {}, () -> expires[0]++);
        timers.restartInactivity(1000);
        Runnable stale = scheduler.take().runnable;
        scheduler.now = 1000;
        timers.restartInactivity(2000);
        stale.run();
        assertEquals(0, expires[0]);
        scheduler.runNext();
        assertEquals(0, expires[0]);
        scheduler.runNext();
        assertEquals(1, expires[0]);
        assertTrue(scheduler.pending.isEmpty());
    }

    @Test public void elapsedRealtimeExpiryDoesNotWaitForUptimeToCatchUpAfterSleep() {
        Scheduler scheduler = new Scheduler();
        int[] expires = {0};
        ScreenTimerController timers = new ScreenTimerController(scheduler, () -> {}, () -> expires[0]++);
        timers.restartInactivity(30000);
        Job first = scheduler.take();
        assertEquals(1000L, first.delay);
        scheduler.now = 60000; // Elapsed time advanced while the main looper was asleep.
        first.runnable.run();
        assertEquals(1, expires[0]);
        assertTrue(scheduler.pending.isEmpty());
    }

    @Test public void disabledInactivityCancelsPreviousExpiry() {
        Scheduler scheduler = new Scheduler();
        ScreenTimerController timers = new ScreenTimerController(scheduler, () -> {}, () -> fail("disabled"));
        timers.restartInactivity(1000);
        Runnable old = scheduler.take().runnable;
        timers.restartInactivity(0);
        scheduler.now = 10000;
        old.run();
        timers.restartInactivity(-1);
        assertTrue(scheduler.pending.isEmpty());
    }

    @Test public void pauseCancelsBothChannelsAndCloseIsPermanent() {
        Scheduler scheduler = new Scheduler();
        ScreenTimerController timers = new ScreenTimerController(scheduler, () -> fail("closed"), () -> fail("closed"));
        timers.startTicks();
        timers.restartInactivity(2000);
        Runnable tick = scheduler.pending.get(0).runnable;
        Runnable idle = scheduler.pending.get(1).runnable;
        timers.pause();
        assertTrue(scheduler.pending.isEmpty());
        timers.close();
        timers.close();
        timers.startTicks();
        timers.restartInactivity(1000);
        scheduler.now = 5000;
        tick.run();
        idle.run();
        assertTrue(scheduler.pending.isEmpty());
    }

    @Test public void callbackMayStopTheTickWithoutItReschedulingItself() {
        Scheduler scheduler = new Scheduler();
        ScreenTimerController[] owner = new ScreenTimerController[1];
        owner[0] = new ScreenTimerController(scheduler, () -> owner[0].stopTicks(), () -> {});
        owner[0].startTicks();
        scheduler.runNext();
        assertTrue(scheduler.pending.isEmpty());
        owner[0].close();
    }
    @Test public void javaBoundaryKeepsRunnableCallbacksAndPrimitiveLongMethods() throws Exception {
        assertNotNull(ScreenTimerController.class.getConstructor(
                ScreenTimerController.Scheduler.class, Runnable.class, Runnable.class));
        assertEquals(void.class, ScreenTimerController.class.getMethod("restartInactivity", long.class).getReturnType());
        assertEquals(void.class, ScreenTimerController.class.getMethod("close").getReturnType());
        assertEquals(void.class, ScreenTimerController.Scheduler.class.getMethod(
                "postDelayed", Runnable.class, long.class).getReturnType());
        assertEquals(long.class, ScreenTimerController.Scheduler.class.getMethod("elapsedRealtime").getReturnType());
    }

    @Test public void callbackMayCloseControllerWithoutReschedulingOrRetainingExpiry() {
        Scheduler scheduler = new Scheduler();
        ScreenTimerController[] owner = new ScreenTimerController[1];
        owner[0] = new ScreenTimerController(scheduler, () -> owner[0].close(), () -> fail("closed"));
        owner[0].startTicks();
        owner[0].restartInactivity(1000);
        Runnable oldIdle = scheduler.pending.get(1).runnable;
        scheduler.runNext();
        assertTrue(scheduler.pending.isEmpty());
        scheduler.now = 2000;
        oldIdle.run();
        owner[0].startTicks();
        assertTrue(scheduler.pending.isEmpty());
    }

    @Test public void longInactivityDelayDoesNotTruncateToIntegerMilliseconds() {
        Scheduler scheduler = new Scheduler();
        int[] expires = {0};
        ScreenTimerController timers = new ScreenTimerController(scheduler, () -> {}, () -> expires[0]++);
        long delay = 1000L * Integer.MAX_VALUE;
        timers.restartInactivity(delay);
        Job first = scheduler.take();
        scheduler.now = delay - 1;
        first.runnable.run();
        assertEquals(0, expires[0]);
        assertEquals(1L, scheduler.pending.get(0).delay);
        scheduler.runNext();
        assertEquals(1, expires[0]);
        timers.close();
    }

}
