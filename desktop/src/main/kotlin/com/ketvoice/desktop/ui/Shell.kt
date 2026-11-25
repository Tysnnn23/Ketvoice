package com.ketvoice.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.desktop.ui.glass.GlassBackdrop
import com.ketvoice.desktop.ui.glass.GlassMode
import com.ketvoice.desktop.ui.glass.GlassStyle
import com.ketvoice.desktop.ui.glass.LocalGlassMode
import com.ketvoice.desktop.ui.glass.glass
import com.ketvoice.desktop.ui.glass.glassSource
import com.ketvoice.desktop.ui.glass.rememberGlassBackdrop
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.KetDesktopTheme
import com.ketvoice.desktop.ui.theme.LocalKet

/**
 * The window's whole content (Windows 2.0): the pages edge to edge, a floating glass sidebar over them, the player
 * as a glass island at the bottom centre that unfolds into the full player, the contextual glass panel on the right,
 * and the overlays (full-screen player, Ketmix Studio, the lyrics editor, menus, dialogs).
 */
@Composable
fun KetvoiceShell(
    player: PlayerControls = PlayerBridge,
    library: LibraryStore = LibraryBridge,
    catalog: CatalogSource = LiveCatalog,
    dark: Boolean? = null,
    navigator: Navigator = remember { Navigator(LastPage.startRoute()) },
    initialPanel: Panel = Panel.NONE,
    initialSidebar: Dp = ShellMetrics.SIDEBAR_WIDE,
    initialQuery: String = "",
    initialFullPlayer: Boolean = false,
    /** « Verre Windows 11 » is showing (WindowGlass.active): Mica under the window, which the glass sits above. */
    glass: Boolean = false,
    /** How the glass surfaces draw ([com.ketvoice.desktop.ui.glass.GlassPolicy]); the window passes its own. */
    glassMode: GlassMode = GlassMode.LIQUID,
    shell: ShellState = remember { ShellState(initialPanel.tab, initialFullPlayer, sidebarWidth = initialSidebar) },
) {
    val content: @Composable () -> Unit = {
        val search = remember { SearchState().apply { query = initialQuery } }
        val menu = remember { MenuState() }
        val drag = remember { TrackDrag() }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val actions = remember(player, library, navigator) { TrackActions(player, library, navigator, scope) }
        LastPage.Remember(navigator) // Windows 2.0: the window opens again on this page
        CompositionLocalProvider(
            LocalPlayer provides player, LocalLibrary provides library, LocalCatalog provides catalog,
            LocalNavigator provides navigator, LocalSearch provides search, LocalMenu provides menu,
            LocalTrackDrag provides drag, LocalTrackActions provides actions, LocalShell provides shell, LocalGlassMode provides glassMode,
        ) { ShellLayout() }
    }
    if (dark == null) KetDesktopTheme(glass = glass, content = content) else KetDesktopTheme(dark, glass, content)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ShellLayout() {
    val ket = LocalKet.current
    val player = LocalPlayer.current
    val nav = LocalNavigator.current
    val search = LocalSearch.current
    val menu = LocalMenu.current
    val actions = LocalTrackActions.current
    val shell = LocalShell.current
    var searchFocused by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    // The window's shortcuts (Shortcuts.list, shown in Settings); text fields keep Space and the arrows.
    val keys = object : Shortcuts.Target {
        override val typing get() = searchFocused || TextEntry.active
        override val fullPlayer get() = shell.fullPlayer
        override fun focusSearch() { runCatching { searchFocus.requestFocus() } }
        override fun leaveField() { runCatching { rootFocus.requestFocus() } }
        override fun setFullPlayer(open: Boolean) { shell.fullPlayer = open }
        override fun settings() { nav.root(Route.Settings) }
        override fun back() = nav.back()
    }
    fun onKey(e: KeyEvent): Boolean = if (com.ketvoice.desktop.ketmix.KetmixStudioDesk.request.value != null) StudioKeys.handle(e)
        else if (LyricsEditorDesk.request.value != null) LyricsEditorKeys.handle(e)
        else Shortcuts.handle(e, player, actions, keys)

    BoxWithConstraints(Modifier.fillMaxSize().background(ket.window).onPreviewKeyEvent(::onKey).focusRequester(rootFocus).focusable().testTag("shell")) {
        val window = maxWidth
        val icons = ShellMetrics.collapsed(window)
        val sidebar = ShellMetrics.sidebar(window, shell.sidebarWidth)
        val panelOpen = shell.panel != null
        val panelWidth = ShellMetrics.panel(window, shell.panelWidth)
        val motion = if (Motion.reduced) androidx.compose.animation.core.snap() else spring<Dp>(dampingRatio = .86f, stiffness = 360f)
        val sideAnimated by animateDpAsState(sidebar, motion)
        val endInset by animateDpAsState(if (panelOpen) ShellMetrics.MARGIN + panelWidth + ShellMetrics.GAP else 0.dp, motion)
        val insets = PaddingValues(start = ShellMetrics.MARGIN + sideAnimated + ShellMetrics.GAP, end = endInset,
            bottom = ShellMetrics.ISLAND_HEIGHT + ShellMetrics.ISLAND_FOOT * 2)
        val backdrop = rememberGlassBackdrop()
        SharedTransitionLayout(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalSharedScope provides this, LocalShellInsets provides insets) {
                Box(Modifier.fillMaxSize()) {
                    // What the glass refracts: the pages, edge to edge, on the window's ground (Mica shows through it).
                    Box(Modifier.fillMaxSize().glassSource(backdrop)) {
                        Box(Modifier.fillMaxSize().background(ket.ground))
                        Pages()
                    }
                    Sidebar(backdrop, sideAnimated, icons, searchFocus, { searchFocused = it }, Modifier.padding(ShellMetrics.MARGIN))
                    if (nav.canGoBack) BackButton(backdrop, Modifier.padding(start = insets.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) + 10.dp, top = 16.dp))
                    RightPanel(backdrop, panelWidth, panelOpen)
                    PlayerLayer(backdrop, window, insets)
                    DragChip()
                }
            }
        }
        if (com.ketvoice.desktop.ui.PlayerFullScreen.on.value) FullScreenPlayer(onExit = { PlayerFullScreen.on.value = false })
        KetmixStudioHost()
        LyricsEditorHost()
        PlaybackToast()
        PlaylistUpdateCard()
        if (ImportDialog.open.value) ImportPlaylistDialog(onDismiss = { ImportDialog.open.value = false })
        com.ketvoice.desktop.update.Updates.dialog.value?.let { release ->
            UpdateDialog(release, com.ketvoice.desktop.update.Updates.updater) { com.ketvoice.desktop.update.Updates.dialog.value = null }
        }
        ShellHooks.all.forEach { key(it.id) { it.content() } }
        ContextMenuHost(menu)
    }
    // A new search from the field opens the search page; clearing it keeps the start page.
    LaunchedEffect(search.query) { if (search.query.isNotEmpty() && nav.current != Route.Search) nav.root(Route.Search) }
}

