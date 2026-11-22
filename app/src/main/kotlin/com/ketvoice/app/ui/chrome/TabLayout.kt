package com.ketvoice.app.ui.chrome

import androidx.compose.ui.graphics.vector.ImageVector
import com.ketvoice.app.data.CatalogRequest
import com.ketvoice.app.data.LayoutSettings
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Ket27Glyphs
import com.ketvoice.app.ui.common.KetIcons

/** A place the tab bar can hold (9.0 « Onglets à la carte »): the three root pages, Search and two library pages. */
internal enum class TabDestination(val id: String, val label: String) {
    Home("home", "Accueil"),
    Explore("explore", "Explorer"),
    Library("library", "Bibliothèque"),
    Search("search", "Rechercher"),
    Downloads("downloads", "Téléchargements"),
    Favorites("favorites", "Favoris");

    val icon: ImageVector get() = when (this) {
        Home -> Ket27Glyphs.Home
        Explore -> Ket27Glyphs.Explore
        Library -> Ket27Glyphs.Library
        Search -> Ket27Glyphs.Search
        Downloads -> KetIcons.Download
        Favorites -> Ket27Glyphs.Heart
    }

    /** The root tab ([AppUi.HOME]…) this destination is, or -1 for a page over the current tab. */
    val rootTab: Int get() = when (this) {
        Home -> AppUi.HOME
        Explore -> AppUi.EXPLORER
        Library -> AppUi.LIBRARY
        else -> -1
    }

    companion object {
        fun of(id: String): TabDestination? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The tabs of the bar, in the listener's order (Settings › Personnalisation › Disposition): Home, Explore and
 * Library by default, 2 to 5, Home or Library always there. Both glass looks and the large windows' rail and
 * sidebar read this same order.
 */
internal object TabLayout {
    val DEFAULT = listOf(TabDestination.Home, TabDestination.Explore, TabDestination.Library)

    fun parse(text: String?): List<TabDestination> = LayoutSettings.tabList.shown(text).mapNotNull(TabDestination::of)

    fun current(): List<TabDestination> = parse(LayoutSettings.tabs.value)

    /**
     * Which of [tabs] is lit for the page shown: a page a tab opens (Search, Downloads, Favourites) lights that tab,
     * otherwise the root tab does; -1 when none of the tabs is where the listener is.
     */
    fun selected(tabs: List<TabDestination>, tab: Int, overlay: Int, catalogKind: String?): Int {
        val page = when {
            overlay == AppUi.SEARCH -> TabDestination.Search
            overlay == AppUi.DOWNLOADS -> TabDestination.Downloads
            overlay == AppUi.CATALOG && catalogKind == "favorites" -> TabDestination.Favorites
            else -> null
        }
        page?.let { p -> tabs.indexOf(p).takeIf { it >= 0 }?.let { return it } }
        return tabs.indexOfFirst { it.rootTab == tab }
    }

    /** The lit slot of the bar now, the first one when no tab is where the listener is (as before 9.0). */
    fun selectedNow(tabs: List<TabDestination>): Int =
        selected(tabs, AppUi.tab.intValue, AppUi.overlay.intValue, AppUi.catalogRequest.value?.kind).coerceAtLeast(0)

    /** Opens [destination]: a root tab, or its page over the current tab (back returns to that tab). */
    fun open(destination: TabDestination, floating: Boolean = true) {
        if (destination.rootTab >= 0) { AppUi.selectTab(destination.rootTab); return }
        when (destination) {
            TabDestination.Search -> if (floating) FloatingSearch.open() else AppUi.openSearch()
            TabDestination.Downloads -> { AppUi.selectTab(AppUi.tab.intValue); AppUi.openDownloads() }
            TabDestination.Favorites -> { AppUi.selectTab(AppUi.tab.intValue); AppUi.openCatalog(CatalogRequest("favorites", "local", "Favoris")) }
            else -> Unit
        }
    }

    /** The root tab the app starts on: Home, or the first root tab of the bar when Home is not in it. */
    fun startTab(tabs: List<TabDestination> = current()): Int =
        if (TabDestination.Home in tabs) AppUi.HOME else tabs.firstOrNull { it.rootTab >= 0 }?.rootTab ?: AppUi.HOME

    /** At launch, still on Home with nothing over it: the start tab of the bar (Home unless the bar left it out). */
    fun openStart() {
        val start = startTab()
        if (start != AppUi.HOME && AppUi.tab.intValue == AppUi.HOME && AppUi.overlay.intValue == AppUi.NONE) AppUi.selectTab(start)
    }
}
