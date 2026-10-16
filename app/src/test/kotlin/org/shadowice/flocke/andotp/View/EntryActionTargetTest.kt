package org.shadowice.flocke.andotp.View

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.EntryList
import org.shadowice.flocke.andotp.Utilities.TokenCalculator

class EntryActionTargetTest {
    private val owned = mutableListOf<Entry>()

    private fun entry(label: String): Entry {
        val entry = Entry(
            Entry.OTPType.TOTP,
            "GEZDGNBVGY3TQOJQ",
            30,
            6,
            "synthetic",
            label,
            TokenCalculator.HashAlgorithm.SHA1,
            mutableListOf()
        )
        owned.add(entry)
        return entry
    }

    @After fun cleanup() {
        owned.forEach { it.destroy() }
    }

    @Test fun reorderStillResolvesOriginalEntryNotItsOldPosition() {
        val source = EntryList()
        val first = entry("first")
        val second = entry("second")
        source.addEntry(first)
        source.addEntry(second)
        val target = EntryActionTarget(first, 10)
        source.swapEntries(0, 1)
        assertSame(first, target.resolve(source, 10))
        assertSame(second, source.getEntry(0))
    }

    @Test fun deletionCannotRedirectAConfirmationToTheNextRow() {
        val source = EntryList()
        val first = entry("first")
        val second = entry("second")
        source.addEntry(first)
        source.addEntry(second)
        val target = EntryActionTarget(first, 10)
        source.removeEntry(0)
        assertNull(target.resolve(source, 10))
        assertSame(second, source.getEntry(0))
    }

    @Test fun equalReplacementWithSameStableIdIsStillADifferentTarget() {
        val source = EntryList()
        val original = entry("same")
        val replacement = entry("same")
        source.addEntry(original)
        val target = EntryActionTarget(original, 10)
        source.addEntry(replacement, true)
        assertEquals(original, replacement)
        assertEquals(original.getListId(), replacement.getListId())
        assertNull(target.resolve(source, 10))
    }

    @Test fun newerSessionCannotReuseAHandleEvenForTheSameLiveEntry() {
        val source = EntryList()
        val original = entry("original")
        source.addEntry(original)
        val target = EntryActionTarget(original, 10)
        assertNull(target.resolve(source, 11))
        assertEquals("EntryActionTarget(redacted)", target.toString())
        assertTrue(original.getSecret()!!.isNotEmpty()) // A borrowed handle never destroys its entry.
    }

    @Test fun javaBoundaryKeepsNullableEntryReturnWithoutGeneratedValueMethods() {
        assertNotNull(EntryActionTarget::class.java.getConstructor(Entry::class.java, java.lang.Long.TYPE))
        assertEquals(
            Entry::class.java,
            EntryActionTarget::class.java.getMethod("resolve", EntryList::class.java, java.lang.Long.TYPE).returnType
        )
        for (method in EntryActionTarget::class.java.methods) {
            assertNotEquals("copy", method.name)
            assertFalse(method.name.startsWith("component"))
        }
        val original = entry("original")
        val target = EntryActionTarget(original, 10)
        assertNull(target.resolve(EntryList(), 10))
    }
}
