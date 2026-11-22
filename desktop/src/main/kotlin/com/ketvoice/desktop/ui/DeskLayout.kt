package com.ketvoice.desktop.ui

import android.content.SharedPreferences
import com.ketvoice.app.data.LayoutList
import com.ketvoice.app.data.LayoutSettings

/**
 * « Disposition » on Windows (the phone's 9.0 layout keys, read for the window). The keys, ids and limits are the
 * phone's, so a choice made on one device is valid on the other and the account's settings sync carries it; an empty
 * value is the window exactly as it was before.
 *
 * - `layoutTabs` → the sidebar: Home, Explore, the Library group; Search, Downloads and Favourites can come out as
 *   entries of their own (Downloads then leaves the Library group). The phone's list as it is (same defaults).
 * - `layoutHome` → Home: For you, Recently played, the catalogue's shelves (the phone's other sections are not here).
 * - `layoutPlayer` → the buttons at the right of the bar, the phone's ids in the window's order (lyrics, queue, the
 *   account's devices, output); `layoutMenu` → a title's menu, in the window's order. Ids the window has no button
 *   for (the sleep timer…) keep their place and their state for the phone.
 * - `layoutLibraryViews`/`layoutLibrarySorts` → grid or list, and the order each library page opens with.
 */
internal object DeskLayout {
    val sidebar: LayoutList get() = LayoutSettings.tabList
    val home: LayoutList get() = LayoutSettings.homeList

    /** The bar's buttons: the phone's ids and limits; the default is the window's row (lyrics, queue, devices, output). */
    val bar = LayoutList(listOf("lyrics", "queue", "account", "output", "like", "playlist", "download", "ketmix", "sleep", "share"),
        setOf("lyrics", "output", "account", "queue"), minShown = 2, maxShown = 5, required = setOf("queue"))

    /** A title's menu: the phone's ids; the default is the window's menu (« Éditer les paroles… » after Ketmix Studio). */
    val menu = LayoutList(listOf("playNext", "enqueue", "playlist", "mixPoint", "lyricsEditor", "download", "favorite", "radio", "share", "about"),
        setOf("playNext", "enqueue", "playlist", "mixPoint", "lyricsEditor", "download", "favorite", "radio", "share", "about"))

    /** What the window draws of each list, with its label in Settings. */
    val sidebarLabels = linkedMapOf("home" to "Accueil", "explore" to "Explorer", "library" to "Bibliothèque", "search" to "Rechercher",
        "downloads" to "Téléchargements", "favorites" to "Favoris")
    val homeLabels = linkedMapOf("forYou" to "Pour toi", "recent" to "Écoutés récemment", "catalog" to "Albums et sélections du catalogue")
    val barLabels = linkedMapOf("lyrics" to "Paroles", "queue" to "À suivre", "account" to "Contrôle du téléphone", "output" to "Sortie audio",
        "like" to "J'aime", "playlist" to "Ajouter à une playlist", "download" to "Télécharger")
    val menuLabels = linkedMapOf("playNext" to "Lire ensuite", "enqueue" to "Ajouter à la file d'attente", "playlist" to "Ajouter à une playlist",
        "mixPoint" to "Ketmix Studio", "lyricsEditor" to "Éditer les paroles…", "download" to "Télécharger", "favorite" to "J'aime")

    /** Library pages: their orders, as the phone stores them; the window opens Albums, Artists and Titles A–Z. */
    val sortDefaults = linkedMapOf("playlists" to "Recent", "albums" to "1", "artists" to "1", "titles" to "1")
    private val sortValues = mapOf("playlists" to setOf("Recent", "Title", "Size"), "albums" to setOf("0", "1", "2"),
        "artists" to setOf("0", "1"), "titles" to setOf("0", "1", "2"))
    val sortChoices = mapOf(
        "playlists" to listOf("Recent" to "Ajout récent", "Title" to "Titre", "Size" to "Nombre de titres"),
        "albums" to listOf("0" to "Récents", "1" to "Titre A–Z", "2" to "Artiste A–Z"),
        "artists" to listOf("0" to "Récents", "1" to "Nom A–Z"),
        "titles" to listOf("0" to "Récents", "1" to "Titre A–Z", "2" to "Artiste A–Z"))

    // ---- What the window shows.

    /**
     * The sidebar's rows from the top: `library` stands for its header and pages (Downloads among them unless it is
     * an entry of its own), and Statistics closes the group, or the list when the group is hidden.
     */
    fun sidebarRows(text: String? = LayoutSettings.tabs.value): List<String> {
        val shown = sidebar.shown(text)
        val rows = shown.flatMap { id ->
            if (id != "library") listOf(id)
            else listOf("header", "recent", "artists", "albums", "songs") + (if ("downloads" in shown) emptyList() else listOf("downloads")) + "stats"
        }
        return if ("stats" in rows) rows else rows + "stats"
    }

    /** The page the window opens on: Home, or the sidebar's first entry when Home is hidden. */
    fun startRoute(text: String? = LayoutSettings.tabs.value): Route {
        val shown = sidebar.shown(text)
        if ("home" in shown) return Route.Home
        return when (shown.firstOrNull()) {
            "explore" -> Route.Explore
            "library" -> Route.RecentlyAdded
            "search" -> Route.Search
            "downloads" -> Route.Downloads
            "favorites" -> Route.Favorites
            else -> Route.Home
        }
    }

