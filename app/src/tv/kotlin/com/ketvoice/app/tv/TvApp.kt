package com.ketvoice.app.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.common.Ket27Glyphs
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.collectPick
import com.ketvoice.app.ui.common.pick
import kotlinx.coroutines.delay

/**
 * Ketvoice TV's whole interface: the side menu (Search, Home, Library, Settings, and what plays), the page of the
 * current section, Now Playing over everything, and a title's options. Back goes up the stack ([TvNavigator]);
 * [onExit] is called when there is nowhere left to go back to.
 */
@Composable
fun TvApp(actions: TvActions, tier: TvTier, onExit: () -> Unit) {
    KetvoiceTheme(useDark = true) {
        CompositionLocalProvider(LocalTvTier provides tier, LocalTvActions provides actions) {
            val nav = actions.nav
            BackHandler { if (!nav.back()) onExit() }
            Box(Modifier.fillMaxSize().background(TvDesign.Ink.page)) {
                if (nav.current == TvRoute.NowPlaying) TvPageHost(nav, Modifier.fillMaxSize())
                else {
                    TvAmbientBackground()
                    TvPageHost(nav, Modifier.fillMaxSize().padding(start = TvDesign.Size.railClosed))
                    TvRail(nav)
                }
                TvTrackMenu(actions)
            }
            TvRequests(actions)
        }
    }
}

/** The pages in the stack keep their scroll and focus while they are in it ([rememberSaveableStateHolder], [FocusMemory]). */
@Composable
private fun TvPageHost(nav: TvNavigator, modifier: Modifier) {
    val holder = rememberSaveableStateHolder()
    val memories = remember { HashMap<String, FocusMemory>() }
    LaunchedEffect(nav.stack) { nav.takeDropped().forEach { holder.removeState(it.key); memories.remove(it.key) } }
    val route = nav.current
    val page = remember(route.key) { PageFocus(memories.getOrPut(route.key) { FocusMemory() }) }
    Box(modifier.focusProperties { onEnter = { page.restore() } }.focusGroup().testTag("tv-page")) {
        CompositionLocalProvider(LocalPageFocus provides page) {
            holder.SaveableStateProvider(route.key) { TvPage(route) }
        }
        PageFocusFallback(page)
    }
}

@Composable
private fun TvPage(route: TvRoute) {
    val actions = LocalTvActions.current
    when (route) {
        TvRoute.Home -> TvHomePage()
        TvRoute.Search -> TvSearchPage(actions.searchSeed.value)
        TvRoute.Library -> TvLibraryPage()
        TvRoute.Settings -> TvSettingsPage()
        TvRoute.NowPlaying -> TvNowPlayingPage()
        TvRoute.SignIn -> TvSignInPage()
        is TvRoute.Collection -> TvCollectionPage(route)
        is TvRoute.Artist -> TvArtistPage(route)
    }
}

/** Behind the sections: the colours of what plays, very dim; black when nothing does. */
@Composable
private fun TvAmbientBackground() {
    val current by remember { Player.state.pick { it.current } }.collectPick()
    val covers by com.ketvoice.app.data.ArtworkRepo.covers.collectAsState()
    val cover = current?.let { com.ketvoice.app.data.MediaOverrides.cover(it, covers["t:${it.id}"] ?: it.coverUrl) }
    TvBackdrop(cover, Modifier.fillMaxSize(), veil = .82f)
}

/**
 * The side menu: icons only, it opens (with the names) while the focus is in it, over the page (the page is never
 * laid out again). Entering it puts the focus on the current section.
 */
@Composable
private fun TvRail(nav: TvNavigator) {
    var open by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (open) TvDesign.Size.railOpen else TvDesign.Size.railClosed, spring(stiffness = 700f), label = "rail")
    val requesters = remember { TvRoute.ROOTS.associateWith { FocusRequester() } }
    val current by remember { Player.state.pick { it.current } }.collectPick()
    val scrim by androidx.compose.animation.core.animateFloatAsState(if (open) 1f else 0f, label = "rail-scrim")
    // Open, the menu darkens the page beside it (a veil that fades out to the right), never a hard edge.
    Box(Modifier.fillMaxHeight().width(TvDesign.Size.railOpen + 240.dp).graphicsLayer { alpha = scrim }
        .background(Brush.horizontalGradient(0f to Color(0xF7070709), .5f to Color(0xE6070709), 1f to Color.Transparent)))
    Column(Modifier.fillMaxHeight().width(width)
        .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .55f), Color.Transparent)))
        .onFocusChanged { open = it.hasFocus }
        .focusProperties { onEnter = { runCatching { requesters.getValue(nav.section).requestFocus() } } }
        .focusGroup()
        .padding(vertical = TvDesign.Safe.vertical, horizontal = 12.dp).testTag("tv-rail"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(8.dp))
        RailLogo(open)
        Spacer(Modifier.height(18.dp))
        TvRoute.ROOTS.forEach { root ->
            RailItem(railIcon(root), railLabel(root), selected = nav.section == root, open = open, requester = requesters.getValue(root),
                tag = "rail-${root.key}") { nav.section(root) }
        }
        Spacer(Modifier.weight(1f))
        current?.let { track ->
            RailItem(KetIcons.Music, track.title, selected = false, open = open, requester = remember { FocusRequester() }, tag = "rail-now-playing",
                localize = false, cover = com.ketvoice.app.data.ArtworkRepo.coverForTrack(track)) { nav.open(TvRoute.NowPlaying) }
        }
    }
}

