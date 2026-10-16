// Sandbox-only stand-ins, see android_content.kt.
package android.widget

/** The adapter that can filter its own contents. */
interface Filterable {
    fun getFilter(): Filter
}

/**
 * The search filter of the entry list. The stand-in only carries the result values; the
 * filtering itself is done by the converted presentation layer.
 */
abstract class Filter {
    /** The result of a filtering run: the number of matches and the matched values. */
    class FilterResults {
        /** The number of matches. */
        @JvmField var count: Int = 0

        /** The matched values. */
        @JvmField var values: Any? = null
    }

    /** The constraints the filter was run with, in order. */
    val constraints: MutableList<CharSequence?> = ArrayList()

    /** Runs the filter; the platform publishes the results to the listener. */
    fun filter(constraint: CharSequence?) {
        constraints.add(constraint)
        publishResults(constraint, performFiltering(constraint))
    }

    abstract fun performFiltering(constraint: CharSequence?): FilterResults

    abstract fun publishResults(constraint: CharSequence?, results: FilterResults)
}
