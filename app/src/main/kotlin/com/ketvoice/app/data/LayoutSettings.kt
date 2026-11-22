package com.ketvoice.app.data

import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * An ordered choice of ids, some shown and some not: the tabs of the bar, Home's sections, the player's buttons, the
 * title menu. Stored as `a,-b,c` (every known id in its order, `-` before a hidden one); an empty text is the
 * default. Unknown ids (from a newer version) are dropped, ids this version knows but the text lacks take their
 * default place and visibility, and a text that breaks the limits reads as the default.
 */
internal class LayoutList(
    val all: List<String>,
    val defaultShown: Set<String>,
    val minShown: Int = 0,
    val maxShown: Int = Int.MAX_VALUE,
    /** Always shown. */
    val required: Set<String> = emptySet(),
    /** At least one of these is shown. */
    val anyOf: Set<String> = emptySet()
) {
    data class Entry(val id: String, val shown: Boolean)

    val defaults: List<Entry> = all.map { Entry(it, it in defaultShown) }

    fun allowed(entries: List<Entry>): Boolean {
        val shown = entries.filter { it.shown }.map { it.id }
        return shown.size in minShown..maxShown && shown.containsAll(required) && (anyOf.isEmpty() || shown.any { it in anyOf })
    }

    /** The entries [text] holds, or the defaults when it is empty, malformed or out of the limits. */
    fun parse(text: String?): List<Entry> = read(text) ?: defaults

    /** The entries [text] holds, or null when it is empty, malformed or out of the limits. */
    private fun read(text: String?): List<Entry>? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > MAX_TEXT) return null
        val read = mutableListOf<Entry>()
        for (token in raw.split(',')) {
            val hidden = token.startsWith('-')
            val id = token.removePrefix("-")
            if (id in all && read.none { it.id == id }) read += Entry(id, !hidden)
        }
        if (read.isEmpty()) return null
        // What this version knows and the text does not (an item added later): right after the item it follows by
        // default, or first, with its default visibility.
        all.forEachIndexed { index, id ->
            if (read.none { it.id == id }) {
                val after = all.subList(0, index).lastOrNull { prev -> read.any { it.id == prev } }
                read.add(if (after == null) 0 else read.indexOfFirst { it.id == after } + 1, Entry(id, id in defaultShown))
            }
        }
        return read.takeIf(::allowed)
    }

    fun encode(entries: List<Entry>): String =
        if (entries == defaults || !allowed(entries)) "" else entries.joinToString(",") { (if (it.shown) "" else "-") + it.id }

    /** Whether [text] may be stored (a backup or the account's sync): empty, or well formed, known and in the limits. */
    fun valid(text: String): Boolean {
        if (text.isEmpty()) return true
        if (text.length > MAX_TEXT || !text.matches(TOKENS)) return false
        val ids = text.split(',').map { it.removePrefix("-") }
        return ids.all { it in all } && ids.toSet().size == ids.size && read(text) != null
    }

    fun shown(text: String?): List<String> = parse(text).filter { it.shown }.map { it.id }

    /** Whether the entry at [index] may be shown or hidden without leaving the limits. */
    fun canToggle(entries: List<Entry>, index: Int): Boolean {
        val entry = entries.getOrNull(index) ?: return false
        return allowed(entries.toMutableList().also { it[index] = entry.copy(shown = !entry.shown) })
    }

    fun toggle(entries: List<Entry>, index: Int): List<Entry> =
        if (!canToggle(entries, index)) entries else entries.toMutableList().also { it[index] = it[index].copy(shown = !it[index].shown) }

    fun move(entries: List<Entry>, from: Int, to: Int): List<Entry> {
        if (from !in entries.indices || to !in entries.indices || from == to) return entries
        return entries.toMutableList().also { it.add(to, it.removeAt(from)) }
    }

    private companion object {
        const val MAX_TEXT = 400
        val TOKENS = Regex("-?[a-zA-Z]{1,24}(,-?[a-zA-Z]{1,24})*")
    }
}

/**
 * « Disposition » (9.0, owner 2026-11-22: « rends complètement customisable l'app »): which tabs the bar holds and
 * in which order, Home's sections, the buttons of the player and the mini-player, the artwork's size, the Canvas in
 * the player, the library's views and orders, the title menu and the swipes of a title row. Every key is new; an
 * empty or missing value is today's layout, so older versions, which ignore these keys, look as they always did.
 * Stored in `ketvoice_settings`, carried by the personal backup and the account's settings sync.
 */