@Composable
private fun RailLogo(open: Boolean) {
    Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.ketvoice.app.R.drawable.logo_clean), null, Modifier.size(28.dp))
        if (open) {
            Spacer(Modifier.width(12.dp))
            Text("KETVOICE", localize = false, style = TvDesign.Type.caption, color = TvDesign.Ink.secondary)
        }
    }
}

private fun railIcon(route: TvRoute): ImageVector = when (route) {
    TvRoute.Search -> Ket27Glyphs.Search
    TvRoute.Home -> Ket27Glyphs.Home
    TvRoute.Library -> Ket27Glyphs.Library
    else -> KetIcons.Settings
}

private fun railLabel(route: TvRoute): String = when (route) {
    TvRoute.Search -> "Rechercher"
    TvRoute.Home -> "Accueil"
    TvRoute.Library -> "Bibliothèque"
    else -> "Réglages"
}

@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, open: Boolean, requester: FocusRequester, tag: String,
                     localize: Boolean = true, cover: String? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(TvDesign.Radius.pill)
    val ink = when {
        focused -> TvDesign.Ink.onFocusFill
        selected -> TvDesign.Ink.primary
        else -> TvDesign.Ink.secondary
    }
    Row(Modifier.fillMaxWidth().height(44.dp).focusRequester(requester).clip(shape)
        .background(if (focused) TvDesign.Ink.focusFill else if (selected) Color.White.copy(alpha = .12f) else Color.Transparent)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        if (cover != null) TvCover(cover, 24.dp, RoundedCornerShape(6.dp)) else com.ketvoice.app.ui.common.Icon(icon, null, Modifier.size(22.dp), tint = ink)
        if (open) {
            Spacer(Modifier.width(14.dp))
            Text(label, localize = localize, style = TvDesign.Type.label, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A title's options (the menu key or a long press): next, queue, favourite, radio, its artist. */
@Composable
private fun TvTrackMenu(actions: TvActions) {
    val track = actions.menu.value ?: return
    val first = remember(track.id) { FocusRequester() }
    BackHandler { actions.menu.value = null }
    LaunchedEffect(track.id) { delay(16); runCatching { first.requestFocus() } }
    fun close() { actions.menu.value = null }
    val favorite = track.id in com.ketvoice.app.data.AppSettings.favoriteIds.value
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f)).testTag("track-menu"), contentAlignment = Alignment.Center) {
        Column(Modifier.width(420.dp).clip(RoundedCornerShape(TvDesign.Radius.panel)).background(Color(0xFF1C1C22)).padding(24.dp)
            .focusGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(track.title, localize = false, style = TvDesign.Type.row, color = TvDesign.Ink.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, localize = false, style = TvDesign.Type.caption, color = TvDesign.Ink.secondary, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            MenuButton("Lire ensuite", Modifier.focusRequester(first)) { Player.playNext(track); close() }
            MenuButton("Ajouter à la file d'attente") { Player.enqueue(listOf(track)); close() }
            MenuButton(if (favorite) "Retirer des favoris" else "Ajouter aux favoris") { com.ketvoice.app.ui.player.toggleFavorite(track); close() }
            MenuButton("Lancer la radio") { Player.startRadio(track); close(); actions.nav.open(TvRoute.NowPlaying) }
            if (track.artist.isNotBlank()) MenuButton("Aller à l'artiste") {
                close(); actions.nav.open(TvRoute.Artist(track.artistId?.takeIf { it.startsWith("UC") }.orEmpty(),
                    com.ketvoice.app.data.PersonalMixes.primaryArtist(track)))
            }
        }
    }
}

@Composable
private fun MenuButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TvButton(label, null, onClick, modifier.fillMaxWidth(), tag = "menu-$label")
}

/** Requests from outside the interface: a voice request that started playback, a search asked from the TV's assistant. */
@Composable
private fun TvRequests(actions: TvActions) {
    val showPlayer = com.ketvoice.app.ui.AppUi.showPlayer.value
    LaunchedEffect(showPlayer) {
        if (!showPlayer) return@LaunchedEffect
        com.ketvoice.app.ui.AppUi.showPlayer.value = false
        if (Player.state.value.current != null) actions.nav.open(TvRoute.NowPlaying)
    }
}
