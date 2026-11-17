package com.ketvoice.app.data

import java.io.File

/**
 * What the Canvas files on the device use and what goes when the listener lowers the limit or clears the cache (6.2.3).
 * Pure functions over lists of files; [CanvasCache] reads the real folder and applies them.
 */
object CanvasCachePlan {
    /** The limits offered, in MB. */
    val LIMITS_MB = listOf(256, 512, 1024, 2048)
    const val DEFAULT_MB = 512

    /** One file of the folder: [kept] when a downloaded title depends on it. */
    data class Item(val name: String, val bytes: Long, val modified: Long, val kept: Boolean)

    data class Usage(val keptBytes: Long, val otherBytes: Long) {
        val totalBytes: Long get() = keptBytes + otherBytes
    }

    /** A stored or restored value is brought to the nearest limit offered (never 0, never huge). */
    fun clampMb(value: Int): Int = LIMITS_MB.minByOrNull { kotlin.math.abs(it - value) } ?: DEFAULT_MB

    fun limitBytes(mb: Int): Long = clampMb(mb) * 1024L * 1024L

    private fun counts(item: Item) = !item.name.endsWith(".part")

    /** What the folder holds, split into what downloaded titles need and the rest. Unfinished writes are not counted. */
    fun usage(items: List<Item>): Usage {
        val live = items.filter(::counts)
        return Usage(live.filter { it.kept }.sumOf { it.bytes }, live.filterNot { it.kept }.sumOf { it.bytes })
    }

    /**
     * The files to delete so that the folder fits in 90 % of [limitBytes]: the least recently used first, never one a
     * download depends on. Nothing while the folder is within the limit.
     */
    fun overLimit(items: List<Item>, limitBytes: Long): List<Item> =
        DiskBudget.leastRecentlyUsed(items.filter(::counts), limitBytes, { it.bytes }, { it.modified }, { it.kept })

    /** The files « Vider le cache » deletes: everything but what downloaded titles depend on, and writes still in flight. */
    fun toClear(items: List<Item>): List<Item> = items.filter { counts(it) && !it.kept }

    /** « 312 Mo » / « 1,4 Go », as the settings show sizes. */
    fun sizeLabel(bytes: Long, locale: java.util.Locale = java.util.Locale.FRANCE): String = when {
        bytes <= 0L -> "0 Mo"
        bytes < 1_000_000L -> "< 1 Mo"
        bytes < 1_000_000_000L -> "${(bytes + 500_000L) / 1_000_000L} Mo"
        else -> String.format(locale, "%.1f Go", bytes / 1_000_000_000.0)
    }

    fun limitLabel(mb: Int): String = if (mb >= 1024) "${mb / 1024} Go" else "$mb Mo"

    fun items(dir: File, kept: (File) -> Boolean): List<Item> =
        dir.listFiles()?.filter { it.isFile }?.map { Item(it.name, it.length(), it.lastModified(), kept(it)) }.orEmpty()
}
