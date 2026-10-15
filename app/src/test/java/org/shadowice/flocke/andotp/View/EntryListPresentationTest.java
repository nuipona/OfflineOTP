package org.shadowice.flocke.andotp.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.shadowice.flocke.andotp.Database.Entry;
import org.shadowice.flocke.andotp.Database.EntryList;
import org.shadowice.flocke.andotp.Utilities.Constants;
import org.shadowice.flocke.andotp.Utilities.TokenCalculator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import static org.junit.Assert.*;
import static org.shadowice.flocke.andotp.Utilities.Constants.SearchIncludes.*;
import static org.shadowice.flocke.andotp.Utilities.Constants.SortMode.*;
import static org.shadowice.flocke.andotp.Utilities.Constants.TagFunctionality.*;

/** Characterizes existing EntryList behavior without constructing an Activity or RecyclerView. */
public class EntryListPresentationTest {
    private EntryList source;
    private EntryListPresentation presentation;
    private Entry alpha, beta, tagless;
    private Locale locale;

    @Before public void setup() {
        locale = Locale.getDefault();
        Locale.setDefault(Locale.US);
        source = new EntryList();
        alpha = entry("alpha", "Zeta", "work", "shared");
        beta = entry("beta", "Alpha", "work");
        tagless = entry("tagless", "Delta");
        source.addEntry(alpha); source.addEntry(beta); source.addEntry(tagless);
        presentation = new EntryListPresentation(source);
    }
    @After public void cleanup() {
        presentation.clear();
        for (Entry entry : source.getEntries()) entry.destroy();
        Locale.setDefault(locale);
    }
    private Entry entry(String label, String issuer, String... tags) {
        return new Entry(Entry.OTPType.TOTP, "GEZDGNBVGY3TQOJQ", 30, 6, issuer, label,
                TokenCalculator.HashAlgorithm.SHA1, new ArrayList<>(Arrays.asList(tags)));
    }
    private void showAll() { presentation.filterByTags(Collections.emptyList(), false, AND); }
    private void search(String text, Constants.SearchIncludes... includes) {
        assertTrue(presentation.publishSearch(presentation.search(text, Arrays.asList(includes))));
    }

    @Test public void startsEmptyAndSortedProjectionBorrowsEntriesAndPreservesSourceIds() {
        assertEquals(0, presentation.size());
        long id = alpha.getListId();
        presentation.setSortMode(ISSUER);
        showAll();
        assertSame(beta, presentation.get(0));
        assertSame(tagless, presentation.get(1));
        assertSame(alpha, presentation.get(2));
        assertSame(alpha, source.getEntry(0));
        assertEquals(id, presentation.get(2).getListId());
        assertEquals(0, presentation.sourceIndex(2));
    }

    @Test public void searchRespectsConfiguredFieldsAndCase() {
        search("ALPHA", LABEL);
        assertEquals(1, presentation.size()); assertSame(alpha, presentation.get(0));
        search("ALPHA", ISSUER);
        assertEquals(1, presentation.size()); assertSame(beta, presentation.get(0));
        search("SHARED", TAGS);
        assertEquals(1, presentation.size()); assertSame(alpha, presentation.get(0));
        search("work", LABEL, ISSUER);
        assertEquals(0, presentation.size());
        search("alpha");
        assertEquals(0, presentation.size());
        search(null);
        assertEquals(3, presentation.size());
    }

    @Test public void localeSensitiveSearchIsPreservedRatherThanSilentlyChangedToRootCaseFolding() {
        Locale.setDefault(new Locale("tr", "TR"));
        alpha.setLabel("I");
        search("\u0131", LABEL);
        assertEquals(1, presentation.size()); assertSame(alpha, presentation.get(0));
        search("i", LABEL);
        assertEquals(0, presentation.size());
    }

    @Test public void tagReconciliationDropsUnusedTagsKeepsSelectionsAndSelectsNewTags() {
        java.util.Map<String, Boolean> result = presentation.reconcileTags(
                Arrays.asList("work", "removed"), Arrays.asList("removed"));
        assertEquals(2, result.size());
        assertEquals(Boolean.FALSE, result.get("work"));
        assertEquals(Boolean.TRUE, result.get("shared"));
        assertFalse(result.containsKey("removed"));
        result = presentation.reconcileTags(Arrays.asList("work", "shared"), Arrays.asList("work"));
        assertEquals(Boolean.TRUE, result.get("work"));
        assertEquals(Boolean.FALSE, result.get("shared"));
    }

