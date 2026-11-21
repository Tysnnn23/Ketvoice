package com.ketvoice.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Ket27Glyphs
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.desktop.ui.theme.DeskGlyphs
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.KetDesktopTheme
import com.ketvoice.desktop.ui.theme.LocalKet
import java.awt.Cursor

/** The window's whole content: sidebar, now-playing bar, pages, side panels and the full player. */
@Composable
fun KetvoiceShell(
    player: PlayerControls = PlayerBridge,
    library: LibraryStore = LibraryBridge,
    catalog: CatalogSource = LiveCatalog,
    dark: Boolean? = null,
    navigator: Navigator = remember { Navigator() },
    initialPanel: Panel = Panel.NONE,
    initialSidebar: Dp = 248.dp,
    initialQuery: String = "",
    initialFullPlayer: Boolean = false,
    /** « Verre Windows 11 » is showing (WindowGlass.active): Mica under the window, Acrylic sidebar and bar. */
    glass: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        val search = remember { SearchState().apply { query = initialQuery } }
        val menu = remember { MenuState() }
        val drag = remember { TrackDrag() }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val actions = remember(player, library, navigator) { TrackActions(player, library, navigator, scope) }
        CompositionLocalProvider(
            LocalPlayer provides player, LocalLibrary provides library, LocalCatalog provides catalog,
            LocalNavigator provides navigator, LocalSearch provides search, LocalMenu provides menu,
            LocalTrackDrag provides drag, LocalTrackActions provides actions,
        ) { ShellLayout(initialPanel, initialSidebar, initialFullPlayer) }
    }
    if (dark == null) KetDesktopTheme(glass = glass, content = content) else KetDesktopTheme(dark, glass, content)
}

@Composable
private fun ShellLayout(initialPanel: Panel, initialSidebar: Dp, initialFullPlayer: Boolean) {
    val ket = LocalKet.current
    val player = LocalPlayer.current
    val nav = LocalNavigator.current
    val search = LocalSearch.current
    val menu = LocalMenu.current
    val actions = LocalTrackActions.current
    val state by player.state.collectAsState()
    var panel by remember { mutableStateOf(initialPanel) }
    var fullPlayer by remember { mutableStateOf(initialFullPlayer) }
    var sidebar by remember { mutableStateOf(initialSidebar) }
    var searchFocused by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    // The window's shortcuts (Shortcuts.list, shown in Settings); text fields keep Space and the arrows.
    val keys = object : Shortcuts.Target {
        override val typing get() = searchFocused || TextEntry.active
        override val fullPlayer get() = fullPlayer
        override fun focusSearch() { runCatching { searchFocus.requestFocus() } }
        override fun leaveField() { runCatching { rootFocus.requestFocus() } }
        override fun setFullPlayer(open: Boolean) { fullPlayer = open }
        override fun settings() { nav.root(Route.Settings) }
        override fun back() = nav.back()
    }
    fun onKey(e: KeyEvent): Boolean = if (com.ketvoice.desktop.ketmix.KetmixStudioDesk.request.value != null) StudioKeys.handle(e)
        else Shortcuts.handle(e, player, actions, keys)

    Box(Modifier.fillMaxSize().background(ket.window).onPreviewKeyEvent(::onKey).focusRequester(rootFocus).focusable().testTag("shell")) {
        Backdrop(state.current?.coverUrl)
        Row(Modifier.fillMaxSize()) {
            Sidebar(sidebar, searchFocus, onSearchFocus = { searchFocused = it })
            // The resize handle: 220–320 dp.
            val density = LocalDensity.current
            Box(Modifier.width(5.dp).fillMaxHeight().offset(x = (-2).dp).pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                .pointerInput(Unit) { detectHorizontalDragGestures { _, d -> sidebar = (sidebar + with(density) { d.toDp() }).coerceIn(220.dp, 320.dp) } }
                .testTag("sidebar-resize")) {
                Box(Modifier.align(Alignment.Center).width(.5.dp).fillMaxHeight().background(ket.hairline))
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxWidth().material(ket, if (ket.acrylic) ket.material else ket.material.copy(alpha = ket.material.alpha * .6f))) {
                    NowPlayingBar(panel, { panel = it }, onOpenPlayer = { fullPlayer = true })
                }
                Box(Modifier.fillMaxWidth().height(.5.dp).background(ket.hairline))
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(ket.ground)) { Pages() }
                    val width by animateDpAsState(if (panel == Panel.NONE) 0.dp else 330.dp, Motion.Springs.SHEET.motion())
                    if (width > 0.dp) Row(Modifier.width(width).fillMaxHeight()) {
                        Box(Modifier.width(.5.dp).fillMaxHeight().background(ket.hairline))
                        Box(Modifier.width(330.dp).fillMaxHeight().material(ket)) {
                            when (panel) { Panel.QUEUE -> QueuePanel(); Panel.LYRICS -> LyricsPanel(); Panel.NONE -> {} }
                        }
                    }
                }
            }
        }
        DragChip()
        AnimatedVisibility(fullPlayer, enter = EnterTransition.None, exit = fadeOut(Motion.Springs.DISMISS.motion())) {
            FullPlayer(onClose = { fullPlayer = false })
        }
        if (PlayerFullScreen.on.value) FullScreenPlayer(onExit = { PlayerFullScreen.on.value = false })
        KetmixStudioHost()
        PlaybackToast()
        if (ImportDialog.open.value) ImportPlaylistDialog(onDismiss = { ImportDialog.open.value = false })
        com.ketvoice.desktop.update.Updates.dialog.value?.let { release ->
            UpdateDialog(release, com.ketvoice.desktop.update.Updates.updater) { com.ketvoice.desktop.update.Updates.dialog.value = null }
        }
        ContextMenuHost(menu)
    }
    // A new search from the field opens the search page; clearing it keeps the start page.
    LaunchedEffect(search.query) { if (search.query.isNotEmpty() && nav.current != Route.Search) nav.root(Route.Search) }
}

