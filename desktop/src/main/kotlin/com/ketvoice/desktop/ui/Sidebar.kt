package com.ketvoice.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.ketvoice.desktop.ui.glass.GlassBackdrop
import com.ketvoice.desktop.ui.glass.GlassStyle
import com.ketvoice.desktop.ui.glass.glass
import com.ketvoice.desktop.ui.theme.DeskGlyphs
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The floating glass sidebar: search, the entries of Settings › Disposition, pages other features registered, the
 * playlists and Settings. Below [ShellMetrics.COLLAPSE_BELOW] it keeps only its icons. Under the pointer a clear
 * glass drop (the lens) slides on a spring from the selected entry to the hovered one and back, and the hovered
 * entry grows a little, as if seen through it.
 */
@Composable
internal fun Sidebar(backdrop: GlassBackdrop?, width: Dp, icons: Boolean, searchFocus: FocusRequester, onSearchFocus: (Boolean) -> Unit,
                     modifier: Modifier = Modifier) {
    val ket = LocalKet.current
    val nav = LocalNavigator.current
    val library = LocalLibrary.current
    val playlists by library.playlists.collectAsState()
    val lens = remember { SideLens() }
    Column(modifier.width(width).fillMaxHeight().glassShadow(26.dp)
        .glass(backdrop, 26.dp, ket.glassTint, GlassStyle.Panel, rim = ket.glassRim).testTag("sidebar")) {
        if (icons) SearchButton() else SearchField(searchFocus, onSearchFocus)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LensPill(lens, backdrop)
            val list = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize().lensArea(lens).fluidScroll(list), state = list,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                // Settings › Disposition: the sidebar's entries in the listener's order (`layoutTabs`, as the phone's tabs).
                DeskLayout.sidebarRows().forEach { row -> item("side:$row") { SidebarRow(row, icons, lens) } }
                PageRegistry.sidebar.forEach { p ->
                    item("extra:${p.id}") { SideItem(p.icon, p.title, Route.Extra(p.id), icons, lens) }
                }
                item("pl-h") { if (!icons) PlaylistsHeader() else Spacer(Modifier.height(14.dp)) }
                DeskLayout.sortedPlaylists(playlists).forEach { p -> item(p.id) { PlaylistItem(p, icons, lens) } }
            }
        }
        Box(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = 4.dp)) { SideItem(KetIcons.Settings, "Réglages", Route.Settings, icons, lens) }
    }
    // The selected entry is where the drop rests.
    LaunchedEffect(nav.section) { lens.selected = sideKey(nav.section) }
}

/** The key a sidebar row registers its place under, for a route. */
internal fun sideKey(route: Route): String = when (route) {
    is Route.Playlist -> "playlist:${route.id}"
    is Route.Extra -> "extra:${route.id}"
    else -> "route:$route"
}

/** Where the rows are and where the pointer is, for the lens. Positions in the list area's own pixels. */
@Stable
internal class SideLens {
    val rows = mutableStateMapOf<String, Rect>()
    var area: LayoutCoordinates? = null
    var selected by mutableStateOf<String?>(null)
    /** The pointer's height in the list area, or null when it is elsewhere. */
    var pointer by mutableStateOf<Float?>(null)
    var hovered by mutableStateOf<String?>(null)

    /** Where the drop should be: around the hovered row, leaning towards the pointer; else on the selected row. */
    fun target(): Rect? {
        val p = pointer
        val row = hovered?.let(rows::get)
        if (p != null && row != null) {
            val lean = (p - row.center.y) * LensMath.LEAN
            return row.translate(0f, lean)
        }
        return selected?.let(rows::get)
    }
}

internal object LensMath {
    /** How far the drop leans towards the pointer from the centre of the row under it. */
    const val LEAN = .35f
    /** How much a hovered entry grows. */
    const val ZOOM = 1.045f
    /** The drop stretches along its way with speed (px/s), up to this much. */
    const val STRETCH = .22f

    fun stretch(velocity: Float): Float = 1f + (abs(velocity) / 4000f).coerceAtMost(STRETCH)
}

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.lensArea(lens: SideLens): Modifier = onGloballyPositioned { lens.area = it }
    .onPointerEvent(PointerEventType.Enter, PointerEventPass.Initial) { e -> lens.pointer = e.changes.first().position.y }
    .onPointerEvent(PointerEventType.Move, PointerEventPass.Initial) { e -> lens.pointer = e.changes.first().position.y }
    .onPointerEvent(PointerEventType.Exit, PointerEventPass.Initial) { lens.pointer = null; lens.hovered = null }