    @Test public void emptyAndOrAndTaglessBehaviorIsPreserved() {
        showAll(); // Existing AND(empty) semantics include all entries, even without noTags.
        assertEquals(3, presentation.size());
        presentation.filterByTags(Collections.emptyList(), false, OR);
        assertEquals(0, presentation.size());
        presentation.filterByTags(Collections.emptyList(), true, OR);
        assertEquals(1, presentation.size()); assertSame(tagless, presentation.get(0));
        presentation.filterByTags(Arrays.asList("work", "shared"), true, AND);
        assertEquals(2, presentation.size());
        assertSame(alpha, presentation.get(0)); assertSame(tagless, presentation.get(1));
        presentation.filterByTags(Collections.singletonList("work"), false, OR);
        assertEquals(2, presentation.size());
    }

    @Test public void searchStillReplacesTagProjectionInsteadOfIntroducingCombinedSemantics() {
        presentation.filterByTags(Collections.singletonList("shared"), false, OR);
        assertEquals(1, presentation.size());
        search("beta", LABEL);
        assertEquals(1, presentation.size()); assertSame(beta, presentation.get(0));
    }

    @Test public void selectedTagsAreCopiedAndDragRequiresCompleteUnsortedProjection() {
        ArrayList<String> tags = new ArrayList<>(Collections.singletonList("shared"));
        presentation.selectTags(tags);
        tags.clear();
        presentation.refreshTags(false, OR);
        assertEquals(1, presentation.size());
        assertFalse(presentation.canDrag());
        showAll();
        assertTrue(presentation.canDrag());
        source.swapEntries(0, 2);
        presentation.showReorderedEntries();
        assertSame(tagless, presentation.get(0));
        assertTrue(presentation.canDrag());
        presentation.setSortMode(LABEL);
        showAll();
        assertFalse(presentation.canDrag());
    }

    @Test public void usageSortOnlyResortsCurrentSubsetAndDoesNotMutateSourceOrder() {
        presentation.setSortMode(MOST_USED);
        presentation.filterByTags(Collections.singletonList("work"), false, OR);
        beta.setUsedFrequency(10);
        assertTrue(presentation.resortAfterUsage());
        assertEquals(2, presentation.size()); assertSame(beta, presentation.get(0));
        assertSame(alpha, source.getEntry(0));
        presentation.setSortMode(LAST_USED);
        alpha.setLastUsed(100);
        assertTrue(presentation.resortAfterUsage()); assertSame(alpha, presentation.get(0));
        presentation.setSortMode(UNSORTED);
        assertFalse(presentation.resortAfterUsage());
    }

    @Test public void closedForeignOrAlreadyTransferredResultsCannotBePublished() {
        EntryListPresentation.SearchResult closed = presentation.search("alpha", Arrays.asList(LABEL));
        closed.close();
        assertFalse(presentation.publishSearch(closed));
        EntryListPresentation other = new EntryListPresentation(source);
        EntryListPresentation.SearchResult foreign = other.search("alpha", Arrays.asList(LABEL));
        assertFalse(presentation.publishSearch(foreign));
        EntryListPresentation.SearchResult once = presentation.search("alpha", Arrays.asList(LABEL));
        assertEquals("EntrySearchResult(redacted)", once.toString());
        assertTrue(presentation.publishSearch(once));
        once.close();
        assertEquals(1, presentation.size());
        assertFalse(presentation.publishSearch(once));
    }

    @Test public void clearingProjectionInvalidatesPendingSearchWithoutDestroyingSourceSecrets() {
        showAll();
        EntryListPresentation.SearchResult pending = presentation.search("alpha", Arrays.asList(LABEL));
        presentation.clear();
        assertFalse(presentation.publishSearch(pending));
        assertEquals(0, presentation.size());
        assertEquals(3, source.getEntries().size());
        assertTrue(alpha.getSecret().length > 0);
    }

    @Test public void tagSortAndEntryChangesInvalidatePreviouslyComputedSearches() {
        EntryListPresentation.SearchResult pending = presentation.search("alpha", Arrays.asList(LABEL));
        presentation.setSortMode(ISSUER);
        assertFalse(presentation.publishSearch(pending));
        pending = presentation.search("alpha", Arrays.asList(LABEL));
        showAll();
        assertFalse(presentation.publishSearch(pending));
        pending = presentation.search("alpha", Arrays.asList(LABEL));
        presentation.removeDisplayed(0);
        assertFalse(presentation.publishSearch(pending));
        assertTrue(alpha.getSecret().length > 0); // Display removal is not ownership destruction.
    }
}
