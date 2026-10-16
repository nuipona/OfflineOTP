package org.shadowice.flocke.andotp.View

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.EntryList
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import java.util.Locale

/** Characterizes existing EntryList behavior without constructing an Activity or RecyclerView. */
class EntryListPresentationTest {
    private lateinit var source: EntryList
    private lateinit var presentation: EntryListPresentation
    private lateinit var alpha: Entry
    private lateinit var beta: Entry
    private lateinit var tagless: Entry
    private lateinit var locale: Locale

    @Before fun setup() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        source = EntryList()
        alpha = entry("alpha", "Zeta", "work", "shared")
        beta = entry("beta", "Alpha", "work")
        tagless = entry("tagless", "Delta")
        source.addEntry(alpha)
        source.addEntry(beta)
        source.addEntry(tagless)
        presentation = EntryListPresentation(source)
    }

    @After fun cleanup() {
        presentation.clear()
        source.getEntries().forEach { it.destroy() }
        Locale.setDefault(locale)
    }

    private fun entry(label: String, issuer: String, vararg tags: String): Entry = Entry(
        Entry.OTPType.TOTP,
        "GEZDGNBVGY3TQOJQ",
        30,
        6,
        issuer,
        label,
        TokenCalculator.HashAlgorithm.SHA1,
        tags.toMutableList()
    )

    private fun showAll() {
        presentation.filterByTags(emptyList(), false, Constants.TagFunctionality.AND)
    }

    private fun search(text: String?, vararg includes: Constants.SearchIncludes) {
        assertTrue(presentation.publishSearch(presentation.search(text, includes.toList())))
    }

    @Test fun startsEmptyAndSortedProjectionBorrowsEntriesAndPreservesSourceIds() {
        assertEquals(0, presentation.size())
        val id = alpha.getListId()
        presentation.setSortMode(Constants.SortMode.ISSUER)
        showAll()
        assertSame(beta, presentation.get(0))
        assertSame(tagless, presentation.get(1))
        assertSame(alpha, presentation.get(2))
        assertSame(alpha, source.getEntry(0))
        assertEquals(id, presentation.get(2).getListId())
        assertEquals(0, presentation.sourceIndex(2))
    }

    @Test fun searchRespectsConfiguredFieldsAndCase() {
        search("ALPHA", Constants.SearchIncludes.LABEL)
        assertEquals(1, presentation.size())
        assertSame(alpha, presentation.get(0))
        search("ALPHA", Constants.SearchIncludes.ISSUER)
        assertEquals(1, presentation.size())
        assertSame(beta, presentation.get(0))
        search("SHARED", Constants.SearchIncludes.TAGS)
        assertEquals(1, presentation.size())
        assertSame(alpha, presentation.get(0))
        search("work", Constants.SearchIncludes.LABEL, Constants.SearchIncludes.ISSUER)
        assertEquals(0, presentation.size())
        search("alpha")
        assertEquals(0, presentation.size())
        search(null)
        assertEquals(3, presentation.size())
    }

    @Test fun localeSensitiveSearchIsPreservedRatherThanSilentlyChangedToRootCaseFolding() {
        Locale.setDefault(Locale("tr", "TR"))
        alpha.setLabel("I")
        search("\u0131", Constants.SearchIncludes.LABEL)
        assertEquals(1, presentation.size())
        assertSame(alpha, presentation.get(0))
        search("i", Constants.SearchIncludes.LABEL)
        assertEquals(0, presentation.size())
    }

    @Test fun tagReconciliationDropsUnusedTagsKeepsSelectionsAndSelectsNewTags() {
        var result = presentation.reconcileTags(listOf("work", "removed"), listOf("removed"))
        assertEquals(2, result.size)
        assertEquals(false, result["work"])
        assertEquals(true, result["shared"])
        assertFalse(result.containsKey("removed"))
        result = presentation.reconcileTags(listOf("work", "shared"), listOf("work"))
        assertEquals(true, result["work"])
        assertEquals(false, result["shared"])
    }

    @Test fun emptyAndOrAndTaglessBehaviorIsPreserved() {
        showAll() // Existing AND(empty) semantics include all entries, even without noTags.
        assertEquals(3, presentation.size())
        presentation.filterByTags(emptyList(), false, Constants.TagFunctionality.OR)
        assertEquals(0, presentation.size())
        presentation.filterByTags(emptyList(), true, Constants.TagFunctionality.OR)
        assertEquals(1, presentation.size())
        assertSame(tagless, presentation.get(0))
        presentation.filterByTags(listOf("work", "shared"), true, Constants.TagFunctionality.AND)
        assertEquals(2, presentation.size())
        assertSame(alpha, presentation.get(0))
        assertSame(tagless, presentation.get(1))
        presentation.filterByTags(listOf("work"), false, Constants.TagFunctionality.OR)
        assertEquals(2, presentation.size())
    }

    @Test fun searchStillReplacesTagProjectionInsteadOfIntroducingCombinedSemantics() {
        presentation.filterByTags(listOf("shared"), false, Constants.TagFunctionality.OR)
        assertEquals(1, presentation.size())
        search("beta", Constants.SearchIncludes.LABEL)
        assertEquals(1, presentation.size())
        assertSame(beta, presentation.get(0))
    }

    @Test fun selectedTagsAreCopiedAndDragRequiresCompleteUnsortedProjection() {
        val tags = arrayListOf("shared")
        presentation.selectTags(tags)
        tags.clear()
        presentation.refreshTags(false, Constants.TagFunctionality.OR)
        assertEquals(1, presentation.size())
        assertFalse(presentation.canDrag())
        showAll()
        assertTrue(presentation.canDrag())
        source.swapEntries(0, 2)
        presentation.showReorderedEntries()
        assertSame(tagless, presentation.get(0))
        assertTrue(presentation.canDrag())
        presentation.setSortMode(Constants.SortMode.LABEL)
        showAll()
        assertFalse(presentation.canDrag())
    }

    @Test fun usageSortOnlyResortsCurrentSubsetAndDoesNotMutateSourceOrder() {
        presentation.setSortMode(Constants.SortMode.MOST_USED)
        presentation.filterByTags(listOf("work"), false, Constants.TagFunctionality.OR)
        beta.setUsedFrequency(10)
        assertTrue(presentation.resortAfterUsage())
        assertEquals(2, presentation.size())
        assertSame(beta, presentation.get(0))
        assertSame(alpha, source.getEntry(0))
        presentation.setSortMode(Constants.SortMode.LAST_USED)
        alpha.setLastUsed(100)
        assertTrue(presentation.resortAfterUsage())
        assertSame(alpha, presentation.get(0))
        presentation.setSortMode(Constants.SortMode.UNSORTED)
        assertFalse(presentation.resortAfterUsage())
    }

    @Test fun closedForeignOrAlreadyTransferredResultsCannotBePublished() {
        val closed = presentation.search("alpha", listOf(Constants.SearchIncludes.LABEL))
        closed.close()
        assertFalse(presentation.publishSearch(closed))
        val other = EntryListPresentation(source)
        val foreign = other.search("alpha", listOf(Constants.SearchIncludes.LABEL))
        assertFalse(presentation.publishSearch(foreign))
        val once = presentation.search("alpha", listOf(Constants.SearchIncludes.LABEL))
        assertEquals("EntrySearchResult(redacted)", once.toString())
        assertTrue(presentation.publishSearch(once))
        once.close()
        assertEquals(1, presentation.size())
        assertFalse(presentation.publishSearch(once))
    }

    @Test fun clearingProjectionInvalidatesPendingSearchWithoutDestroyingSourceSecrets() {
        showAll()
        val pending = presentation.search("alpha", listOf(Constants.SearchIncludes.LABEL))
        presentation.clear()
        assertFalse(presentation.publishSearch(pending))
        assertEquals(0, presentation.size())
        assertEquals(3, source.getEntries().size)
        assertTrue(alpha.getSecret()!!.isNotEmpty())
    }

    @Test fun tagSortAndEntryChangesInvalidatePreviouslyComputedSearches() {
        var pending = presentation.search("alpha", listOf(Constants.SearchIncludes.LABEL))
        presentation.setSortMode(Constants.SortMode.ISSUER)
        assertFalse(presentation.publishSearch(pending))
        pending = presentation.search("alpha", listOf(Constants.SearchIncludes.LABEL))
        showAll()
        assertFalse(presentation.publishSearch(pending))
        pending = presentation.search("alpha", listOf(Constants.SearchIncludes.LABEL))
        presentation.removeDisplayed(0)
        assertFalse(presentation.publishSearch(pending))
        assertTrue(alpha.getSecret()!!.isNotEmpty()) // Display removal is not ownership destruction.
    }
}
