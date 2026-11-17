package com.ketvoice.app.ui.sub

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtistCache
import com.ketvoice.app.data.ArtistPage
import com.ketvoice.app.data.ArtistPages
import com.ketvoice.app.data.ArtistSignatures
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LargeScreen
import com.ketvoice.app.ui.common.GlassBackButton
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.motion.Motion
import kotlinx.coroutines.launch

/**
 * The artist page (6.1, after Apple Music): a large hero (photo or Canvas, the name over it, Listen,
 * Shuffle and the favourite in glass), « Titres populaires » (five, then all), the latest release,
 * the discography in shelves by kind, the similar artists, « À propos » with the biography in the
 * app's language and the signature, the artist radio. Everything comes from the catalogue
 * (YouTube Music); a section without data is not shown. A wide window lays the titles in columns
 * beside the latest release under a banner; phones and Quest keep one column.
 */
@Composable
fun ArtistScreen(request: AppUi.ArtistRequest? = AppUi.artistRequest.value) {
    request ?: return
    var page by remember(request.id, request.name) { mutableStateOf<ArtistPage?>(null) }
    var loading by remember(request.id, request.name) { mutableStateOf(true) }
    var failed by remember(request.id, request.name) { mutableStateOf(false) }
    var attempt by remember(request.id, request.name) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(request.id, request.name, attempt) {
        loading = true
        failed = false
        // Disk + memory cache first: a previously opened artist renders at once.
        val cached = ArtistCache.get(request.id, request.name)
        if (cached != null) {
            page = cached
            loading = false
        }
        runCatching {
            // A title does not always carry a channel id: the artist is then found by name (data/ArtistPages).
            val resolved = ArtistPages.fetch(request.id, request.name)
            if (resolved == null && cached == null) ArtistCache.put(request.id, request.name, null)
            if (resolved != null) {
                ArtistCache.put(request.id, request.name, resolved)
                page = resolved
                runCatching { ArtworkRepo.enrich(resolved.tracks) }
            }
        }.onFailure { if (cached == null) page = null }
        if (page == null) failed = true
        loading = false
    }
    ArtistPageFrame(request, page, loading, failed, attempt, onRetry = { attempt++ }, onRefresh = {
        // Full reload: drop the cached page and artwork for this artist, then fetch the current server version.
        scope.launch {
            ArtistCache.invalidate(request.id, request.name)
            ArtistSignatures.invalidate(request.name)
            attempt++
        }
    })
}

/**
 * The artist page around what was loaded: its colour taken from the portrait, the skeleton while it loads,
 * the error with Retry, the page itself, and the floating bar.
 */