/** The glass's backdrop: the current cover, blurred far and faint, under the translucent sidebar and bar. */
@Composable
private fun Backdrop(cover: String?) {
    val ket = LocalKet.current
    val image = rememberCover(cover)
    if (image == null) return
    Image(image, null, Modifier.fillMaxSize().blur(140.dp).graphicsLayer { alpha = if (ket.dark) .22f else .16f }, contentScale = ContentScale.Crop)
}

@Composable
private fun Pages() {
    val nav = LocalNavigator.current
    val reduced = Motion.reduced
    AnimatedContent(nav.current, Modifier.fillMaxSize(), transitionSpec = {
        if (reduced) ContentTransform(EnterTransition.None, ExitTransition.None)
        else when (nav.direction) {
            1 -> (slideInHorizontally(Motion.Springs.PUSH.spec()) { it / 6 } + fadeIn(Motion.Springs.SMOOTH.spec())) togetherWith
                (slideOutHorizontally(Motion.Springs.PUSH.spec()) { -it / 12 } + fadeOut(Motion.Springs.DISMISS.spec()))
            -1 -> (slideInHorizontally(Motion.Springs.PUSH.spec()) { -it / 12 } + fadeIn(Motion.Springs.SMOOTH.spec())) togetherWith
                (slideOutHorizontally(Motion.Springs.PUSH.spec()) { it / 6 } + fadeOut(Motion.Springs.DISMISS.spec()))
            else -> (fadeIn(Motion.Springs.SMOOTH.spec()) + scaleIn(Motion.Springs.APPEAR.spec(), initialScale = .985f)) togetherWith
                fadeOut(Motion.Springs.DISMISS.spec())
        }
    }, contentKey = { it }) { route ->
        Box(Modifier.fillMaxSize()) {
            when (route) {
                Route.Home -> HomePage()
                Route.Explore -> ExplorePage()
                Route.Search -> SearchPage()
                Route.RecentlyAdded -> RecentlyAddedPage()
                Route.Artists -> ArtistsPage()
                Route.Albums -> AlbumsPage()
                Route.Songs -> SongsPage()
                Route.Downloads -> DownloadsPage()
                Route.Stats -> StatsPage()
                Route.Settings -> SettingsPage()
                Route.Equalizer -> EqualizerPage()
                is Route.Playlist -> PlaylistPage(route.id)
                is Route.Album -> AlbumPage(route.ref)
                is Route.Artist -> ArtistPage(route.ref)
                is Route.Category -> CategoryPage(route.category)
            }
            if (nav.canGoBack && route == nav.current) KetIconButton(KetIcons.ArrowLeft, "Retour", { nav.back() },
                Modifier.align(Alignment.TopStart).padding(6.dp).testTag("back"), size = 28.dp, glyph = 15.dp)
        }
    }
}

/* ── Sidebar ── */