internal object LayoutSettings {
    const val TABS = "layoutTabs"
    const val HOME = "layoutHome"
    const val PLAYER = "layoutPlayer"
    const val MINI = "layoutMini"
    const val ARTWORK = "layoutArtwork"
    const val CANVAS = "layoutCanvas"
    const val LIBRARY_VIEWS = "layoutLibraryViews"
    const val LIBRARY_SORTS = "layoutLibrarySorts"
    const val MENU = "layoutMenu"
    const val SWIPE_END = "layoutSwipeEnd"
    const val SWIPE_START = "layoutSwipeStart"

    /** Tabs: Home, Explore, Library by default; 2 to 5; Home or Library always there. */
    val tabList = LayoutList(listOf("home", "explore", "library", "search", "downloads", "favorites"),
        setOf("home", "explore", "library"), minShown = 2, maxShown = 5, anyOf = setOf("home", "library"))

    /** Home's sections from the top (the greeting stays first, it is the page's title). */
    val homeList = LayoutList(listOf("featured", "forYou", "moods", "releases", "recent", "library", "friends", "mixes",
        "week", "replay", "catalog"), setOf("featured", "forYou", "moods", "releases", "recent", "library",
        "friends", "mixes", "week", "replay", "catalog"))

    /** The full player's bottom row: 2 to 5 buttons, the queue always among them. */
    val playerList = LayoutList(listOf("lyrics", "output", "account", "queue", "like", "ketmix", "sleep", "share", "playlist", "download"),
        setOf("lyrics", "output", "account", "queue"), minShown = 2, maxShown = 5, required = setOf("queue"))

    /** The mini-player's buttons beside play/pause: at most two. */
    val miniList = LayoutList(listOf("next", "previous", "like", "ketmix", "lyrics", "queue"), setOf("next"), maxShown = 2)

    /** The title menu (« Plus » always closes it and is not in the list). */
    val menuList = LayoutList(listOf("playNext", "enqueue", "favorite", "download", "playlist", "radio", "share", "mixPoint", "lyricsEditor", "about"),
        setOf("playNext", "enqueue", "favorite", "download", "playlist", "radio", "share", "mixPoint", "lyricsEditor", "about"))

    /** What a swipe on a title row does. */
    val swipeActions = listOf("none", "playNext", "enqueue", "favorite", "download", "playlist", "removeDownload")
    const val SWIPE_END_DEFAULT = "playNext"
    const val SWIPE_START_DEFAULT = "enqueue"

    /** The library's sections, their views (list or grid) and orders. */
    val libraryViewDefaults = linkedMapOf("playlists" to "list", "albums" to "grid", "artists" to "grid")
    val librarySortDefaults = linkedMapOf("playlists" to "Recent", "albums" to "0", "artists" to "0", "titles" to "0")
    private val librarySortValues = mapOf("playlists" to setOf("Recent", "Title", "Size"), "albums" to setOf("0", "1", "2"),
        "artists" to setOf("0", "1"), "titles" to setOf("0", "1", "2"))

    /** The artwork of the phone player: 0 as since 8.3, 1 smaller, 2 larger. */
    const val ARTWORK_MAX = 2

    val tabs = mutableStateOf("")
    val home = mutableStateOf("")
    val player = mutableStateOf("")
    val mini = mutableStateOf("")
    val artwork = mutableIntStateOf(0)
    /** The full player shows the title's Canvas (on, as always) or its cover only. */
    val canvas = mutableStateOf(true)
    val libraryViews = mutableStateOf("")
    val librarySorts = mutableStateOf("")
    val menu = mutableStateOf("")
    val swipeEnd = mutableStateOf(SWIPE_END_DEFAULT)
    val swipeStart = mutableStateOf(SWIPE_START_DEFAULT)

    private val lists: Map<String, LayoutList> = mapOf(TABS to tabList, HOME to homeList, PLAYER to playerList, MINI to miniList, MENU to menuList)
    private val listStates get() = mapOf(TABS to tabs, HOME to home, PLAYER to player, MINI to mini, MENU to menu)

    val keys: Set<String> = setOf(TABS, HOME, PLAYER, MINI, ARTWORK, CANVAS, LIBRARY_VIEWS, LIBRARY_SORTS, MENU, SWIPE_END, SWIPE_START)

    // ---- Library maps: `section=value,…`, empty for the defaults.
    fun readMap(text: String?, defaults: Map<String, String>, values: (String) -> Set<String>): Map<String, String> {
        val read = LinkedHashMap(defaults)
        text?.takeIf { it.length <= 400 }?.split(',')?.forEach { pair ->
            val key = pair.substringBefore('=', "")
            val value = pair.substringAfter('=', "")
            if (key in defaults && value in values(key)) read[key] = value
        }
        return read
    }