/** The right panel slides in from the window's edge on a spring and out again, keeping its last tab while it leaves. */
@Composable
private fun RightPanel(backdrop: GlassBackdrop, width: Dp, open: Boolean) {
    val slide by animateDpAsState(if (open) 0.dp else width + ShellMetrics.MARGIN * 2,
        if (Motion.reduced) androidx.compose.animation.core.snap() else spring(dampingRatio = .84f, stiffness = 380f))
    if (!open && slide >= width + ShellMetrics.MARGIN * 2 - 1.dp) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        SidePanel(backdrop, width, Modifier.offset { IntOffset(slide.roundToPx(), 0) }.padding(ShellMetrics.MARGIN))
    }
}

/**
 * The island at the bottom centre of the room between the sidebar and the panel, or the full player: one swaps for
 * the other in a shared transition, so the cover, the title and the buttons travel between them.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PlayerLayer(backdrop: GlassBackdrop, window: Dp, insets: PaddingValues) {
    val shell = LocalShell.current
    val reduced = Motion.reduced
    val start = insets.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
    val end = insets.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
    AnimatedContent(shell.fullPlayer, Modifier.fillMaxSize(), transitionSpec = { SharedMotion.player(reduced) }, label = "player") { full ->
        CompositionLocalProvider(LocalPlayerScope provides this) {
            if (full) FullPlayer(onClose = { shell.fullPlayer = false })
            else Box(Modifier.fillMaxSize().padding(start = start - ShellMetrics.GAP, end = (end - ShellMetrics.GAP).coerceAtLeast(0.dp)),
                contentAlignment = Alignment.BottomCenter) {
                val room = window - start - end + ShellMetrics.GAP * 2
                Island(backdrop, room, onOpenPlayer = { shell.fullPlayer = true }, Modifier.padding(bottom = ShellMetrics.ISLAND_FOOT))
            }
        }
    }
}

/** « Retour » as a round glass drop above the page, beside the sidebar. */
@Composable
private fun BackButton(backdrop: GlassBackdrop, modifier: Modifier) {
    val ket = LocalKet.current
    val nav = LocalNavigator.current
    Box(modifier.glassShadow(20.dp, 10.dp).glass(backdrop, 20.dp, ket.glassTint, GlassStyle.Drop, rim = ket.glassRim)) {
        KetIconButton(KetIcons.ArrowLeft, "Retour", { nav.back() }, Modifier.testTag("back"), size = 40.dp, glyph = 17.dp)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Pages() {
    val nav = LocalNavigator.current
    val reduced = Motion.reduced
    AnimatedContent(nav.current, Modifier.fillMaxSize(), transitionSpec = { SharedMotion.pages(nav.direction, reduced) }, contentKey = { it },
        label = "pages") { route ->
        CompositionLocalProvider(LocalPageScope provides this) {
            Box(Modifier.fillMaxSize()) { PageFor(route) }
        }
    }
}

/** The page of [route]: the built-in pages, then those of [PageRegistry]. */
@Composable
internal fun PageFor(route: Route) {
    when (route) {
        Route.Home -> HomePage()
        Route.Explore -> ExplorePage()
        Route.Search -> SearchPage()
        Route.RecentlyAdded -> RecentlyAddedPage()
        Route.Artists -> ArtistsPage()
        Route.Albums -> AlbumsPage()
        Route.Songs -> SongsPage()
        Route.Downloads -> DownloadsPage()
        Route.Favorites -> FavoritesPage()
        Route.Stats -> StatsPage()
        Route.Settings -> SettingsPage()
        Route.Equalizer -> EqualizerPage()
        is Route.Playlist -> PlaylistPage(route.id)
        is Route.Album -> AlbumPage(route.ref)
        is Route.Artist -> ArtistPage(route.ref)
        is Route.Category -> CategoryPage(route.category)
        is Route.Extra -> PageRegistry.find(route.id)?.content?.invoke(route.arg)
            ?: Page(null, "missing") { item { EmptyMessage("Page indisponible", "Cette page n'est plus disponible.") } }
    }
}

/** The title being dragged, under the pointer. */
@Composable
private fun DragChip() {
    val drag = LocalTrackDrag.current
    val ket = LocalKet.current
    val t = drag.track ?: return
    val p = drag.pointer
    Row(Modifier.offset { IntOffset(p.x.toInt() + 12, p.y.toInt() + 8) }.shadow(10.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))
        .background(ket.raised).padding(6.dp).testTag("drag-chip"), verticalAlignment = Alignment.CenterVertically) {
        Artwork(t.coverUrl, 28.dp, radius = 6.dp)
        Spacer(Modifier.width(8.dp))
        KText(t.title, Ket27Desk.Type.footnote, Modifier.width(160.dp), weight = FontWeight.Medium, translate = false)
    }
}
