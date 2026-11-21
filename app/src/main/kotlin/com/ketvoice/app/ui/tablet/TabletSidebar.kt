package com.ketvoice.app.ui.tablet

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.R
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.CatalogRequest
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LargeScreenInfo
import com.ketvoice.app.ui.adaptive.LargeScreenNav
import com.ketvoice.app.ui.adaptive.NavigationMode
import com.ketvoice.app.ui.adaptive.hoverHighlight
import com.ketvoice.app.ui.adaptive.trackDropTarget
import com.ketvoice.app.ui.common.AccountAvatar
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Ket27Glyphs
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.launch

/** The listener's choice for the sidebar, kept in preferences of its own (`large-screen`, key `sidebar_collapsed`). */
object SidebarPrefs {
    private const val FILE = "large-screen"
    private const val KEY = "sidebar_collapsed"

    /** True: closed to a rail; false: open; null: never chosen, the width decides. */
    val collapsed = mutableStateOf<Boolean?>(null)
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        collapsed.value = if (prefs.contains(KEY)) prefs.getBoolean(KEY, false) else null
    }

    fun set(context: Context, value: Boolean) {
        collapsed.value = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }

    /** Flips the sidebar shown in [mode], when the window allows a sidebar at all. */
    fun toggle(context: Context, info: LargeScreenInfo?) {
        if (info == null || !info.sidebarAllowed) return
        set(context, info.navigation == NavigationMode.Sidebar)
    }

    internal fun resetForTests() { loaded = false; collapsed.value = null }
}

/** Where the sidebar sends the listener; [selected] says whether it is where the listener is. */
internal data class SidebarDestination(val label: String, val icon: ImageVector, val tag: String,
                                       val selected: Boolean, val open: () -> Unit)

/** The sections of the library the sidebar lists, in the library's own numbering. */
internal object SidebarSections {
    const val PLAYLISTS = 1
    const val ALBUMS = 2
    const val TITLES = 3
    const val ARTISTS = 4

    /** At most this many playlists are listed; the library holds the others. */
    const val PLAYLISTS_SHOWN = 8

    /** Pinned playlists first, then the newest. */
    fun playlists(all: List<LocalPlaylist>): List<LocalPlaylist> =
        (all.filter { it.pinned } + all.filterNot { it.pinned }.sortedByDescending { it.createdAt }).take(PLAYLISTS_SHOWN)
}

/**
 * The navigation of a large window, in glass, floating along the start edge: the full sidebar
 * (search, the three tabs, the sections of the library, pinned and recent playlists, which also
 * take a title dragged onto them) or, folded, a rail of symbols. The listener folds and unfolds it;
 * the choice is kept. On a medium window only the rail exists.
 */
@Composable
fun TabletNavigation(info: LargeScreenInfo, width: Dp, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val sidebar = info.navigation == NavigationMode.Sidebar
    Box(modifier.width(width).fillMaxHeight().statusBarsPadding().navigationBarsPadding()
        .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 0.dp)) {
    GlassPanel(Modifier.fillMaxSize().testTag(if (sidebar) "tablet-sidebar" else "tablet-rail"),
        RoundedCornerShape(30.dp), GlassParams.navigation(dark = theme.isDark), enabled = AppSettings.liquidGlass.value) {
        // Without glass (switched off), the panel is a card of its own colour.
        Box(Modifier.fillMaxSize().then(if (AppSettings.liquidGlass.value) Modifier else
            Modifier.clip(RoundedCornerShape(30.dp)).background(theme.card))) {
            // The two forms cross-fade while the panel springs to its new width (nothing moves under reduced motion).
            AnimatedContent(sidebar, transitionSpec = {
                fadeIn(androidx.compose.animation.core.tween(com.ketvoice.app.ui.motion.Motion.duration(180)))
                    .togetherWith(fadeOut(androidx.compose.animation.core.tween(com.ketvoice.app.ui.motion.Motion.duration(120))))
            }, label = "sidebar-fold") { open ->
                if (open) SidebarContent(info) { SidebarPrefs.toggle(context, info) }
                else RailContent(info) { SidebarPrefs.toggle(context, info) }
            }
        }
    }
    }
}

@Composable
private fun destinations(): List<SidebarDestination> {
    val overlay = AppUi.overlay.intValue
    val tab = AppUi.tab.intValue
    val section = LargeScreenNav.shownLibrarySection.intValue
    return listOf(
        SidebarDestination("Accueil", Ket27Glyphs.Home, "sidebar-home", tab == AppUi.HOME && overlay == AppUi.NONE) { AppUi.selectTab(AppUi.HOME) },
        SidebarDestination("Explorer", Ket27Glyphs.Explore, "sidebar-explorer", tab == AppUi.EXPLORER && overlay == AppUi.NONE) { AppUi.selectTab(AppUi.EXPLORER) },
        SidebarDestination("Bibliothèque", Ket27Glyphs.Library, "sidebar-library",
            tab == AppUi.LIBRARY && overlay == AppUi.NONE && section == 0) { openLibrary(0) })
}