    fun encodeMap(values: Map<String, String>, defaults: Map<String, String>): String =
        values.filter { (k, v) -> k in defaults && defaults[k] != v }.entries.joinToString(",") { "${it.key}=${it.value}" }

    private fun viewValues(section: String) = setOf("list", "grid")
    private fun sortValues(section: String) = librarySortValues[section].orEmpty()

    fun libraryView(section: String): String = readMap(libraryViews.value, libraryViewDefaults, ::viewValues)[section] ?: "list"
    fun librarySort(section: String): String = readMap(librarySorts.value, librarySortDefaults, ::sortValues)[section] ?: "0"

    fun setLibraryView(section: String, value: String) {
        if (section !in libraryViewDefaults || value !in viewValues(section)) return
        libraryViews.value = encodeMap(readMap(libraryViews.value, libraryViewDefaults, ::viewValues) + (section to value), libraryViewDefaults)
    }

    fun setLibrarySort(section: String, value: String) {
        if (section !in librarySortDefaults || value !in sortValues(section)) return
        librarySorts.value = encodeMap(readMap(librarySorts.value, librarySortDefaults, ::sortValues) + (section to value), librarySortDefaults)
    }

    private fun validMap(text: String, defaults: Map<String, String>, values: (String) -> Set<String>): Boolean =
        text.isEmpty() || (text.length <= 400 && text.split(',').all { pair ->
            val key = pair.substringBefore('=', ""); key in defaults && pair.substringAfter('=', "") in values(key)
        })

    /** The checked value of a layout key a backup or the account's sync carries, or null. */
    fun checked(key: String, raw: Any?): Any? = when (key) {
        ARTWORK -> (raw as? Number)?.toInt()?.takeIf { it in 0..ARTWORK_MAX }
        CANVAS -> raw as? Boolean
        SWIPE_END, SWIPE_START -> (raw as? String)?.takeIf { it in swipeActions }
        LIBRARY_VIEWS -> (raw as? String)?.takeIf { validMap(it, libraryViewDefaults, ::viewValues) }
        LIBRARY_SORTS -> (raw as? String)?.takeIf { validMap(it, librarySortDefaults, ::sortValues) }
        else -> lists[key]?.let { list -> (raw as? String)?.takeIf { list.valid(it) } }
    }

    private fun SharedPreferences.text(key: String): String = runCatching { getString(key, "") }.getOrNull().orEmpty()

    fun load(p: SharedPreferences) {
        listStates.forEach { (key, state) -> state.value = p.text(key).let { lists.getValue(key).encode(lists.getValue(key).parse(it)) } }
        artwork.intValue = runCatching { p.getInt(ARTWORK, 0) }.getOrDefault(0).coerceIn(0, ARTWORK_MAX)
        canvas.value = runCatching { p.getBoolean(CANVAS, true) }.getOrDefault(true)
        libraryViews.value = p.text(LIBRARY_VIEWS).takeIf { validMap(it, libraryViewDefaults, ::viewValues) }.orEmpty()
        librarySorts.value = p.text(LIBRARY_SORTS).takeIf { validMap(it, librarySortDefaults, ::sortValues) }.orEmpty()
        swipeEnd.value = p.text(SWIPE_END).takeIf { it in swipeActions } ?: SWIPE_END_DEFAULT
        swipeStart.value = p.text(SWIPE_START).takeIf { it in swipeActions } ?: SWIPE_START_DEFAULT
    }

    fun save(e: SharedPreferences.Editor) {
        listStates.forEach { (key, state) -> e.putString(key, state.value) }
        e.putInt(ARTWORK, artwork.intValue.coerceIn(0, ARTWORK_MAX))
        e.putBoolean(CANVAS, canvas.value)
        e.putString(LIBRARY_VIEWS, libraryViews.value)
        e.putString(LIBRARY_SORTS, librarySorts.value)
        e.putString(SWIPE_END, swipeEnd.value)
        e.putString(SWIPE_START, swipeStart.value)
    }

    /** « Rétablir »: every layout choice back to today's layout. */
    fun resetAll() {
        listStates.values.forEach { it.value = "" }
        artwork.intValue = 0
        canvas.value = true
        libraryViews.value = ""
        librarySorts.value = ""
        swipeEnd.value = SWIPE_END_DEFAULT
        swipeStart.value = SWIPE_START_DEFAULT
    }

    /** Whether everything is as it was before the listener changed anything. */
    fun isDefault(): Boolean = listStates.values.all { it.value.isEmpty() } && artwork.intValue == 0 && canvas.value &&
        libraryViews.value.isEmpty() && librarySorts.value.isEmpty() && swipeEnd.value == SWIPE_END_DEFAULT &&
        swipeStart.value == SWIPE_START_DEFAULT
}
