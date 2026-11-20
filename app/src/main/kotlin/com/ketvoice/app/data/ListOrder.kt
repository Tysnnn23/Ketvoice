package com.ketvoice.app.data

import android.content.Context

/**
 * « Recherche et tri dans les listes » (7.5): how a playlist, Favoris and Téléchargements can be ordered.
 * One model for the three lists (Downloads' sorts of 7.4 moved here). [descendingFirst] is the direction a
 * sort takes when it is chosen: the newest additions and the largest files first, names from A.
 */
enum class ListSort(val label: String, val descendingFirst: Boolean) {
    Added("Date d'ajout", true), Title("Titre", false), Artist("Artiste", false), Album("Album", false),
    Duration("Durée", false), Size("Taille", true)
}

data class ListOrder(val sort: ListSort, val descending: Boolean = sort.descendingFirst) {
    /** Choosing a sort: the same one again turns its direction around, another starts in its own direction. */
    fun choose(next: ListSort): ListOrder = if (next == sort) copy(descending = !descending) else ListOrder(next)

    val code: String get() = sort.name + if (descending) ":desc" else ":asc"

    companion object {
        fun parse(code: String?, fallback: ListOrder): ListOrder {
            val sort = ListSort.entries.firstOrNull { it.name == code?.substringBefore(':') } ?: return fallback
            return ListOrder(sort, code?.substringAfter(':', "") == "desc")
        }
    }
}

/** What a list's entries are sorted and searched by. [added] is their rank in the order they were added. */
class ListFields<T>(val title: (T) -> String, val artist: (T) -> String, val album: (T) -> String,
                    val durationMs: (T) -> Long, val added: (T) -> Long, val bytes: (T) -> Long = { 0L })

object ListOrders {
    private const val PREFS = "ketvoice_list_orders"

    /**
     * Whether an entry matches a search: every word of [query], accents and case aside ([SearchRank.fold]),
     * starts a word of its title, artist or album. « beyonce » finds « Beyoncé », « halo bey » finds « Halo · Beyoncé ».
     */
    fun matches(query: String, title: String, artist: String, album: String): Boolean {
        val words = fold(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val text = fold("$title $artist $album").split(' ')
        return words.all { word -> text.any { it.startsWith(word) } }
    }

    /** [SearchRank.fold], with « Œ » and « Æ » read as the two letters people type: « oeuvre » finds « Œuvre ». */
    private fun fold(value: String): String = SearchRank.fold(value.replace("Œ", "oe").replace("œ", "oe").replace("Æ", "ae").replace("æ", "ae"))

    /** The entries matching [query], in [order]. Sorting is stable: equal entries keep the list's own order. */
    fun <T> arrange(items: List<T>, query: String, order: ListOrder, fields: ListFields<T>): List<T> {
        val found = if (query.isBlank()) items else items.filter { matches(query, fields.title(it), fields.artist(it), fields.album(it)) }
        val names = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
        val by: Comparator<T> = when (order.sort) {
            ListSort.Added -> compareBy(fields.added)
            ListSort.Title -> compareBy<T, String>(names, fields.title).thenBy(names, fields.artist)
            ListSort.Artist -> compareBy<T, String>(names, fields.artist).thenBy(names, fields.album).thenBy(names, fields.title)
            ListSort.Album -> compareBy<T, String>(names, fields.album).thenBy(names, fields.title)
            ListSort.Duration -> compareBy(fields.durationMs)
            ListSort.Size -> compareBy(fields.bytes)
        }
        return found.sortedWith(if (order.descending) by.reversed() else by)
    }

    /** The order the listener last chose for the list [key] (a playlist, `liked`, `downloads`), kept on this device. */
    fun load(context: Context, key: String, fallback: ListOrder): ListOrder = runCatching {
        ListOrder.parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null), fallback)
    }.getOrDefault(fallback)

    fun save(context: Context, key: String, order: ListOrder) {
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, order.code).apply() }
    }

    /** Fields of a title as Favoris holds it; [rank] is where it was added. */
    fun tracks(rank: (Track) -> Long): ListFields<Track> =
        ListFields({ it.title }, { it.artist }, { it.album }, { it.durationMs }, rank)
}
