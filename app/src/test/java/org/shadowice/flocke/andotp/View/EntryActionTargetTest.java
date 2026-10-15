package org.shadowice.flocke.andotp.View;

import org.junit.After;
import org.junit.Test;
import org.shadowice.flocke.andotp.Database.Entry;
import org.shadowice.flocke.andotp.Database.EntryList;
import org.shadowice.flocke.andotp.Utilities.TokenCalculator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class EntryActionTargetTest {
    private final List<Entry> owned = new ArrayList<>();
    private Entry entry(String label) {
        Entry entry = new Entry(Entry.OTPType.TOTP, "GEZDGNBVGY3TQOJQ", 30, 6, "synthetic", label,
                TokenCalculator.HashAlgorithm.SHA1, Collections.emptyList());
        owned.add(entry);
        return entry;
    }
    @After public void cleanup() { for (Entry entry : owned) entry.destroy(); }

    @Test public void reorderStillResolvesOriginalEntryNotItsOldPosition() {
        EntryList source = new EntryList();
        Entry first = entry("first"), second = entry("second");
        source.addEntry(first); source.addEntry(second);
        EntryActionTarget target = new EntryActionTarget(first, 10);
        source.swapEntries(0, 1);
        assertSame(first, target.resolve(source, 10));
        assertSame(second, source.getEntry(0));
    }

    @Test public void deletionCannotRedirectAConfirmationToTheNextRow() {
        EntryList source = new EntryList();
        Entry first = entry("first"), second = entry("second");
        source.addEntry(first); source.addEntry(second);
        EntryActionTarget target = new EntryActionTarget(first, 10);
        source.removeEntry(0);
        assertNull(target.resolve(source, 10));
        assertSame(second, source.getEntry(0));
    }

    @Test public void equalReplacementWithSameStableIdIsStillADifferentTarget() {
        EntryList source = new EntryList();
        Entry original = entry("same"), replacement = entry("same");
        source.addEntry(original);
        EntryActionTarget target = new EntryActionTarget(original, 10);
        source.addEntry(replacement, true);
        assertEquals(original, replacement);
        assertEquals(original.getListId(), replacement.getListId());
        assertNull(target.resolve(source, 10));
    }

    @Test public void newerSessionCannotReuseAHandleEvenForTheSameLiveEntry() {
        EntryList source = new EntryList();
        Entry original = entry("original");
        source.addEntry(original);
        EntryActionTarget target = new EntryActionTarget(original, 10);
        assertNull(target.resolve(source, 11));
        assertEquals("EntryActionTarget(redacted)", target.toString());
        assertTrue(original.getSecret().length > 0); // A borrowed handle never destroys its entry.
    }
    @Test public void javaBoundaryKeepsNullableEntryReturnWithoutGeneratedValueMethods() throws Exception {
        assertNotNull(EntryActionTarget.class.getConstructor(Entry.class, long.class));
        assertEquals(Entry.class, EntryActionTarget.class.getMethod("resolve", EntryList.class, long.class).getReturnType());
        for (java.lang.reflect.Method method : EntryActionTarget.class.getMethods()) {
            assertNotEquals("copy", method.getName());
            assertFalse(method.getName().startsWith("component"));
        }
        Entry original = entry("original");
        EntryActionTarget target = new EntryActionTarget(original, 10);
        assertNull(target.resolve(new EntryList(), 10));
    }

}
