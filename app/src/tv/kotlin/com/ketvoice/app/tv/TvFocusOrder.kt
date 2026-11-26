package com.ketvoice.app.tv

/**
 * Where the remote's focus goes (Ketvoice TV). Pure rules, host-tested; the composables only apply them.
 */
object TvFocusOrder {
    enum class Move { Up, Down, Left, Right }

    /**
     * The next key of an on-screen grid of [count] keys laid out [columns] wide (the last row may be shorter), or null
     * when the move leaves the grid (the focus then goes to what lies beside it). Moving down onto a shorter last row
     * lands on its last key, as a TV keyboard does.
     */
    fun grid(index: Int, count: Int, columns: Int, move: Move): Int? {
        if (count <= 0 || columns <= 0 || index !in 0 until count) return null
        val row = index / columns
        val col = index % columns
        val rows = (count + columns - 1) / columns
        return when (move) {
            Move.Left -> if (col == 0) null else index - 1
            Move.Right -> if (col == columns - 1 || index == count - 1) null else index + 1
            Move.Up -> if (row == 0) null else index - columns
            Move.Down -> if (row == rows - 1) null else minOf(index + columns, count - 1)
        }
    }

    /**
     * The item a row gives the focus to when it is entered from above or below: the one focused last time when it is
     * still there, else the one at the same position, else the first.
     */
    fun entry(keys: List<String>, remembered: String?, fallbackIndex: Int = 0): Int {
        if (keys.isEmpty()) return -1
        remembered?.let { r -> keys.indexOf(r).takeIf { it >= 0 }?.let { return it } }
        return fallbackIndex.coerceIn(0, keys.lastIndex)
    }

    /**
     * Where a row scrolls so that the focused card stays at the same place on screen (Google TV's « pivot »):
     * [pivot] of the row's width from its start, never before the row's start nor past its end. Returns the scroll
     * offset (px) to apply from the item's current [itemOffset] (its leading edge, relative to the viewport). An element
     * already in view before the pivot (or within [slack] of the viewport after it) does not move anything.
     */
    fun pivotScroll(itemOffset: Float, itemSize: Float, viewport: Float, pivot: Float = PIVOT, leading: Float = 0f, slack: Float = 0f): Float {
        if (viewport <= 0f) return 0f
        val wanted = (viewport * pivot).coerceAtLeast(leading)
        // Already in view and no further than [slack] past the pivot: nothing moves (the top of a page stays put).
        if (itemOffset >= leading && itemOffset + itemSize <= viewport && itemOffset <= wanted + slack * viewport) return 0f
        // A card wider than the room left after the pivot is placed so that it fits whole.
        val target = if (wanted + itemSize > viewport) (viewport - itemSize).coerceAtLeast(leading) else wanted
        return itemOffset - target
    }

    /** Cards are kept a little in from the row's start so that the previous one shows (there is more to the left). */
    const val PIVOT = 0.08f

    /** The rows of a page: the one entered from the side menu is the first, or the remembered one. */
    fun rowEntry(rowKeys: List<String>, remembered: String?): Int = entry(rowKeys, remembered)
}

/**
 * The last focused item of each row of a page, so that coming back to a page (Back) or to a row (Up/Down) puts the
 * focus where it was. Kept per page while the page is in the back stack.
 */
class FocusMemory {
    private val items = LinkedHashMap<String, String>()
    var lastRow: String? = null
        private set

    fun focused(row: String, item: String) {
        items[row] = item
        lastRow = row
        if (items.size > MAX_ROWS) items.remove(items.keys.first())
    }

    fun item(row: String): String? = items[row]

    /** The row and item to focus when the page comes back, or null for the page's default. */
    fun restore(): Pair<String, String>? = lastRow?.let { row -> items[row]?.let { row to it } }

    fun clear() { items.clear(); lastRow = null }

    companion object {
        const val MAX_ROWS = 64
    }
}
