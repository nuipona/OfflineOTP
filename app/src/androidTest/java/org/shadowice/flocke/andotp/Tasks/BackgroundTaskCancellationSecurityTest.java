package org.shadowice.flocke.andotp.Tasks;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.shadowice.flocke.andotp.Utilities.VaultSession;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BackgroundTaskCancellationSecurityTest {
    @Before public void before() { VaultSession.lock(); }
    @After public void after() { VaultSession.lock(); }

    private static final class Task extends UiBasedBackgroundTask<byte[]> {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch discarded = new CountDownLatch(1);
        final byte[] material = new byte[] {1, 2, 3};
        Task() { super(new byte[0]); }
        @Override protected byte[] doInBackground() {
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { /* Return to test post-work cancellation too. */ }
            return material;
        }
        @Override protected void discardResult(byte[] result) {
            Arrays.fill(result, (byte) 0);
            discarded.countDown();
        }
    }

    @Test public void explicitCancellationDoesNotDeliverLateSecrets() throws Exception {
        Task task = new Task();
        AtomicInteger callbacks = new AtomicInteger();
        task.setCallback(result -> callbacks.incrementAndGet());
        task.execute();
        assertTrue(task.started.await(5, TimeUnit.SECONDS));
        task.cancel();
        task.release.countDown();
        assertTrue(task.discarded.await(5, TimeUnit.SECONDS));
        assertArrayEquals(new byte[3], task.material);
        assertEquals(0, callbacks.get());
    }

    @Test public void revocationDiscardsResultAndTaskIsOneShot() throws Exception {
        Task task = new Task();
        AtomicInteger callbacks = new AtomicInteger();
        task.setCallback(result -> callbacks.incrementAndGet());
        task.execute();
        assertTrue(task.started.await(5, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, task::execute);
        VaultSession.lock();
        task.release.countDown();
        assertTrue(task.discarded.await(5, TimeUnit.SECONDS));
        assertArrayEquals(new byte[3], task.material);
        assertEquals(0, callbacks.get());
    }

    @Test public void unexpectedParserOrProviderExceptionDeliversFailureNotSuccess() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        UiBasedBackgroundTask<String> task = new UiBasedBackgroundTask<String>("failure") {
            @Override protected String doInBackground() { throw new IllegalArgumentException("synthetic-provider-failure"); }
        };
        task.setCallback(value -> { result.set(value); delivered.countDown(); });
        task.execute();
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertEquals("failure", result.get());
    }
    private static void awaitCompleted(UiBasedBackgroundTask<?> task) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!(task.getState() instanceof TaskState.Completed) && System.nanoTime() < deadline)
            Thread.sleep(10);
        assertTrue(task.getState() instanceof TaskState.Completed);
    }

    @Test public void typedFailureDoesNotInventAFallbackValue() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<TaskOutcome<String>> result = new AtomicReference<>();
        UiBasedBackgroundTask<String> task = new UiBasedBackgroundTask<String>("legacy-fallback") {
            @Override protected String doInBackground() { throw new IllegalArgumentException("synthetic"); }
        };
        task.setOutcomeCallback(value -> { result.set(value); delivered.countDown(); });
        task.execute();
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertTrue(result.get() instanceof TaskOutcome.Failure);
        assertEquals(TaskFailure.UNEXPECTED_EXCEPTION, ((TaskOutcome.Failure<String>) result.get()).getError());
    }

    @Test public void nullProducerResultIsTypedFailure() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<TaskOutcome<String>> result = new AtomicReference<>();
        UiBasedBackgroundTask<String> task = new UiBasedBackgroundTask<String>("legacy-fallback") {
            @Override protected String doInBackground() { return null; }
        };
        task.setOutcomeCallback(value -> { result.set(value); delivered.countDown(); });
        task.execute();
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertEquals(TaskFailure.NULL_RESULT, ((TaskOutcome.Failure<String>) result.get()).getError());
    }

    @Test public void detachedCompletionRebindsOnce() throws Exception {
        Task task = new Task();
        task.execute();
        assertTrue(task.started.await(5, TimeUnit.SECONDS));
        task.release.countDown();
        awaitCompleted(task);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        task.setOutcomeCallback(outcome -> {
            assertTrue(outcome instanceof TaskOutcome.Value);
            task.disposeOutcome(outcome);
            calls.incrementAndGet();
            delivered.countDown();
        });
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        task.setOutcomeCallback(outcome -> calls.incrementAndGet());
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(1, calls.get());
        assertArrayEquals(new byte[3], task.material);
    }

    @Test public void keyReplacementDisposesDetachedResultBeforeRebind() throws Exception {
        Task task = new Task();
        task.execute();
        assertTrue(task.started.await(5, TimeUnit.SECONDS));
        task.release.countDown();
        awaitCompleted(task);
        VaultSession.activate(new javax.crypto.spec.SecretKeySpec(new byte[16], "AES"), VaultSession.generation());
        assertTrue(task.discarded.await(5, TimeUnit.SECONDS));
        assertArrayEquals(new byte[3], task.material);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<TaskOutcome<byte[]>> result = new AtomicReference<>();
        task.setOutcomeCallback(outcome -> { result.set(outcome); delivered.countDown(); });
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertEquals(TaskCancellation.SESSION_REVOKED, ((TaskOutcome.Cancelled<byte[]>) result.get()).getReason());
    }

    @Test public void cancellationBeforeSubmissionCleansOwnedInputsExactlyOnce() throws Exception {
        AtomicInteger cleaned = new AtomicInteger();
        CountDownLatch delivered = new CountDownLatch(1);
        UiBasedBackgroundTask<String> task = new UiBasedBackgroundTask<String>("fallback") {
            @Override protected void onCancellation() { cleaned.incrementAndGet(); }
            @Override protected String doInBackground() { fail("Canceled task must not run"); return "unexpected"; }
        };
        task.cancel();
        task.cancel();
        task.setOutcomeCallback(outcome -> { assertTrue(outcome instanceof TaskOutcome.Cancelled); delivered.countDown(); });
        task.execute();
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertEquals(1, cleaned.get());
        assertThrows(IllegalStateException.class, task::execute);
    }

}
