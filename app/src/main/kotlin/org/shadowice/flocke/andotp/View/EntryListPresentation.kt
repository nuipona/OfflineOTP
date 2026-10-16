package org.shadowice.flocke.andotp.View

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.EntryList
import org.shadowice.flocke.andotp.Utilities.Constants

import java.util.ArrayList
import java.util.HashMap

/**
 * Display-only projection over a borrowed EntryList. No Context, views, session authority or I/O.
 * Entries are borrowed, not copied/owned: clearing this projection must never destroy them.
 * Collection/query semantics remain in EntryList, including locale-sensitive search and sorting.
 */
class EntryListPresentation(private val source: EntryList) {
    private var displayed: ArrayList<Entry> = ArrayList()
    private var tags: MutableList<String> = ArrayList()
    private var sortMode: Constants.SortMode = Constants.SortMode.UNSORTED
    private var revision: Long = 0

    @Synchronized fun size(): Int = displayed.size
    @Synchronized fun get(position: Int): Entry = displayed[position]
    @Synchronized fun indexOf(entry: Entry): Int = displayed.indexOf(entry)
    @Synchronized fun sourceIndex(position: Int): Int = source.indexOf(displayed[position])
    @Synchronized fun getSortMode(): Constants.SortMode = sortMode

    @Synchronized fun setSortMode(mode: Constants.SortMode) {
        sortMode = mode
        revision++
    }

    /** Preserve existing selections, remove unused tags, and select newly introduced tags. */
    fun reconcileTags(known: List<String>, active: List<String>): HashMap<String, Boolean> {
        val inUse = source.getAllTags()
        val selection = HashMap<String, Boolean>()
        for (tag in known) if (inUse.contains(tag)) selection[tag] = false
        for (tag in active) if (inUse.contains(tag)) selection[tag] = true
        for (tag in inUse) if (!selection.containsKey(tag)) selection[tag] = true
        return selection
    }

    @Synchronized fun selectTags(selected: List<String>) {
        tags = ArrayList(selected)
        revision++
    }

    @Synchronized fun filterByTags(selected: List<String>, noTags: Boolean,
                                  functionality: Constants.TagFunctionality) {
        selectTags(selected)
        refreshTags(noTags, functionality)
    }

    @Synchronized fun refreshTags(noTags: Boolean, functionality: Constants.TagFunctionality) {
        displayed = source.getEntriesFilteredByTags(tags, noTags, functionality, sortMode)
        revision++
    }

    /** Re-sort the current subset, not the complete vault, after usage changes. */
    @Synchronized fun resortAfterUsage(): Boolean {
        if (sortMode != Constants.SortMode.LAST_USED && sortMode != Constants.SortMode.MOST_USED)
            return false
        displayed = EntryList.sortEntries(displayed, sortMode)
        revision++
        return true
    }

    @Synchronized fun canDrag(): Boolean {
        return sortMode == Constants.SortMode.UNSORTED && source.isEqual(displayed)
    }

    @Synchronized fun showReorderedEntries() {
        displayed = source.getEntries()
        revision++
    }

    @Synchronized fun removeDisplayed(position: Int) {
        displayed.removeAt(position)
        revision++
    }

    @Synchronized fun invalidateSearch() { revision++ }

    @Synchronized fun clear() {
        displayed.clear()
        tags.clear()
        revision++
    }

    /** Computes a borrowed projection; callers still enforce session/generation at publication. */
    fun search(constraint: CharSequence?, includes: List<Constants.SearchIncludes>): SearchResult {
        val version: Long
        val mode: Constants.SortMode
        synchronized(this) {
            version = revision
            mode = sortMode
        }
        // Do not hold the presentation monitor during collection work.
        val matches = source.getFilteredEntries(constraint, includes, mode)
        return SearchResult(this, version, matches)
    }

    @Synchronized fun publishSearch(result: SearchResult): Boolean {
        synchronized(result) {
            if (result.owner !== this || result.revision != revision || result.entries == null) {
                result.close()
                return false
            }
            displayed = result.entries!!
            result.entries = null
            return true
        }
    }

    /** Owns only a list of borrowed references, never the entries/seeds themselves. */
    class SearchResult(
        val owner: EntryListPresentation,
        val revision: Long,
        var entries: ArrayList<Entry>?
    ) : AutoCloseable {
        @Synchronized fun size(): Int = entries?.size ?: 0
        @Synchronized override fun close() { entries?.clear(); entries = null }
        override fun toString(): String = "EntrySearchResult(redacted)"
    }
}