/** The drop: a clear glass capsule behind the rows, sprung to [SideLens.target]. */
@Composable
private fun LensPill(lens: SideLens, backdrop: GlassBackdrop?) {
    val ket = LocalKet.current
    val target = lens.target()
    val top = remember { Animatable(0f) }
    val height = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (target != null) 1f else 0f, Motion.Springs.SMOOTH.motion())
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        if (!shown || Motion.reduced) { top.snapTo(t.top); height.snapTo(t.height); shown = true; return@LaunchedEffect }
        kotlinx.coroutines.coroutineScope {
            launch { top.animateTo(t.top, spring(dampingRatio = .62f, stiffness = 520f)) }
            launch { height.animateTo(t.height, spring(dampingRatio = .7f, stiffness = 600f)) }
        }
    }
    if (!shown || target == null && alpha == 0f) return
    val density = LocalDensity.current
    val left = target?.left ?: lens.rows.values.firstOrNull()?.left ?: 0f
    val w = target?.width ?: lens.rows.values.firstOrNull()?.width ?: 0f
    Box(Modifier.offset { IntOffset(left.toInt(), top.value.toInt()) }
        .size(with(density) { w.toDp() }, with(density) { height.value.coerceAtLeast(1f).toDp() })
        .graphicsLayer {
            val s = LensMath.stretch(top.velocity)
            scaleY = s; scaleX = 1f / s.coerceAtMost(1.08f); this.alpha = alpha
        }
        .glass(backdrop, 12.dp, ket.lensTint, GlassStyle.Drop, rim = ket.glassRim.copy(alpha = ket.glassRim.alpha * 1.6f))
        .testTag("side-lens"))
}

@Composable
private fun SearchField(focus: FocusRequester, onFocus: (Boolean) -> Unit) {
    val ket = LocalKet.current
    val search = LocalSearch.current
    val nav = LocalNavigator.current
    Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 10.dp).fillMaxWidth().height(34.dp)
        .clip(RoundedCornerShape(17.dp)).background(ket.fill).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(KetIcons.Search, null, Modifier.size(14.dp), tint = ket.secondary)
        Spacer(Modifier.width(8.dp))
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

/** The sidebar reduced to icons: search is a button that opens its page. */
@Composable
private fun SearchButton() {
    val nav = LocalNavigator.current
    Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        KetIconButton(KetIcons.Search, "Rechercher", { nav.root(Route.Search) }, Modifier.testTag("search-button"), size = 40.dp, glyph = 18.dp)
    }
}

@Composable
private fun PlaylistsHeader() {
    val ket = LocalKet.current
    val nav = LocalNavigator.current
    val library = LocalLibrary.current
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        KText("Playlists", Ket27Desk.Type.caption.copy(letterSpacing = Ket27Desk.Type.caption2.letterSpacing), Modifier.weight(1f),
            color = ket.secondary, weight = FontWeight.SemiBold)
        KetIconButton(KetIcons.Link, "Importer des playlists", { ImportDialog.open.value = true },
            size = 24.dp, glyph = 13.dp, modifier = Modifier.testTag("import-playlist"))
        KetIconButton(KetIcons.Plus, "Nouvelle playlist", {
            library.createPlaylist(AppText.t("Nouvelle playlist")) { id -> nav.root(Route.Playlist(id)) }
        }, size = 24.dp, glyph = 13.dp, modifier = Modifier.testTag("new-playlist"))
    }
}

