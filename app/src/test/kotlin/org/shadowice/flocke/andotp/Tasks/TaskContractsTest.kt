package org.shadowice.flocke.andotp.Tasks

import org.junit.Assert.*
import org.junit.Test

class TaskContractsTest {
    @Test fun valueLifecycleIsOneShot() {
        val task = TaskController(10)
        assertTrue(task.snapshot() is TaskState.Ready)
        assertEquals(TaskStart.STARTED, task.start())
        assertTrue(task.snapshot() is TaskState.Running)
        assertEquals(TaskStart.ALREADY_SUBMITTED, task.start())
        assertTrue(task.complete(TaskCompletion.VALUE))
        assertTrue(task.snapshot() is TaskState.Completed)
        assertTrue(task.claimDelivery())
        assertEquals(TaskCompletion.VALUE, (task.snapshot() as TaskState.Delivered).completion)
        assertTrue(task.deliveryConsumed())
        assertFalse(task.claimDelivery())
        assertFalse(task.cancel(TaskCancellation.EXPLICIT))
    }

    @Test fun failureCompletionIsNotAValue() {
        val task = TaskController(0)
        task.start()
        task.complete(TaskCompletion.FAILURE)
        task.claimDelivery()
        assertEquals(TaskCompletion.FAILURE, (task.snapshot() as TaskState.Delivered).completion)
        assertEquals("TaskOutcome.Value(redacted)", TaskOutcome.Value("synthetic-secret").toString())
        assertEquals(TaskFailure.NULL_RESULT, TaskOutcome.Failure<String>(TaskFailure.NULL_RESULT).error)
    }

    @Test fun preStartCancellationStillDeliversOnlyOnce() {
        val task = TaskController(0)
        assertTrue(task.cancel(TaskCancellation.EXPLICIT))
        assertEquals(TaskStart.CANCELED, task.start())
        assertFalse(task.complete(TaskCompletion.VALUE))
        assertTrue(task.claimDelivery())
        assertFalse(task.claimDelivery())
        assertTrue(task.deliveryConsumed())
        assertEquals(TaskStart.ALREADY_SUBMITTED, task.start())
    }

    @Test fun revocationReplacesPendingCompletionButNotDeliveredCompletion() {
        val task = TaskController(5)
        task.start()
        task.complete(TaskCompletion.VALUE)
        task.observeGeneration(6)
        assertEquals(TaskCancellation.SESSION_REVOKED, (task.snapshot() as TaskState.Cancelled).reason)
        assertTrue(task.claimDelivery())
        assertFalse(task.complete(TaskCompletion.FAILURE))
        val delivered = TaskController(5)
        delivered.start()
        delivered.complete(TaskCompletion.VALUE)
        delivered.claimDelivery()
        delivered.observeGeneration(6)
        assertTrue(delivered.snapshot() is TaskState.Delivered)
    }

    @Test fun firstCancellationReasonWins() {
        val task = TaskController(1)
        task.cancel(TaskCancellation.EXPLICIT)
        task.observeGeneration(2)
        assertEquals(TaskCancellation.EXPLICIT, (task.snapshot() as TaskState.Cancelled).reason)
        assertFalse(task.cancel(TaskCancellation.SESSION_REVOKED))
    }

    @Test fun unstartedTaskCannotCompleteOrClaimDelivery() {
        val task = TaskController(0)
        assertFalse(task.complete(TaskCompletion.VALUE))
        assertFalse(task.claimDelivery())
        assertFalse(task.deliveryConsumed())
    }

    @Test fun verifiedOutcomeOwnsACopyAndIsRedacted() {
        val bytes = byteArrayOf(1, 2)
        val outcome = AuthenticationOutcome.verified(bytes, 42)
        bytes.fill(0)
        assertEquals(42L, outcome.generation)
        assertEquals("AuthenticationOutcome.Verified(redacted)", outcome.toString())
        val material = outcome.key.takeBytes()
        assertArrayEquals(byteArrayOf(1, 2), material)
        material.fill(0)
        outcome.close()
        assertThrows(IllegalStateException::class.java) { outcome.key.takeBytes() }
    }
}
