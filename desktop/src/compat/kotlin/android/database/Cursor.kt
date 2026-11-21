package android.database

import java.io.Closeable

/** The read-only Cursor surface the shared code uses, over rows held in memory. */
interface Cursor : Closeable {
    val count: Int
    fun moveToFirst(): Boolean
    fun moveToNext(): Boolean
    fun getColumnIndex(columnName: String): Int
    fun getString(columnIndex: Int): String?
    fun getLong(columnIndex: Int): Long
    fun isNull(columnIndex: Int): Boolean

    companion object {
        fun ofRow(columns: Array<String>, row: List<Any?>): Cursor = RowsCursor(columns, listOf(row))
    }
}

private class RowsCursor(private val columns: Array<String>, private val rows: List<List<Any?>>) : Cursor {
    private var at = -1
    override val count get() = rows.size
    override fun moveToFirst() = rows.isNotEmpty().also { at = 0 }
    override fun moveToNext() = (at + 1 < rows.size).also { if (it) at++ }
    override fun getColumnIndex(columnName: String) = columns.indexOf(columnName)
    override fun getString(columnIndex: Int) = rows[at][columnIndex]?.toString()
    override fun getLong(columnIndex: Int) = (rows[at][columnIndex] as? Number)?.toLong() ?: 0L
    override fun isNull(columnIndex: Int) = rows[at][columnIndex] == null
    override fun close() = Unit
}
