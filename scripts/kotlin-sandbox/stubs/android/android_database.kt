// Sandbox-only stand-ins, see android_content.kt.
package android.database

/**
 * Column-accessor-only stand-in for the document metadata query in the storage helper. Like the
 * platform interface it is `Closeable` (not just `AutoCloseable`), so `use` resolves the same way.
 */
interface Cursor : java.io.Closeable {
    fun moveToFirst(): Boolean
    fun moveToNext(): Boolean
    fun getColumnIndex(columnName: String?): Int
    fun getColumnIndexOrThrow(columnName: String?): Int
    fun getString(columnIndex: Int): String?
    fun getInt(columnIndex: Int): Int
    fun isNull(columnIndex: Int): Boolean
    override fun close()
}
