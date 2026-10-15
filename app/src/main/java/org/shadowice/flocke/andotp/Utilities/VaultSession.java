package org.shadowice.flocke.andotp.Utilities;

import javax.crypto.SecretKey;

/** Java compatibility facade over the typed, process-only Kotlin controller. */
public final class VaultSession {
    public static final Object MutationLock = new Object();
    private static final SessionController controller = new SessionController(MutationLock);
    private VaultSession() { }

    public static SessionState snapshot() { return controller.snapshot(); }
    public static SecretKey getKey() { return controller.currentKey(); }
    public static long generation() { return snapshot().getGeneration(); }
    public static boolean isCurrent(long expected) { return controller.isCurrent(expected); }
    public static SessionState.Unlocking beginUnlock() { return controller.beginUnlock(); }
    public static SessionCommandResult activate(SecretKey key, long expected) { return controller.activate(key, expected); }
    public static void fail(SessionFailure error) { controller.fail(error); }
    public static void lock() { lock(SessionLockReason.EXPLICIT); }
    public static void lock(SessionLockReason reason) { controller.lock(reason); }

    /** Legacy bootstrap adapter; new async callers must pass their captured generation to activate. */
    public static void open(SecretKey key) {
        synchronized (MutationLock) {
            if (!(activate(key, generation()) instanceof SessionCommandResult.Applied))
                throw new IllegalStateException("Session activation rejected");
        }
    }
    public static SessionCommandResult publishDerived(byte[] bytes, long expected) { return controller.publishDerived(bytes, expected); }
    public static SessionHandoff consumeDerived(long expected) { return controller.consumeDerived(expected); }
    public static boolean offerDerivedKey(byte[] bytes, long expected) {
        return bytes != null && publishDerived(bytes, expected) instanceof SessionCommandResult.Applied;
    }
    /** Legacy one-use transport adapter; callers own and must wipe the returned array. */
    public static byte[] takeDerivedKey() {
        return takeDerivedKey(generation());
    }
    public static byte[] takeDerivedKey(long expected) {
        SessionHandoff result = consumeDerived(expected);
        if (!(result instanceof SessionHandoff.Available)) return null;
        try (SessionHandoff.Available available = (SessionHandoff.Available) result) {
            return available.getSecret().takeBytes();
        }
    }
    public static void addChangeListener(Runnable listener) { controller.addChangeListener(listener); }
    public static void removeChangeListener(Runnable listener) { controller.removeChangeListener(listener); }
    public static void addLockListener(Runnable listener) { controller.addLockListener(listener); }
    public static void removeLockListener(Runnable listener) { controller.removeLockListener(listener); }
}