@Composable
private fun librarySections(): List<SidebarDestination> {
    val overlay = AppUi.overlay.intValue
    val library = AppUi.tab.intValue == AppUi.LIBRARY && overlay == AppUi.NONE
    val section = LargeScreenNav.shownLibrarySection.intValue
    val catalog = AppUi.catalogRequest.value
    return listOf(
        SidebarDestination("Playlists", KetIcons.Playlist, "sidebar-playlists", library && section == SidebarSections.PLAYLISTS) { openLibrary(SidebarSections.PLAYLISTS) },
        SidebarDestination("Artistes", KetIcons.Account, "sidebar-artists", library && section == SidebarSections.ARTISTS) { openLibrary(SidebarSections.ARTISTS) },
        SidebarDestination("Albums", KetIcons.Album, "sidebar-albums", library && section == SidebarSections.ALBUMS) { openLibrary(SidebarSections.ALBUMS) },
        SidebarDestination("Titres", KetIcons.Music, "sidebar-titles", library && section == SidebarSections.TITLES) { openLibrary(SidebarSections.TITLES) },
        SidebarDestination("Favoris", KetIcons.Heart, "sidebar-favorites", overlay == AppUi.CATALOG && catalog?.kind == "favorites") {
            AppUi.selectTab(AppUi.LIBRARY); AppUi.openCatalog(CatalogRequest("favorites", "local", "Favoris")) },
        SidebarDestination("Téléchargements", KetIcons.Download, "sidebar-downloads", overlay == AppUi.DOWNLOADS) { AppUi.selectTab(AppUi.LIBRARY); AppUi.openDownloads() },
        SidebarDestination("Musique locale", KetIcons.Folder, "sidebar-local", overlay == AppUi.LOCAL_FILES) { AppUi.selectTab(AppUi.LIBRARY); AppUi.openLocalFiles() },
        SidebarDestination("Replay", KetIcons.Clock, "sidebar-replay", overlay == AppUi.REPLAY) { AppUi.selectTab(AppUi.LIBRARY); AppUi.openReplay() },
        SidebarDestination("Statistiques", com.ketvoice.app.ui.stats.StatsIcon, "sidebar-stats", overlay == AppUi.STATS) {
            AppUi.selectTab(AppUi.LIBRARY); AppUi.closeOverlays(); AppUi.openStats() })
}

private fun openLibrary(section: Int) {
    AppUi.selectTab(AppUi.LIBRARY)
    LargeScreenNav.librarySection.value = section
    LargeScreenNav.shownLibrarySection.intValue = section
}

@Composable
private fun SidebarContent(info: LargeScreenInfo, onFold: () -> Unit) {
    val theme = LocalTheme.current
    val playlists by PlaylistStore.lists.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo_clean), null, Modifier.size(22.dp), contentScale = ContentScale.Fit,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(theme.onBackground))
            Text("Ketvoice", localize = false, color = theme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 9.dp))
            if (info.sidebarAllowed) SidebarIconButton(SidebarGlyph.Fold, "Réduire la barre latérale", "sidebar-fold", onClick = onFold)
        }
        SearchPill(Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
            destinations().forEach { SidebarRow(it) }
            SidebarHeading("Bibliothèque")
            librarySections().forEach { SidebarRow(it) }
            SidebarHeading("Playlists", action = { SidebarIconButton(KetIcons.Plus, "Créer une playlist", "sidebar-new-playlist", size = 36.dp) {
                AppUi.showCreatePlaylist.value = true } })
            SidebarSections.playlists(playlists).forEach { playlist -> PlaylistRow(playlist) }
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val interactions = remember { MutableInteractionSource() }
            Row(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).hoverHighlight(interactions, RoundedCornerShape(16.dp))
                .clickable(interactions, null, role = Role.Button) { AppUi.showAccount.value = true }
                .padding(6.dp).testTag("sidebar-account"), verticalAlignment = Alignment.CenterVertically) {
                AccountAvatar("K", 34.dp)
                Text("Compte", color = theme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 10.dp))
            }
            SidebarIconButton(KetIcons.Settings, "Réglages", "sidebar-settings", selected = AppUi.overlay.intValue == AppUi.SETTINGS) { AppUi.openSettings() }
        }
    }
}