@Composable
internal fun ArtistPageFrame(request: AppUi.ArtistRequest, page: ArtistPage?, loading: Boolean, failed: Boolean,
                             revision: Int, onRetry: () -> Unit, onRefresh: () -> Unit) {
    val baseTheme = LocalTheme.current
    val accent = com.ketvoice.app.theme.rememberCoverAccent(page?.artist?.photo)?.first
    // The page takes its colour from the portrait: the header reports it once it is known.
    var portraitTone by remember(request) { mutableStateOf<androidx.compose.ui.graphics.Color?>(null) }
    val pageTint by androidx.compose.animation.animateColorAsState(
        artistPageTint(baseTheme.background, portraitTone.takeIf { AppSettings.albumColors.value }, baseTheme.isDark),
        tween(Motion.duration(520)), label = "artist-page-tint")
    val theme = baseTheme.copy(
        background = pageTint,
        card = androidx.compose.ui.graphics.lerp(pageTint, baseTheme.onBackground, if (baseTheme.isDark) .08f else .05f),
        accent = if (AppSettings.albumColors.value) accent ?: baseTheme.accent else baseTheme.accent
    )
    val context = androidx.compose.ui.platform.LocalContext.current
    val artist = page?.artist
    val listState = rememberLazyListState()
    // Collapse is read from the real header bounds, so a fling, a restored back stack or an
    // instant jump all produce the same top-bar state, no jump.
    val collapse = remember {
        derivedStateOf {
            val header = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "artist-header" }
            if (header == null) if (listState.firstVisibleItemIndex > 0) 1f else 0f
            else {
                val scrolled = (-header.offset).toFloat()
                val span = (header.size * 0.62f).coerceAtLeast(1f)
                AlbumPresentation.smoothProgress(scrolled / span)
            }
        }
    }
    CompositionLocalProvider(LocalTheme provides theme) {
    Box(Modifier.fillMaxSize().background(theme.background)) {
        when {
            loading -> ArtistSkeleton(theme)
            failed || page == null -> Column(Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Cette page d'artiste n'a pas pu être chargée.", color = theme.onBackground,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Vérifiez la connexion puis réessayez.", color = theme.secondary, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp))
                Text("Réessayer", color = theme.accent, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onRetry).padding(top = 20.dp, bottom = 12.dp))
            }
            else -> ArtistPageContent(page!!, listState, { collapse.value }, revision, onRefresh) { portraitTone = it }
        }
        // The bar: back, then the owner's visuals and Share in one capsule of glass; once the hero has scrolled
        // away, the name settles in the middle over the page's faded edge and a small Play joins them.
        val publisher by com.ketvoice.app.data.PublisherSession.allowed.collectAsState()
        val padActions = page?.let { shown ->
            listOfNotNull(
                com.ketvoice.app.ui.common.PadAction(KetIcons.Appearance, "Modifier les visuels", "artist-visuals") {
                    AppUi.openVisuals(listOf(com.ketvoice.app.data.VisualTarget.artist(shown.artist.name, shown.artist.photo)))
                }.takeIf { publisher },
                com.ketvoice.app.ui.common.PadAction(KetIcons.Share, "Partager l'artiste", "artist-share") {
                    com.ketvoice.app.util.ShareLinks.shareArtist(context, shown.artist.id, shown.artist.name)
                })
        }.orEmpty()
        val tracks = page?.tracks.orEmpty()
        val barSide = barSidePadding(padActions.size, tracks.isNotEmpty())
        com.ketvoice.app.ui.common.ScrollEdgeTitle(artist?.name ?: request.name,
            { if (page == null) 0f else collapse.value }, Modifier.align(Alignment.TopCenter),
            tag = "artist-bar-title", localize = false, sidePadding = barSide, textTop = 8.dp)
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassBackButton { AppUi.closeOverlay() }
            // While the page cannot show its hero, the name keeps the context beside the back button.
            if (page == null) Text(request.name, localize = false, color = theme.onBackground, fontSize = 18.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp))
            else Spacer(Modifier.weight(1f))
            if (tracks.isNotEmpty()) Box(Modifier.graphicsLayer { alpha = collapse.value }
                .then(if (collapse.value < .98f) Modifier.clearAndSetSemantics {} else Modifier)) {
                com.ketvoice.app.ui.common.LiquidGlassButton({ Player.setShuffle(false); Player.playQueue(tracks) },
                    Modifier.size(44.dp), enabled = collapse.value >= .98f) {
                    com.ketvoice.app.ui.common.Icon(KetIcons.Play, "Lecture", tint = theme.accent, modifier = Modifier.size(20.dp))
                }
            }
            com.ketvoice.app.ui.common.GlassButtonPad(padActions, height = 44.dp, captured = true, tag = "artist-actions")
        }
    }
    }
}

/** Widens the content by [by] towards the start and moves it there, so the margin of its rows falls outside. */
private fun Modifier.bleedStart(by: Dp): Modifier = layout { measurable, constraints ->
    val px = by.roundToPx()
    if (!constraints.hasBoundedWidth) return@layout measurable.measure(constraints).let { p -> layout(p.width, p.height) { p.placeRelative(0, 0) } }
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + px, maxWidth = constraints.maxWidth + px))
    layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(-px, 0) }
}

/** Room the centred bar title leaves on each side: the back button on one, the Play and the capsule on the other. */
internal fun barSidePadding(padActions: Int, play: Boolean): Dp {
    val pad = when { padActions <= 0 -> 0f; padActions == 1 -> 44f; else -> 6f + 44f * padActions }
    val end = 12f + pad + (if (play) 52f else 0f) + 8f
    return maxOf(64f, end).dp
}