    /** The sidebar's playlists in the order Settings › Disposition chose (« Ajout récent »: the library's own order). */
    fun sortedPlaylists(list: List<UserPlaylist>): List<UserPlaylist> = when (librarySort("playlists")) {
        "Title" -> java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }.let { c -> list.sortedWith { a, b -> c.compare(a.name, b.name) } }
        "Size" -> list.sortedByDescending { it.tracks.size }
        else -> list
    }

    fun homeSections(text: String? = LayoutSettings.home.value): List<String> = home.shown(text).filter { it in homeLabels }

    fun barButtons(text: String? = LayoutSettings.player.value): List<String> = bar.shown(text).filter { it in barLabels }

    fun menuItems(text: String? = LayoutSettings.menu.value): List<String> = menu.shown(text).filter { it in menuLabels }

    /**
     * A title's menu with its fixed parts: « Aller à l'album » and « Aller à l'artiste » follow Télécharger (or end the
     * menu when it is hidden), between separators ("-"), as the window always had them.
     */
    fun menuPlan(items: List<String>): List<String> {
        val plan = mutableListOf<String>()
        fun rule() { if (plan.isNotEmpty() && plan.last() != "-") plan += "-" }
        for (id in items) {
            if (id == "download") { rule(); plan += listOf("download", "goAlbum", "goArtist"); plan += "-" } else plan += id
        }
        if ("download" !in items) { rule(); plan += listOf("goAlbum", "goArtist") }
        return tidy(plan)
    }

    /** No separator first, last or twice in a row. */
    fun tidy(plan: List<String>): List<String> = tidy(plan) { it == "-" }

    fun <T> tidy(plan: List<T>, rule: (T) -> Boolean): List<T> = plan.filterIndexed { i, it -> !rule(it) || (i > 0 && !rule(plan[i - 1])) }
        .dropWhile(rule).dropLastWhile(rule)

    fun libraryGrid(section: String): Boolean = LayoutSettings.libraryView(section) == "grid"

    fun librarySort(section: String): String = LayoutSettings.readMap(LayoutSettings.librarySorts.value, sortDefaults) { sortValues[it].orEmpty() }[section]
        ?: sortDefaults.getValue(section)

    fun setLibrarySort(section: String, value: String) {
        if (value !in sortValues[section].orEmpty()) return
        val now = LayoutSettings.readMap(LayoutSettings.librarySorts.value, sortDefaults) { sortValues[it].orEmpty() } + (section to value)
        LayoutSettings.librarySorts.value = LayoutSettings.encodeMap(now, sortDefaults)
    }

    // ---- Editing a list in Settings: only the ids the window draws are listed; the others keep their slots.

    /** The rows of [list] the window draws ([labels]), in their stored order. */
    fun rows(list: LayoutList, text: String?, labels: Map<String, String>): List<LayoutList.Entry> = list.parse(text).filter { it.id in labels }

    /** [text] with the drawn rows [from] and [to] swapped places (positions among the drawn rows). */
    fun move(list: LayoutList, text: String?, labels: Map<String, String>, from: Int, to: Int): String {
        val entries = list.parse(text)
        val slots = entries.indices.filter { entries[it].id in labels }
        if (from !in slots.indices || to !in slots.indices || from == to) return text.orEmpty()
        val drawn = list.move(slots.map { entries[it] }, from, to)
        val next = entries.toMutableList().also { out -> slots.forEachIndexed { i, slot -> out[slot] = drawn[i] } }
        return list.encode(next)
    }

    fun canToggle(list: LayoutList, text: String?, id: String): Boolean {
        val entries = list.parse(text)
        return list.canToggle(entries, entries.indexOfFirst { it.id == id })
    }

    fun toggle(list: LayoutList, text: String?, id: String): String {
        val entries = list.parse(text)
        return list.encode(list.toggle(entries, entries.indexOfFirst { it.id == id }))
    }

    // ---- Stored with the other settings.

    /**
     * Reads the layout keys. The phone's reading makes an arrangement equal to its own default empty; the bar and the
     * menu have another default here, so their text is kept as it was stored (when it is valid).
     */
    fun load(p: SharedPreferences) {
        LayoutSettings.load(p)
        LayoutSettings.player.value = raw(p, LayoutSettings.PLAYER, bar)
        LayoutSettings.menu.value = raw(p, LayoutSettings.MENU, menu)
    }

    private fun raw(p: SharedPreferences, key: String, list: LayoutList): String =
        runCatching { p.getString(key, "") }.getOrNull().orEmpty().takeIf(list::valid).orEmpty()

    /** The layout states, for the store's watch. */
    val states get() = listOf(LayoutSettings.tabs.value, LayoutSettings.home.value, LayoutSettings.player.value, LayoutSettings.menu.value,
        LayoutSettings.libraryViews.value, LayoutSettings.librarySorts.value)

    /** « Rétablir la disposition »: what this window lays out, back to how it was. The phone's own choices stay. */
    fun reset() {
        LayoutSettings.tabs.value = ""; LayoutSettings.home.value = ""; LayoutSettings.player.value = ""; LayoutSettings.menu.value = ""
        LayoutSettings.libraryViews.value = ""; LayoutSettings.librarySorts.value = ""
    }

    fun isDefault(): Boolean = states.all { it.isEmpty() }
}
