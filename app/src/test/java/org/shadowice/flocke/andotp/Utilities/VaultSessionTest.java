package org.shadowice.flocke.andotp.Utilities;

import org.junit.After;
import org.junit.Test;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

public class VaultSessionTest {
    @After public void clear() { VaultSession.lock(); }

    @Test public void lockRevokesKeyAndGeneration() {
        VaultSession.lock();
        SecretKey key = new SecretKeySpec(new byte[16], "AES");
        VaultSession.open(key);
        long epoch = VaultSession.generation();
        assertSame(key, VaultSession.getKey());
        VaultSession.lock();
        assertNull(VaultSession.getKey());
        assertFalse(VaultSession.isCurrent(epoch));
    }

    @Test public void derivedHandoffIsOneUseAndDoesNotAliasCaller() {
        VaultSession.lock();
        byte[] material = new byte[] {1, 2, 3};
        assertTrue(VaultSession.offerDerivedKey(material, VaultSession.generation()));
        material[0] = 0;
        assertArrayEquals(new byte[] {1, 2, 3}, VaultSession.takeDerivedKey());
        assertNull(VaultSession.takeDerivedKey());
    }

    @Test public void staleAuthCannotOfferKeyAfterLock() {
        long epoch = VaultSession.generation();
        VaultSession.lock();
        assertFalse(VaultSession.offerDerivedKey(new byte[16], epoch));
        assertNull(VaultSession.takeDerivedKey());
        assertTrue(VaultSession.offerDerivedKey(new byte[16], VaultSession.generation()));
        VaultSession.lock();
        assertNull(VaultSession.takeDerivedKey());
    }

    @Test public void listenersCanBeRemovedWithoutRetainingActivities() {
        int[] calls = {0};
        Runnable listener = () -> calls[0]++;
        VaultSession.addLockListener(listener);
        VaultSession.lock();
        assertEquals(1, calls[0]);
        VaultSession.removeLockListener(listener);
        VaultSession.lock();
        assertEquals(1, calls[0]);
    }
}
