package org.shadowice.flocke.andotp.Utilities

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class VaultSessionTest {
    @After fun clear() {
        VaultSession.lock()
    }

    @Test fun lockRevokesKeyAndGeneration() {
        VaultSession.lock()
        val key: SecretKey = SecretKeySpec(ByteArray(16), "AES")
        VaultSession.open(key)
        val epoch = VaultSession.generation()
        assertSame(key, VaultSession.getKey())
        VaultSession.lock()
        assertNull(VaultSession.getKey())
        assertFalse(VaultSession.isCurrent(epoch))
    }

    @Test fun derivedHandoffIsOneUseAndDoesNotAliasCaller() {
        VaultSession.lock()
        val material = byteArrayOf(1, 2, 3)
        assertTrue(VaultSession.offerDerivedKey(material, VaultSession.generation()))
        material[0] = 0
        assertArrayEquals(byteArrayOf(1, 2, 3), VaultSession.takeDerivedKey())
        assertNull(VaultSession.takeDerivedKey())
    }

    @Test fun staleAuthCannotOfferKeyAfterLock() {
        val epoch = VaultSession.generation()
        VaultSession.lock()
        assertFalse(VaultSession.offerDerivedKey(ByteArray(16), epoch))
        assertNull(VaultSession.takeDerivedKey())
        assertTrue(VaultSession.offerDerivedKey(ByteArray(16), VaultSession.generation()))
        VaultSession.lock()
        assertNull(VaultSession.takeDerivedKey())
    }

    @Test fun listenersCanBeRemovedWithoutRetainingActivities() {
        var calls = 0
        val listener = Runnable { calls++ }
        VaultSession.addLockListener(listener)
        VaultSession.lock()
        assertEquals(1, calls)
        VaultSession.removeLockListener(listener)
        VaultSession.lock()
        assertEquals(1, calls)
    }
}
