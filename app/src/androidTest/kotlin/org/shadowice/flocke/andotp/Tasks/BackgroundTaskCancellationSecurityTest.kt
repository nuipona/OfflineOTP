package org.shadowice.flocke.andotp.Tasks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Utilities.VaultSession
import java.util.Arrays
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class BackgroundTaskCancellationSecurityTest {
    @Before fun before() {
        VaultSession.lock()
    }

    @After fun after() {
        VaultSession.lock()
    }

    private class Task : UiBasedBackgroundTask<ByteArray>(ByteArray(0)) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val discarded = CountDownLatch(1)
        val material = byteArrayOf(1, 2, 3)

        override fun doInBackground(): ByteArray {
            started.countDown()
            try {
                release.await(5, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                // Return to test post-work cancellation too.
            }
            return material
        }

        override fun discardResult(result: ByteArray) {
            Arrays.fill(result, 0.toByte())
            discarded.countDown()
        }
    }

    @Test fun explicitCancellationDoesNotDeliverLateSecrets() {
        val task = Task()
        val callbacks = AtomicInteger()
        task.setCallback { callbacks.incrementAndGet() }
        task.execute()
        assertTrue(task.started.await(5, TimeUnit.SECONDS))
        task.cancel()
        task.release.countDown()
        assertTrue(task.discarded.await(5, TimeUnit.SECONDS))
        assertArrayEquals(ByteArray(3), task.material)
        assertEquals(0, callbacks.get())
    }

    @Test fun revocationDiscardsResultAndTaskIsOneShot() {
        val task = Task()
        val callbacks = AtomicInteger()
        task.setCallback { callbacks.incrementAndGet() }
        task.execute()
        assertTrue(task.started.await(5, TimeUnit.SECONDS))
        assertThrows(IllegalStateException::class.java) { task.execute() }
        VaultSession.lock()
        task.release.countDown()
        assertTrue(task.discarded.await(5, TimeUnit.SECONDS))
        assertArrayEquals(ByteArray(3), task.material)
        assertEquals(0, callbacks.get())
    }

    @Test fun unexpectedParserOrProviderExceptionDeliversFailureNotSuccess() {
        val delivered = CountDownLatch(1)
        val result = AtomicReference<String?>()
        val task = object : UiBasedBackgroundTask<String>("failure") {
            override fun doInBackground(): String {
                throw IllegalArgumentException("synthetic-provider-failure")
            }
        }
        task.setCallback { value ->
            result.set(value)
            delivered.countDown()
        }
        task.execute()
        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        assertEquals("failure", result.get())
    }

    private fun awaitCompleted(task: UiBasedBackgroundTask<*>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (task.getState() !is TaskState.Completed && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(task.getState() is TaskState.Completed)
    }

    @Test fun typedFailureDoesNotInventAFallbackValue() {
        val delivered = CountDownLatch(1)
        val result = AtomicReference<TaskOutcome<String>?>()
        val task = object : UiBasedBackgroundTask<String>("legacy-fallback") {
            override fun doInBackground(): String = throw IllegalArgumentException("synthetic")
        }
        task.setOutcomeCallback { outcome ->
            result.set(outcome)
            delivered.countDown()
        }
        task.execute()
        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        assertTrue(result.get() is TaskOutcome.Failure)
        assertEquals(TaskFailure.UNEXPECTED_EXCEPTION, (result.get() as TaskOutcome.Failure<String>).error)
    }

    @Test fun nullProducerResultIsTypedFailure() {
        val delivered = CountDownLatch(1)
        val result = AtomicReference<TaskOutcome<String?>?>()
        val task = object : UiBasedBackgroundTask<String?>(null) {
            override fun doInBackground(): String? = null
        }
        task.setOutcomeCallback { outcome ->
            result.set(outcome)
            delivered.countDown()
        }
        task.execute()
        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        assertEquals(TaskFailure.NULL_RESULT, (result.get() as TaskOutcome.Failure<String?>).error)
    }

    @Test fun detachedCompletionRebindsOnce() {
        val task = Task()
        task.execute()
        assertTrue(task.started.await(5, TimeUnit.SECONDS))
        task.release.countDown()
        awaitCompleted(task)
        val delivered = CountDownLatch(1)
        val calls = AtomicInteger()
        task.setOutcomeCallback { outcome ->
            assertTrue(outcome is TaskOutcome.Value)
            task.disposeOutcome(outcome)
            calls.incrementAndGet()
            delivered.countDown()
        }
        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        task.setOutcomeCallback { calls.incrementAndGet() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(1, calls.get())
        assertArrayEquals(ByteArray(3), task.material)
    }

    @Test fun keyReplacementDisposesDetachedResultBeforeRebind() {
        val task = Task()
        task.execute()
        assertTrue(task.started.await(5, TimeUnit.SECONDS))
        task.release.countDown()
        awaitCompleted(task)
        VaultSession.activate(SecretKeySpec(ByteArray(16), "AES"), VaultSession.generation())
        assertTrue(task.discarded.await(5, TimeUnit.SECONDS))
        assertArrayEquals(ByteArray(3), task.material)
        val delivered = CountDownLatch(1)
        val result = AtomicReference<TaskOutcome<ByteArray>?>()
        task.setOutcomeCallback { outcome ->
            result.set(outcome)
            delivered.countDown()
        }
        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        assertEquals(TaskCancellation.SESSION_REVOKED, (result.get() as TaskOutcome.Cancelled<ByteArray>).reason)
    }

    @Test fun cancellationBeforeSubmissionCleansOwnedInputsExactlyOnce() {
        val cleaned = AtomicInteger()
        val delivered = CountDownLatch(1)
        val task = object : UiBasedBackgroundTask<String>("fallback") {
            override fun onCancellation() {
                cleaned.incrementAndGet()
            }

            override fun doInBackground(): String {
                fail("Canceled task must not run")
                return "unexpected"
            }
        }
        task.cancel()
        task.cancel()
        task.setOutcomeCallback { outcome ->
            assertTrue(outcome is TaskOutcome.Cancelled)
            delivered.countDown()
        }
        task.execute()
        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        assertEquals(1, cleaned.get())
        assertThrows(IllegalStateException::class.java) { task.execute() }
    }
}