@Composable
private fun RailContent(info: LargeScreenInfo, onUnfold: () -> Unit) {
    val playlists by PlaylistStore.lists.collectAsState()
    Column(Modifier.fillMaxSize().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (info.sidebarAllowed) SidebarIconButton(SidebarGlyph.Unfold, "Afficher la barre latérale", "sidebar-unfold", onClick = onUnfold)
        else Image(painterResource(R.drawable.logo_clean), null, Modifier.padding(12.dp).size(24.dp), contentScale = ContentScale.Fit,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(LocalTheme.current.onBackground))
        Spacer(Modifier.height(6.dp))
        SidebarIconButton(Ket27Glyphs.Search, "Rechercher", "rail-search", selected = AppUi.overlay.intValue == AppUi.SEARCH) { AppUi.openSearch() }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            destinations().forEach { SidebarIconButton(it.icon, it.label, it.tag.replace("sidebar", "rail"), selected = it.selected, onClick = it.open) }
            Box(Modifier.padding(vertical = 8.dp).width(28.dp).height(1.dp).background(LocalTheme.current.hairline))
            // Pinned playlists stay one touch away, as their artwork.
            SidebarSections.playlists(playlists).filter { it.pinned }.take(4).forEach { playlist -> RailPlaylist(playlist) }
        }
        SidebarIconButton(KetIcons.Settings, "Réglages", "rail-settings", selected = AppUi.overlay.intValue == AppUi.SETTINGS) { AppUi.openSettings() }
        Box(Modifier.padding(top = 6.dp).size(48.dp).clip(CircleShape).clickable(role = Role.Button) { AppUi.showAccount.value = true }
            .semantics { contentDescription = AppText.t("Compte") }, contentAlignment = Alignment.Center) { AccountAvatar("K", 36.dp) }
    }
}

@Composable
private fun SearchPill(modifier: Modifier) {
    val theme = LocalTheme.current
    val selected = AppUi.overlay.intValue == AppUi.SEARCH
    val interactions = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    Row(modifier.fillMaxWidth().clip(shape)
        .background(theme.onBackground.copy(alpha = if (selected) .12f else if (theme.isDark) .08f else .06f))
        .hoverHighlight(interactions, shape)
        .clickable(interactions, null, role = Role.Button) { AppUi.openSearch() }
        .semantics { this.selected = selected }
        .heightIn(min = 42.dp).padding(horizontal = 12.dp).testTag("sidebar-search"), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ket27Glyphs.Search, null, tint = theme.secondary, modifier = Modifier.size(17.dp))
        Text("Rechercher", color = theme.secondary, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 8.dp))
        Text("Ctrl F", localize = false, color = theme.secondary.copy(alpha = .7f), fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun SidebarHeading(title: String, action: (@Composable () -> Unit)? = null) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 18.dp, bottom = 4.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = theme.onBackground, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** One destination of the sidebar: its symbol in the accent, its name, a soft pill when it is where the listener is. */
@Composable
private fun SidebarRow(destination: SidebarDestination) {
    val theme = LocalTheme.current
    val interactions = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxWidth().clip(shape)
        .background(if (destination.selected) theme.onBackground.copy(alpha = if (theme.isDark) .13f else .08f) else androidx.compose.ui.graphics.Color.Transparent)
        .hoverHighlight(interactions, shape)
        .clickable(interactions, null, role = Role.Tab, onClick = destination.open)
        .semantics { selected = destination.selected }
        .heightIn(min = 44.dp).padding(horizontal = 10.dp).testTag(destination.tag), verticalAlignment = Alignment.CenterVertically) {
        Icon(destination.icon, null, tint = theme.accent, modifier = Modifier.size(22.dp))
        Text(destination.label, color = theme.onBackground, fontSize = 16.sp,
            fontWeight = if (destination.selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
    }
}

/** A playlist of the sidebar: open it, or drop a title on it to add the title. */
@Composable
private fun PlaylistRow(playlist: LocalPlaylist) {
    val theme = LocalTheme.current
    val scope = rememberCoroutineScope()
    var over by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    val selected = AppUi.overlay.intValue == AppUi.PLAYLIST && AppUi.playlistId.value == playlist.id
    Row(Modifier.fillMaxWidth().clip(shape)
        .background(when {
            over -> theme.accent.copy(alpha = .22f)
            selected -> theme.onBackground.copy(alpha = if (theme.isDark) .13f else .08f)
            else -> androidx.compose.ui.graphics.Color.Transparent
        })
        .then(if (over) Modifier.border(1.5.dp, theme.accent, shape) else Modifier)
        .trackDropTarget(onOver = { over = it }) { track ->
            scope.launch {
                runCatching { PlaylistStore.add(playlist.id, track) }.onSuccess {
                    com.ketvoice.app.ui.common.TrackActionFeedback.snackbar.currentSnackbarData?.dismiss()
                    com.ketvoice.app.ui.common.TrackActionFeedback.snackbar.showSnackbar(AppText.t("Ajouté à la playlist"))
                }
            }
        }
        .hoverHighlight(interactions, shape)
        .clickable(interactions, null, role = Role.Button) { AppUi.openPlaylist(playlist.id) }
        .semantics { this.selected = selected }
        .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 4.dp).testTag("sidebar-playlist"), verticalAlignment = Alignment.CenterVertically) {
        SmallPlaylistArtwork(playlist, 34.dp, 7.dp)
        Text(playlist.name, localize = false, color = theme.onBackground, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 10.dp))
        if (playlist.pinned) Icon(androidx.compose.material.icons.Icons.Filled.PushPin, null, tint = theme.secondary,
            modifier = Modifier.size(13.dp))
    }
}

