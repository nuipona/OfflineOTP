package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class SessionContractsTest {
    private fun controller() = SessionController(Any())
    private fun key() = SecretKeySpec(ByteArray(16), "AES")

    @Test fun unlockActivationAndReplacementAdvanceEpoch() {
        val session = controller()
        assertTrue(session.snapshot() is SessionState.Locked)
        val attempt = session.beginUnlock()
        assertNull(session.currentKey())
        val first = key()
        assertTrue(session.activate(first, attempt.generation) is SessionCommandResult.Applied)
        assertSame(first, session.currentKey())
        val epoch = session.snapshot().generation
        session.activate(first, epoch)
        assertEquals(epoch, session.snapshot().generation)
        session.activate(key(), epoch)
        assertFalse(session.isCurrent(epoch))
    }

    @Test fun staleActivationAndFailedSessionNeverInstallKey() {
        val session = controller()
        val epoch = session.beginUnlock().generation
        session.lock(SessionLockReason.SCREEN_OFF)
        val stale = session.activate(key(), epoch) as SessionCommandResult.Rejected
        assertEquals(SessionRejection.STALE_GENERATION, stale.reason)
        session.fail(SessionFailure.RECOVERY_REQUIRED)
        val failed = session.activate(key(), session.snapshot().generation) as SessionCommandResult.Rejected
        assertEquals(SessionRejection.FAILED_SESSION, failed.reason)
        assertNull(session.currentKey())
        session.beginUnlock() // A fresh attempt is explicit, never an implicit Failed -> Unlocked.
        assertTrue(session.snapshot() is SessionState.Unlocking)
    }

    @Test fun ownedSecretCopiesTransfersOnceAndRedacts() {
        val source = byteArrayOf(1, 2, 3)
        val secret = OwnedSecret.copyOf(source)
        source.fill(0)
        assertEquals("OwnedSecret(redacted)", secret.toString())
        val transferred = secret.takeBytes()
        assertArrayEquals(byteArrayOf(1, 2, 3), transferred)
        secret.close()
        assertArrayEquals(byteArrayOf(1, 2, 3), transferred) // Ownership belongs to the caller now.
        assertThrows(IllegalStateException::class.java) { secret.takeBytes() }
        transferred.fill(0)
    }

    @Test fun closeRejectsFutureAccessAndBoundsAreChecked() {
        val secret = OwnedSecret.copyOf(ByteArray(16))
        secret.close()
        secret.close()
        assertThrows(IllegalStateException::class.java) { secret.takeBytes() }
        assertThrows(IllegalArgumentException::class.java) { OwnedSecret.copyOf(ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { OwnedSecret.copyOf(ByteArray(65)) }
    }

    @Test fun staleConsumerCannotStealNewerHandoffAndRevocationClearsIt() {
        val session = controller()
        val epoch = session.beginUnlock().generation
        assertTrue(session.publishDerived(byteArrayOf(7), epoch) is SessionCommandResult.Applied)
        assertEquals(SessionHandoff.Reason.STALE_GENERATION,
            (session.consumeDerived(epoch - 1) as SessionHandoff.Unavailable).reason)
        val owned = session.consumeDerived(epoch) as SessionHandoff.Available
        assertArrayEquals(byteArrayOf(7), owned.secret.takeBytes().also { owned.close() })
        assertEquals(SessionHandoff.Reason.MISSING,
            (session.consumeDerived(epoch) as SessionHandoff.Unavailable).reason)
        session.publishDerived(byteArrayOf(8), epoch)
        session.lock(SessionLockReason.EXPLICIT)
        assertTrue(session.consumeDerived(session.snapshot().generation) is SessionHandoff.Unavailable)
        assertEquals(SessionRejection.STALE_GENERATION,
            (session.publishDerived(byteArrayOf(9), epoch) as SessionCommandResult.Rejected).reason)
    }

    @Test fun invalidOrFailedHandoffIsRejected() {
        val session = controller()
        assertEquals(SessionRejection.INVALID_HANDOFF,
            (session.publishDerived(ByteArray(65), 0) as SessionCommandResult.Rejected).reason)
        session.fail(SessionFailure.VAULT_UNAVAILABLE)
        assertEquals(SessionRejection.FAILED_SESSION,
            (session.publishDerived(ByteArray(16), session.snapshot().generation) as SessionCommandResult.Rejected).reason)
    }

    @Test fun listenerFailuresDoNotSkipCleanupAndReplacementIsNotALockEvent() {
        val session = controller()
        var locks = 0
        var changes = 0
        val bad = Runnable { throw IllegalStateException("synthetic") }
        val observer = Runnable { changes++; assertNotNull(session.snapshot()) }
        session.addLockListener(bad)
        session.addLockListener(Runnable { locks++ })
        session.addChangeListener(observer)
        session.activate(key(), 0)
        assertEquals(0, locks)
        assertEquals(1, changes)
        session.lock(SessionLockReason.BACKGROUND)
        assertEquals(1, locks)
        assertEquals(2, changes)
        session.removeChangeListener(observer)
        session.lock(SessionLockReason.RESET)
        assertEquals(2, changes)
    }
}
