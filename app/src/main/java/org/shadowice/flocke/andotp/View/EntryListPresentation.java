package org.shadowice.flocke.andotp.View;

import org.shadowice.flocke.andotp.Database.Entry;
import org.shadowice.flocke.andotp.Database.EntryList;
import org.shadowice.flocke.andotp.Utilities.Constants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Display-only projection over a borrowed EntryList. No Context, views, session authority or I/O.
 * Entries are borrowed, not copied/owned: clearing this projection must never destroy them.
 * Collection/query semantics remain in EntryList, including locale-sensitive search and sorting.
 */
public final class EntryListPresentation {
    private final EntryList source;
    private ArrayList<Entry> displayed = new ArrayList<>();
    private List<String> tags = new ArrayList<>();
    private Constants.SortMode sortMode = Constants.SortMode.UNSORTED;
    private long revision;

    public EntryListPresentation(EntryList source) { this.source = source; }

    public synchronized int size() { return displayed.size(); }
    public synchronized Entry get(int position) { return displayed.get(position); }
    public synchronized int indexOf(Entry entry) { return displayed.indexOf(entry); }
    public synchronized int sourceIndex(int position) { return source.indexOf(displayed.get(position)); }
    public synchronized Constants.SortMode getSortMode() { return sortMode; }

    public synchronized void setSortMode(Constants.SortMode mode) {
        sortMode = mode;
        revision++;
    }

    /** Preserve existing selections, remove unused tags, and select newly introduced tags. */
    public HashMap<String, Boolean> reconcileTags(List<String> known, List<String> active) {
        List<String> inUse = source.getAllTags();
        HashMap<String, Boolean> selection = new HashMap<>();
        for (String tag : known) if (inUse.contains(tag)) selection.put(tag, false);
        for (String tag : active) if (inUse.contains(tag)) selection.put(tag, true);
        for (String tag : inUse) if (!selection.containsKey(tag)) selection.put(tag, true);
        return selection;
    }

    public synchronized void selectTags(List<String> selected) {
        tags = new ArrayList<>(selected);
        revision++;
    }

    public synchronized void filterByTags(List<String> selected, boolean noTags,
                                           Constants.TagFunctionality functionality) {
        selectTags(selected);
        refreshTags(noTags, functionality);
    }

    public synchronized void refreshTags(boolean noTags, Constants.TagFunctionality functionality) {
        displayed = source.getEntriesFilteredByTags(tags, noTags, functionality, sortMode);
        revision++;
    }

    /** Re-sort the current subset, not the complete vault, after usage changes. */
    public synchronized boolean resortAfterUsage() {
        if (sortMode != Constants.SortMode.LAST_USED && sortMode != Constants.SortMode.MOST_USED)
            return false;
        displayed = EntryList.sortEntries(displayed, sortMode);
        revision++;
        return true;
    }

    public synchronized boolean canDrag() {
        return sortMode == Constants.SortMode.UNSORTED && source.isEqual(displayed);
    }

    public synchronized void showReorderedEntries() {
        displayed = source.getEntries();
        revision++;
    }

    public synchronized void removeDisplayed(int position) {
        displayed.remove(position);
        revision++;
    }

    public synchronized void invalidateSearch() { revision++; }

    public synchronized void clear() {
        displayed.clear();
        tags.clear();
        revision++;
    }

    /** Computes a borrowed projection; callers still enforce session/generation at publication. */
    public SearchResult search(CharSequence constraint, List<Constants.SearchIncludes> includes) {
        final long version;
        final Constants.SortMode mode;
        synchronized (this) { version = revision; mode = sortMode; }
        // Do not hold the presentation monitor during collection work.
        ArrayList<Entry> matches = source.getFilteredEntries(constraint, includes, mode);
        return new SearchResult(this, version, matches);
    }

    public synchronized boolean publishSearch(SearchResult result) {
        synchronized (result) {
            if (result.owner != this || result.revision != revision || result.entries == null) {
                result.close();
                return false;
            }
            displayed = result.entries;
            result.entries = null;
            return true;
        }
    }

    /** Owns only a list of borrowed references, never the entries/seeds themselves. */
    public static final class SearchResult implements AutoCloseable {
        private final EntryListPresentation owner;
        private final long revision;
        private ArrayList<Entry> entries;
        private SearchResult(EntryListPresentation owner, long revision, ArrayList<Entry> entries) {
            this.owner = owner;
            this.revision = revision;
            this.entries = entries;
        }
        public synchronized int size() { return entries == null ? 0 : entries.size(); }
        @Override public synchronized void close() { if (entries != null) entries.clear(); entries = null; }
        @Override public String toString() { return "EntrySearchResult(redacted)"; }
    }
}