@Composable
private fun RailPlaylist(playlist: LocalPlaylist) {
    val scope = rememberCoroutineScope()
    var over by remember { mutableStateOf(false) }
    val theme = LocalTheme.current
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
        .then(if (over) Modifier.border(2.dp, theme.accent, RoundedCornerShape(12.dp)) else Modifier)
        .trackDropTarget(onOver = { over = it }) { track -> scope.launch { runCatching { PlaylistStore.add(playlist.id, track) } } }
        .clickable(role = Role.Button) { AppUi.openPlaylist(playlist.id) }
        .semantics { contentDescription = playlist.name }, contentAlignment = Alignment.Center) {
        SmallPlaylistArtwork(playlist, 38.dp, 9.dp)
    }
}

/** A playlist's artwork, small: drawn at the library's size and scaled down, so that it keeps its square corners and its mosaic. */
@Composable
private fun SmallPlaylistArtwork(playlist: LocalPlaylist, side: Dp, radius: Dp) {
    Box(Modifier.size(side).clip(RoundedCornerShape(radius)), contentAlignment = Alignment.Center) {
        com.ketvoice.app.ui.library.PlaylistArtwork(playlist, playlist.name, Modifier.requiredSize(side * 4).graphicsLayer {
            scaleX = .25f; scaleY = .25f })
    }
}

@Composable
private fun SidebarIconButton(icon: ImageVector, label: String, tag: String, size: Dp = 48.dp, selected: Boolean = false, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val interactions = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    val description = AppText.t(label)
    Box(Modifier.size(size).clip(shape)
        .background(if (selected) theme.onBackground.copy(alpha = if (theme.isDark) .13f else .08f) else androidx.compose.ui.graphics.Color.Transparent)
        .hoverHighlight(interactions, shape)
        .clickable(interactions, null, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description; this.selected = selected }
        .testTag(tag), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (selected) theme.accent else theme.onBackground.copy(alpha = .78f), modifier = Modifier.size(22.dp))
    }
}

/** The two symbols of the fold button: a panel with its sidebar, drawn as iPadOS draws it. */
internal object SidebarGlyph {
    private fun panel(name: String, filled: Boolean) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        val ink = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)
        // The window: a rectangle with rounded corners.
        addPath(androidx.compose.ui.graphics.vector.PathBuilder().apply {
            moveTo(5f, 4.5f); lineTo(19f, 4.5f); arcTo(2.5f, 2.5f, 0f, false, true, 21.5f, 7f)
            lineTo(21.5f, 17f); arcTo(2.5f, 2.5f, 0f, false, true, 19f, 19.5f)
            lineTo(5f, 19.5f); arcTo(2.5f, 2.5f, 0f, false, true, 2.5f, 17f)
            lineTo(2.5f, 7f); arcTo(2.5f, 2.5f, 0f, false, true, 5f, 4.5f); close()
        }.nodes, stroke = ink, strokeLineWidth = 1.8f)
        // The edge of the sidebar.
        addPath(androidx.compose.ui.graphics.vector.PathBuilder().apply { moveTo(9.5f, 4.5f); lineTo(9.5f, 19.5f) }.nodes,
            stroke = ink, strokeLineWidth = 1.8f)
        // Open: the sidebar is filled.
        if (filled) addPath(androidx.compose.ui.graphics.vector.PathBuilder().apply {
            moveTo(5f, 6.3f); lineTo(8f, 6.3f); lineTo(8f, 17.7f); lineTo(5f, 17.7f)
            arcTo(.7f, .7f, 0f, false, true, 4.3f, 17f); lineTo(4.3f, 7f); arcTo(.7f, .7f, 0f, false, true, 5f, 6.3f); close()
        }.nodes, fill = ink)
    }.build()

    val Fold: ImageVector by lazy { panel("sidebar-fold", filled = true) }
    val Unfold: ImageVector by lazy { panel("sidebar-unfold", filled = false) }
}