@Composable
private fun SideHeader(label: String, icons: Boolean) {
    val ket = LocalKet.current
    if (icons) { Box(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 14.dp).height(.5.dp).background(ket.hairline)); return }
    KText(label, Ket27Desk.Type.caption, Modifier.padding(start = 12.dp, top = 22.dp, bottom = 6.dp), color = ket.secondary, weight = FontWeight.SemiBold)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SideRow(icon: ImageVector?, label: String, key: String, tag: String, lens: SideLens, icons: Boolean, selected: Boolean,
                    translate: Boolean = true, dropHover: Boolean = false, modifier: Modifier = Modifier,
                    leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val ket = LocalKet.current
    val (source, hovered) = rememberHover()
    DisposableEffect(key) { onDispose { lens.rows.remove(key) } }
    LaunchedEffect(hovered) { if (hovered) lens.hovered = key else if (lens.hovered == key) lens.hovered = null }
    val zoom by animateFloatAsState(if (hovered && !Motion.reduced) LensMath.ZOOM else 1f, spring(dampingRatio = .55f, stiffness = 500f))
    val row = modifier.fillMaxWidth().height(38.dp)
        .onGloballyPositioned { c -> lens.area?.takeIf { it.isAttached }?.let { a -> lens.rows[key] = a.localBoundingBoxOf(c) } }
        .clip(RoundedCornerShape(12.dp))
        .background(if (dropHover) ket.accent.copy(alpha = .22f) else Color.Transparent)
        .hoverable(source).clickable(interactionSource = source, indication = null, onClick = onClick).testTag(tag)
    val content: @Composable () -> Unit = {
        if (leading != null) leading() else if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = if (selected) ket.accent else ket.label.copy(alpha = .82f))
    }
    if (icons) {
        Tooltip(label) {
            Box(row.graphicsLayer { scaleX = zoom; scaleY = zoom }, contentAlignment = Alignment.Center) { content() }
        }
        return
    }
    Row(row.padding(horizontal = 12.dp).graphicsLayer { scaleX = zoom; scaleY = zoom; transformOrigin = TransformOrigin(0f, .5f) },
        verticalAlignment = Alignment.CenterVertically) {
        content()
        Spacer(Modifier.width(12.dp))
        KText(label, Ket27Desk.Type.subheadline, color = ket.label, weight = if (selected) FontWeight.SemiBold else FontWeight.Medium, translate = translate)
    }
}

/** One row of the sidebar's upper part ([DeskLayout.sidebarRows]). */
@Composable
private fun SidebarRow(row: String, icons: Boolean, lens: SideLens) {
    when (row) {
        "home" -> SideItem(Ket27Glyphs.Home, "Accueil", Route.Home, icons, lens)
        "explore" -> SideItem(Ket27Glyphs.Explore, "Explorer", Route.Explore, icons, lens)
        "search" -> SideItem(KetIcons.Search, "Rechercher", Route.Search, icons, lens)
        "favorites" -> SideItem(Ket27Glyphs.Heart, "Favoris", Route.Favorites, icons, lens)
        "header" -> SideHeader("Bibliothèque", icons)
        "recent" -> SideItem(KetIcons.Clock, "Récemment ajouté", Route.RecentlyAdded, icons, lens)
        "artists" -> SideItem(KetIcons.Account, "Artistes", Route.Artists, icons, lens)
        "albums" -> SideItem(KetIcons.Album, "Albums", Route.Albums, icons, lens)
        "songs" -> SideItem(KetIcons.Music, "Titres", Route.Songs, icons, lens)
        "downloads" -> SideItem(KetIcons.Download, "Téléchargements", Route.Downloads, icons, lens)
        "stats" -> SideItem(DeskGlyphs.Stats, "Statistiques", Route.Stats, icons, lens)
    }
}

@Composable
private fun SideItem(icon: ImageVector, label: String, route: Route, icons: Boolean, lens: SideLens) {
    val nav = LocalNavigator.current
    SideRow(icon, label, sideKey(route), "nav:$label", lens, icons, nav.section == route) { nav.root(route) }
}

@Composable
private fun PlaylistItem(p: UserPlaylist, icons: Boolean, lens: SideLens) {
    val nav = LocalNavigator.current
    val drag = LocalTrackDrag.current
    DisposableEffect(p.id) { onDispose { drag.unregister(p.id) } }
    val route = Route.Playlist(p.id)
    SideRow(null, p.name, sideKey(route), "playlist:${p.id}", lens, icons, nav.section == route, translate = false,
        dropHover = drag.hoveredTarget == p.id, modifier = Modifier.dropTarget(p.id, drag),
        leading = { Mosaic(p.tracks, if (icons) 26.dp else 22.dp) }) { nav.root(route) }
}

/** The soft shadow under floating glass. */
internal fun Modifier.glassShadow(radius: Dp, elevation: Dp = 18.dp): Modifier =
    this.then(Modifier.androidxShadow(elevation, RoundedCornerShape(radius)))

private fun Modifier.androidxShadow(elevation: Dp, shape: androidx.compose.ui.graphics.Shape) =
    shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = .22f), spotColor = Color.Black.copy(alpha = .28f))