@Composable
private fun ArtistPageContent(data: ArtistPage, listState: androidx.compose.foundation.lazy.LazyListState,
                              collapse: () -> Float, revision: Int, onRefresh: () -> Unit,
                              onTone: (androidx.compose.ui.graphics.Color?) -> Unit) {
    val theme = LocalTheme.current
    val art by ArtworkRepo.covers.collectAsState()
    val player by Player.state.collectAsState()
    val artwork = rememberArtistArtwork(data.artist.name, revision)
    var expanded by remember(data.artist.id) { mutableStateOf(false) }
    val latest = remember(data.albums) { ArtistDiscography.latest(data.albums) }
    val shelves = remember(data.albums) { ArtistDiscography.shelves(data.albums) }
    val bottom = com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout.current.bottomPaddingDp.dp + 40.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = ArtistLayout.wide(maxWidth.value)
        val margin = ArtistLayout.margin(wide).dp
        val cell = if (wide) LargeScreen.shelfCell(maxWidth.value, 170f, margin.value, 16f).dp else 150.dp
        val popular = ArtistDiscography.popular(data.tracks, expanded)
        val canExpand = data.tracks.size > ArtistDiscography.POPULAR_PREVIEW
        val showAll: (() -> Unit)? = if (canExpand) ({ expanded = !expanded }) else null
        val row: @Composable (Track, Int) -> Unit = { track, index ->
            com.ketvoice.app.ui.common.SwipeTrackActions(track) {
                com.ketvoice.app.ui.common.TrackRow(track, MediaOverrides.cover(track, art["t:${track.id}"] ?: track.coverUrl),
                    player.current?.id == track.id, player.isPlaying, { Player.playQueue(data.tracks, index) }, rank = index + 1)
            }
        }
        LazyColumn(Modifier.fillMaxSize().testTag("artist-list"), state = listState, contentPadding = PaddingValues(bottom = bottom)) {
            item(key = "artist-header") { ArtistHeader(data, collapse, artwork, onTone) }
            if (wide && (data.tracks.isNotEmpty() || latest != null)) item(key = "artist-top") {
                // The room of a wide window: the latest release beside the popular titles, in columns.
                Row(Modifier.fillMaxWidth().padding(horizontal = margin).testTag("artist-list-pane"),
                    horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    if (latest != null) Column(Modifier.weight(.36f)) {
                        PageSectionTitle("Dernière sortie", 0.dp)
                        LatestReleaseCard(latest, cover = 132.dp)
                    }
                    if (data.tracks.isNotEmpty()) BoxWithConstraints(Modifier.weight(.64f)) {
                        val columns = ArtistLayout.popularColumns(maxWidth.value)
                        Column(Modifier.fillMaxWidth().testTag("artist-popular")) {
                            PageSectionTitle("Titres populaires", 0.dp, action = if (expanded) "Afficher moins" else "Tout afficher",
                                actionTag = "artist-show-all", onAction = showAll)
                            val perColumn = (popular.size + columns - 1) / columns
                            // The rows keep their own margin: they reach back over it so the numbers line up with the titles.
                            Row(Modifier.fillMaxWidth().bleedStart(com.ketvoice.app.ui.common.pageMargin),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                repeat(columns) { column ->
                                    Column(Modifier.weight(1f)) {
                                        popular.drop(column * perColumn).take(perColumn).forEachIndexed { i, track ->
                                            row(track, column * perColumn + i)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!wide && data.tracks.isNotEmpty()) {
                item(key = "artist-popular-title") {
                    PageSectionTitle("Titres populaires", margin, Modifier.testTag("artist-popular"),
                        action = if (expanded) "Afficher moins" else "Tout afficher", actionTag = "artist-show-all", onAction = showAll)
                }
                itemsIndexed(popular, key = { i, t -> "${t.id}:$i" }) { index, track ->
                    Box(if (Motion.reduced) Modifier else Modifier.animateItem()) { row(track, index) }
                }
            }
            if (!wide && latest != null) item(key = "artist-latest") {
                Column(Modifier.fillMaxWidth()) {
                    PageSectionTitle("Dernière sortie", margin)
                    LatestReleaseCard(latest, Modifier.padding(horizontal = margin))
                }
            }
            shelves.forEach { shelf ->
                item(key = shelf.tag) {
                    ReleaseShelf(shelf.title, shelf.albums, margin, cell, shelf.tag) { ArtistDiscography.caption(it, shelf.singles) }
                }
            }
            if (data.related.isNotEmpty()) item(key = "artist-similar") { SimilarArtists(data.related, margin) }
            item(key = "artist-about") { ArtistAbout(data, artwork.signature, margin) }
            item(key = "artist-footer") { ArtistPageFooter(data.artist, margin, onRefresh) }
        }
    }
}

/**
 * Loading state that mirrors the real layout instead of a spinner, so the page
 * does not flash from an empty centre to a full hero.
 */
@Composable
private fun ArtistSkeleton(theme: com.ketvoice.app.theme.KetTheme) {
    val pulse = rememberInfiniteTransition(label = "artistSkeleton")
    val alpha by if (Motion.reduced) {
        remember { androidx.compose.runtime.mutableFloatStateOf(0.5f) }
    } else {
        pulse.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.62f,
            animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
            label = "artistSkeletonAlpha"
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = ArtistLayout.wide(maxWidth.value)
        val window = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.toFloat()
        val margin = ArtistLayout.margin(wide).dp
        val heroHeight = ArtistLayout.heroHeight(maxWidth.value, window, wide).dp
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(heroHeight)
                .background(theme.card.copy(alpha = alpha)))
            Box(Modifier.padding(start = margin, top = 26.dp).width(190.dp).height(20.dp)
                .clip(RoundedCornerShape(9.dp)).background(theme.card.copy(alpha = alpha)))
            repeat(5) { index ->
                Row(Modifier.fillMaxWidth().padding(horizontal = margin, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(theme.card.copy(alpha = alpha)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth(if (index % 2 == 0) 0.72f else 0.54f).height(13.dp)
                            .clip(RoundedCornerShape(6.dp)).background(theme.card.copy(alpha = alpha)))
                        Spacer(Modifier.height(7.dp))
                        Box(Modifier.fillMaxWidth(0.34f).height(10.dp)
                            .clip(RoundedCornerShape(5.dp)).background(theme.card.copy(alpha = alpha * 0.8f)))
                    }
                }
            }
        }
    }
}