@Composable
private fun Sidebar(width: Dp, searchFocus: FocusRequester, onSearchFocus: (Boolean) -> Unit) {
    val ket = LocalKet.current
    val nav = LocalNavigator.current
    val library = LocalLibrary.current
    val playlists by library.playlists.collectAsState()
    Column(Modifier.width(width).fillMaxHeight().material(ket).testTag("sidebar")) {
        SearchField(searchFocus, onSearchFocus)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            item { SideItem(Ket27Glyphs.Home, "Accueil", Route.Home) }
            item { SideItem(Ket27Glyphs.Explore, "Explorer", Route.Explore) }
            item { SideHeader("Bibliothèque") }
            item { SideItem(KetIcons.Clock, "Récemment ajouté", Route.RecentlyAdded) }
            item { SideItem(KetIcons.Account, "Artistes", Route.Artists) }
            item { SideItem(KetIcons.Album, "Albums", Route.Albums) }
            item { SideItem(KetIcons.Music, "Titres", Route.Songs) }
            item { SideItem(KetIcons.Download, "Téléchargements", Route.Downloads) }
            item { SideItem(DeskGlyphs.Stats, "Statistiques", Route.Stats) }
            item {
                SideHeader("Playlists") {
                    KetIconButton(KetIcons.Link, "Importer des playlists", { ImportDialog.open.value = true },
                        size = 22.dp, glyph = 13.dp, modifier = Modifier.testTag("import-playlist"))
                    KetIconButton(KetIcons.Plus, "Nouvelle playlist", {
                        library.createPlaylist(AppText.t("Nouvelle playlist")) { id -> nav.root(Route.Playlist(id)) }
                    }, size = 22.dp, glyph = 13.dp, modifier = Modifier.testTag("new-playlist"))
                }
            }
            playlists.forEach { p -> item(p.id) { PlaylistItem(p) } }
        }
        Box(Modifier.fillMaxWidth().height(.5.dp).background(ket.hairline))
        Box(Modifier.padding(10.dp)) { SideItem(KetIcons.Settings, "Réglages", Route.Settings) }
    }
}

@Composable
private fun SearchField(focus: FocusRequester, onFocus: (Boolean) -> Unit) {
    val ket = LocalKet.current
    val search = LocalSearch.current
    val nav = LocalNavigator.current
    Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 10.dp).fillMaxWidth().height(30.dp)
        .clip(RoundedCornerShape(8.dp)).background(ket.fill).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(KetIcons.Search, null, Modifier.size(14.dp), tint = ket.secondary)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            if (search.query.isEmpty()) KText("Rechercher", Ket27Desk.Type.subheadline, color = ket.secondary)
            BasicTextField(search.query, { search.query = it },
                Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { onFocus(it.isFocused); if (it.isFocused) nav.root(Route.Search) }.testTag("search-field"),
                singleLine = true, cursorBrush = SolidColor(ket.accent),
                textStyle = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.subheadline).copy(color = ket.label),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { nav.root(Route.Search) }))
        }
        if (search.query.isNotEmpty()) KetIconButton(KetIcons.Close, "Effacer", { search.query = "" }, size = 18.dp, glyph = 10.dp)
    }
}

@Composable
private fun SideHeader(label: String, trailing: (@Composable () -> Unit)? = null) {
    val ket = LocalKet.current
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        KText(label, Ket27Desk.Type.caption, Modifier.weight(1f), color = ket.secondary, weight = FontWeight.SemiBold)
        trailing?.invoke()
    }
}

@Composable
private fun SideRow(icon: ImageVector?, label: String, selected: Boolean, tag: String, translate: Boolean = true, dropHover: Boolean = false,
                    modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val ket = LocalKet.current
    val (source, hovered) = rememberHover()
    Row(modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(8.dp))
        .background(when { dropHover -> ket.accent.copy(alpha = .22f); selected -> ket.selected; hovered -> ket.hover; else -> Color.Transparent })
        .hoverable(source).clickable(onClick = onClick).padding(horizontal = 10.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) leading() else if (icon != null) Icon(icon, null, Modifier.size(17.dp), tint = ket.accent)
        Spacer(Modifier.width(10.dp))
        KText(label, Ket27Desk.Type.subheadline, color = ket.label, weight = if (selected) FontWeight.SemiBold else null, translate = translate)
    }
}

@Composable
private fun SideItem(icon: ImageVector, label: String, route: Route) {
    val nav = LocalNavigator.current
    SideRow(icon, label, nav.section == route, "nav:$label") { nav.root(route) }
}

@Composable
private fun PlaylistItem(p: UserPlaylist) {
    val nav = LocalNavigator.current
    val drag = LocalTrackDrag.current
    DisposableEffect(p.id) { onDispose { drag.unregister(p.id) } }
    SideRow(null, p.name, nav.section == Route.Playlist(p.id), "playlist:${p.id}", translate = false, dropHover = drag.hoveredTarget == p.id,
        modifier = Modifier.dropTarget(p.id, drag), leading = { Mosaic(p.tracks, 20.dp) }) { nav.root(Route.Playlist(p.id)) }
}

/** The title being dragged, under the pointer. */
@Composable
private fun DragChip() {
    val drag = LocalTrackDrag.current
    val ket = LocalKet.current
    val t = drag.track ?: return
    val p = drag.pointer
    Row(Modifier.offset { IntOffset(p.x.toInt() + 12, p.y.toInt() + 8) }.shadow(10.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))
        .background(ket.raised).padding(6.dp).testTag("drag-chip"), verticalAlignment = Alignment.CenterVertically) {
        Artwork(t.coverUrl, 28.dp, radius = 4.dp)
        Spacer(Modifier.width(8.dp))
        KText(t.title, Ket27Desk.Type.footnote, Modifier.width(160.dp), weight = FontWeight.Medium, translate = false)
    }
}
