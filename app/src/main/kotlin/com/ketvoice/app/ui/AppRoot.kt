package com.ketvoice.app.ui

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.glass.LocalBackdropLayer
import com.ketvoice.app.glass.rememberGlassBackdrop
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.account.AccountDialog
import com.ketvoice.app.ui.chrome.FloatingChrome
import com.ketvoice.app.ui.chrome.GlassBottomBar
import com.ketvoice.app.ui.chrome.MiniPlayerPill
import com.ketvoice.app.ui.chrome.TabletNavigationRail
import com.ketvoice.app.ui.adaptive.AdaptiveLayout
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.explorer.ExplorerScreen
import com.ketvoice.app.ui.home.HomeScreen
import com.ketvoice.app.ui.library.LibraryScreen
import com.ketvoice.app.ui.overlay.SearchScreen
import com.ketvoice.app.ui.player.FullPlayerScreen
import com.ketvoice.app.ui.settings.SettingsScreen
import com.ketvoice.app.ui.sub.QueueScreen
import com.ketvoice.app.ui.sub.SubScreensRouter
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.ketvoice.app.ui.motion.Motion

/**
 * Racine de Ketvoice :
 *   fond capturé (Backdrop SDK) → contenu animé (fondu + glissement)
 *   → chrome en vrai verre liquide → lecteur plein écran (slide) → dialogs.
 */
@Composable
fun AppRoot() {
    com.ketvoice.app.ui.motion.ObserveSystemMotion()
    com.ketvoice.app.ui.motion.ObserveDisplayRefresh()
    // Liquid 27 highlights follow device tilt; never on Quest or with reduced motion.
    com.ketvoice.app.glass.ObserveGlassTilt(
        AppSettings.glassStyle.value == com.ketvoice.app.glass.GlassStyles.LIQUID_27 &&
            AppSettings.liquidGlass.value && !Motion.reduced && !com.ketvoice.app.BuildConfig.IS_QUEST)
    val themeMode = AppSettings.themeMode.value
    val useDark = if (themeMode == 1) false else if (themeMode == 2) true else null
    val view = androidx.compose.ui.platform.LocalView.current
    val effectiveDark = useDark ?: isSystemInDarkTheme()
    // Le lecteur / la file d'attente ont toujours un fond noir : icônes système claires.
    val darkSurface = AppUi.showPlayer.value || AppUi.overlay.value == AppUi.QUEUE

    androidx.compose.runtime.SideEffect {
        val controller = androidx.core.view.WindowCompat.getInsetsController(
            (view.context as? android.app.Activity)?.window ?: return@SideEffect, view
        )
        controller.isAppearanceLightStatusBars = !(effectiveDark || darkSurface)
        controller.isAppearanceLightNavigationBars = !(effectiveDark || darkSurface)
    }

    // Dynamic accent from the currently-playing artwork (opt-in via Album colours).
    // Computed above the theme so the whole UI breathes with the music.
    val playingForAccent by Player.state.collectAsState()
    val dynamicAccent = if (com.ketvoice.app.data.AppSettings.albumColors.value) {
        val t = playingForAccent.current
        val url = t?.let { ArtworkRepo.coverForTrack(it) }
        com.ketvoice.app.theme.rememberCoverAccent(url)?.first
    } else null

    // The playing cover lightly tints the optical glass (independent of the accent option).
    com.ketvoice.app.glass.ObserveCoverTint(playingForAccent.current?.let { ArtworkRepo.coverForTrack(it) })

    KetvoiceTheme(useDark = useDark, dynamicAccent = dynamicAccent) {
        com.ketvoice.app.ui.player.PlaybackFeatureDialogs()
        val th = LocalTheme.current
        val backdrop = rememberGlassBackdrop(th.background)
        val playerState by Player.state.collectAsState()
        val artwork by ArtworkRepo.covers.collectAsState()
        val backProgress = remember { androidx.compose.animation.core.Animatable(0f) }
        val backEdgeRight = remember { androidx.compose.runtime.mutableStateOf(false) }
        val backScope = androidx.compose.runtime.rememberCoroutineScope()

        // Discord live presence: only while a linked account explicitly opted in.
        val discord by com.ketvoice.app.discord.Discord.state.collectAsState()
        LaunchedEffect(
            playerState.current?.id, playerState.isPlaying, playerState.playWhenReady,
            discord.linked, discord.presenceOnly, discord.ready,
            com.ketvoice.app.data.AppSettings.discordPresence.value
        ) {
            val track = playerState.current
            val enabled = com.ketvoice.app.data.AppSettings.discordPresence.value
            val canPublish = discord.linked || discord.presenceOnly
            if (!canPublish || !enabled || track == null || !playerState.isPlaying) {
                com.ketvoice.app.discord.Discord.clearPresence()
                return@LaunchedEffect
            }
            com.ketvoice.app.discord.DiscordPresence.fromTrack(
                title = track.title,
                artist = track.artist,
                coverUrl = artwork["t:${track.id}"] ?: track.coverUrl,
                playing = true,
                positionMs = playerState.positionMs,
                nowMs = System.currentTimeMillis(),
                listenUrl = com.ketvoice.app.util.ShareLinks.trackUrl(track.id, track.title, track.artist)
                    .takeUnless { track.id.startsWith("local:") }
            )?.let(com.ketvoice.app.discord.Discord::setPresence)
        }
        // Un clavier ouvert masque le chrome flottant : sinon le mini-lecteur
        // et la barre d'onglets se dessinent au-dessus des résultats.
        val density = LocalDensity.current
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        val context = androidx.compose.ui.platform.LocalContext.current

        BoxWithConstraints(Modifier.fillMaxSize()) {
        val quest = com.ketvoice.app.BuildConfig.IS_QUEST
        val questLayout = com.ketvoice.app.ui.quest.QuestLayout(maxWidth.value, maxHeight.value)
        val layout = AdaptiveLayout(if (quest) questLayout.contentWidthDp else maxWidth.value,
            maxHeight.value, preferPhone = quest)
        val queueOverlay = AppUi.overlay.value == AppUi.QUEUE
        val pageMaxWidth = if (quest) when (AppUi.overlay.value) {
            AppUi.SETTINGS, AppUi.SOURCES -> minOf(maxWidth, 840.dp)
            else -> questLayout.contentWidthDp.dp
        } else when (AppUi.overlay.value) {
            AppUi.QUEUE -> maxWidth
            AppUi.SETTINGS, AppUi.SOURCES -> 840.dp
            AppUi.SEARCH, AppUi.CATALOG, AppUi.DOWNLOADS -> 1060.dp
            else -> 1280.dp
        }
        CompositionLocalProvider(LocalBackdropLayer provides backdrop, LocalAdaptiveLayout provides layout) {
            Box(Modifier.fillMaxSize()) {
                // ---- Contenu principal : capturé par le backdrop, animé à chaque page ----
                com.ketvoice.app.glass.BackdropCapture(
                    backdrop = backdrop,
                    Modifier
                        .fillMaxSize()
                        .background(th.background)
                        .then(if (AppUi.showPlayer.value || AppUi.showAccount.value || AppUi.showPublisher.value ||
                            AppUi.trackMenuTrack.value != null || AppUi.playlistMenu.value != null ||
                            AppUi.addToPlaylist.value != null) Modifier.clearAndSetSemantics { } else Modifier)
                ) {
                    Box(Modifier.fillMaxSize().padding(start = if (queueOverlay) 0.dp else layout.railWidthDp.dp), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = pageMaxWidth).fillMaxSize()
                        .padding(horizontal = if (layout.hasRail && !queueOverlay) 16.dp else 0.dp)
                        .graphicsLayer {
                            val p = backProgress.value
                            if (p > 0f) {
                                val transform = predictiveBackPage(p, backEdgeRight.value, 28.dp.toPx())
                                scaleX = transform.scale; scaleY = transform.scale
                                translationX = transform.translationX
                                shape = RoundedCornerShape((24.dp.toPx() * p).coerceAtLeast(0f))
                                clip = true
                            }
                        }) {
                    val liquid27 = AppSettings.glassStyle.value == com.ketvoice.app.glass.GlassStyles.LIQUID_27 &&
                        AppSettings.liquidGlass.value
                    NavigationPresentation(AppUi.tab.value to AppUi.snapshot()) { tab, entry ->
                        val overlay = entry.overlay
                            if (overlay == AppUi.NONE) {
                                when (tab) {
                                    AppUi.HOME -> HomeScreen()
                                    AppUi.EXPLORER -> ExplorerScreen()
                                    else -> LibraryScreen()
                                }
                            } else {
                                when (overlay) {
                                    AppUi.SEARCH -> SearchScreen()
                                    AppUi.SETTINGS -> SettingsScreen()
                                    AppUi.QUEUE -> QueueScreen()
                                    AppUi.CATALOG -> com.ketvoice.app.ui.sub.CatalogScreen(entry.catalogRequest)
                                    AppUi.ARTIST -> com.ketvoice.app.ui.sub.ArtistScreen(entry.artistRequest)
                                    AppUi.PLAYLIST -> com.ketvoice.app.ui.library.PlaylistScreen()
                                    AppUi.SOURCES -> com.ketvoice.app.ui.sub.SourcesScreen()
                                    else -> SubScreensRouter()
                                }
                            }
                    }
                    if (liquid27) Liquid27ScrollEdges(Modifier.matchParentSize(),
                        bottom = AppUi.overlay.value != AppUi.SETTINGS && AppUi.overlay.value != AppUi.QUEUE)
                    }
                    }
                }

                // ---- Titre compact : quand la page d'un onglet défile, son grand titre
                //      se réduit en une capsule de verre centrée sous la barre d'état ----
                CompactTabTitle(
                    title = when (AppUi.tab.value) {
                        AppUi.HOME -> "Écouter maintenant"
                        AppUi.EXPLORER -> "Explorer"
                        else -> "Bibliothèque"
                    },
                    visible = AppUi.overlay.value == AppUi.NONE && AppUi.bottomCollapsed.value &&
                        !AppUi.showPlayer.value && !imeVisible,
                    modifier = Modifier.align(Alignment.TopCenter).padding(start = layout.railWidthDp.dp).zIndex(3f))

                if (layout.hasRail && AppUi.overlay.value != AppUi.QUEUE) {
                    TabletNavigationRail(Modifier.width(layout.railWidthDp.dp).align(Alignment.CenterStart).zIndex(3f))
                }

                // ---- Chrome flottant en verre ----
                // Masqué dès qu'une feuille inférieure occupe le bas de l'écran :
                // le mini-lecteur et la navigation ne doivent jamais recouvrir
                // les options d'un menu ouvert.
                val chromeVisible = AppUi.overlay.value != AppUi.SETTINGS &&
                    AppUi.overlay.value != AppUi.QUEUE &&
                    !AppUi.showPlayer.value &&
                    !imeVisible &&
                    AppUi.trackMenuTrack.value == null &&
                    AppUi.addToPlaylist.value == null &&
                    AppUi.playlistMenu.value == null &&
                    !AppUi.showCreatePlaylist.value &&
                    AppUi.playlistResolution.value == null
                AnimatedVisibility(
                    visible = chromeVisible,
                    enter = fadeIn(tween(Motion.duration())) + slideInVertically(tween(Motion.duration())) { it / 3 },
                    exit = fadeOut(tween(Motion.duration())) + slideOutVertically(tween(Motion.duration())) { it / 3 },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = layout.railWidthDp.dp)
                        .fillMaxWidth()
                        .zIndex(3f)
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(Modifier.widthIn(max = if (layout.hasRail) 640.dp else 560.dp).fillMaxWidth()
                        .imePadding().navigationBarsPadding().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (layout.hasRail) {
                            MiniPlayerPill(onOpen = { AppUi.showPlayer.value = true })
                        } else {
                            // Chrome complet : au scroll, le mini-lecteur se condense
                            // et rejoint la rangée de navigation (accueil + recherche
                            // interposés). On remonte → retour à la présentation pleine.
                            FloatingChrome(
                                collapsed = AppUi.bottomCollapsed.value && AppUi.overlay.value == AppUi.NONE,
                                onOpenPlayer = { AppUi.showPlayer.value = true }
                            )
                        }
                    }
                    }
                }

                // ---- Retour système : dépile les overlays, racine sinon ----
                androidx.activity.compose.PredictiveBackHandler(
                    enabled = AppUi.overlay.value != AppUi.NONE &&
                        AppUi.overlay.value != AppUi.QUEUE &&
                        !AppUi.showPlayer.value
                ) { events ->
                    // Predictive back (Android 14+): the page shrinks under the finger and
                    // slides away from the swiped edge, then closes or springs back.
                    try {
                        events.collect { event ->
                            if (!Motion.reduced) {
                                backEdgeRight.value = event.swipeEdge == androidx.activity.BackEventCompat.EDGE_RIGHT
                                backProgress.snapTo(event.progress.coerceIn(0f, 1f))
                            }
                        }
                        AppUi.closeOverlay()
                        backScope.launch { backProgress.animateTo(0f, tween(Motion.duration(220), easing = Motion.EASE_STANDARD)) }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        backScope.launch { backProgress.animateTo(0f, Motion.gentleSpring()) }
                        throw cancelled
                    }
                }

                // ---- Lecteur plein écran : monte depuis le bas, fermeture
                //      progressive interne (drag) puis fondu ----
                com.ketvoice.app.ui.player.PlayerPresentation(
                    visible = AppUi.showPlayer.value,
                    origin = AppUi.miniPlayerBounds.value,
                    modifier = Modifier.fillMaxSize().zIndex(6f)
                ) {
                    Box(Modifier.fillMaxSize()) {
                        FullPlayerScreen(modifier = Modifier.fillMaxSize())
                    }
                }

                // ---- Dialog compte ----
                androidx.compose.material3.SnackbarHost(
                    com.ketvoice.app.ui.common.TrackActionFeedback.snackbar,
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 150.dp).zIndex(12f))
                AnimatedContent(AppUi.trackMenuTrack.value,
                    modifier = Modifier.fillMaxSize().zIndex(7f),
                    transitionSpec = { fadeIn(tween(Motion.duration(240))).togetherWith(fadeOut(tween(Motion.duration(220)))) },
                    label = "global-track-menu") { track ->
                    if (track != null) Box(Modifier.fillMaxSize()) {
                        androidx.activity.compose.BackHandler { AppUi.trackMenuTrack.value = null }
                        com.ketvoice.app.ui.player.PlayerSheetScrim({ AppUi.trackMenuTrack.value = null }) {
                            com.ketvoice.app.ui.player.TrackMenuSheet(track) { AppUi.trackMenuTrack.value = null }
                        }
                    }
                }
                // ---- Menu d'actions de playlist : même couche que le menu morceau,
                //      donc au-dessus du chrome flottant (correctif de recouvrement) ----
                AppUi.playlistMenu.value?.let { request ->
                    val playlist = request.playlist
                    Box(Modifier.fillMaxSize().zIndex(7f)) {
                        androidx.activity.compose.BackHandler { AppUi.playlistMenu.value = null }
                        com.ketvoice.app.ui.player.PlayerSheetScrim({ AppUi.playlistMenu.value = null }) {
                            com.ketvoice.app.ui.library.PlaylistMenuSheet(
                                playlist = playlist,
                                onClose = { AppUi.playlistMenu.value = null },
                                onDownload = {
                                    val pending = playlist.entries.mapNotNull { it.track }
                                        .filter { Downloader.itemFor(it.id) == null }
                                    pending.forEach { Downloader.download(it) }
                                    if (pending.isNotEmpty()) android.widget.Toast.makeText(
                                        context,
                                        com.ketvoice.app.data.AppText.t("Téléchargements lancés"),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onPlayNext = {
                                    val playable = playlist.entries.mapNotNull { it.track }
                                    if (playable.isNotEmpty()) Player.enqueue(playable)
                                },
                                onRename = { AppUi.playlistMenu.value = null; request.onRename() },
                                onExportJson = { AppUi.playlistMenu.value = null; request.onExportJson() },
                                onExportM3u = { AppUi.playlistMenu.value = null; request.onExportM3u() },
                                onDelete = { AppUi.playlistMenu.value = null; request.onDelete() }
                            )
                        }
                    }
                }
                // ---- Ajouter à une playlist : feuille verre au-dessus du chrome ----
                AppUi.addToPlaylist.value?.let { track ->
                    Box(Modifier.fillMaxSize().zIndex(7f)) {
                        androidx.activity.compose.BackHandler { AppUi.addToPlaylist.value = null }
                        com.ketvoice.app.ui.player.PlayerSheetScrim({ AppUi.addToPlaylist.value = null }) {
                            com.ketvoice.app.ui.library.AddToPlaylistSheet(track) { AppUi.addToPlaylist.value = null }
                        }
                    }
                }
                if (AppUi.showAccount.value) {
                    AccountDialog(Modifier.zIndex(8f))
                }
                if (AppUi.showPublisher.value) {
                    Box(Modifier.fillMaxSize().zIndex(9f)) {
                        com.ketvoice.app.ui.settings.PublisherScreen(AppUi.publisherTrack.value) {
                            AppUi.showPublisher.value = false
                        }
                    }
                }
            }
        }
        }
    }
}

/**
 * Liquid 27 scroll-edge effect: content softly fades under the status bar and the
 * floating chrome. Drawn inside the captured layer, so the glass refracts the faded
 * content exactly like the reference material. Purely visual and never focusable.
 */
@Composable
private fun Liquid27ScrollEdges(modifier: Modifier, bottom: Boolean) {
    val background = LocalTheme.current.background
    Box(modifier) {
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(background.copy(alpha = .82f), background.copy(alpha = 0f))))
            .statusBarsPadding().height(28.dp))
        if (bottom) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(background.copy(alpha = 0f), background.copy(alpha = .62f))))
            .navigationBarsPadding().height(132.dp))
    }
}

internal data class BackPageTransform(val scale: Float, val translationX: Float)

/** Page transform while a system back gesture is held: at most 8 % smaller, sliding away from the swiped edge. */
internal fun predictiveBackPage(progress: Float, fromRightEdge: Boolean, travelPx: Float): BackPageTransform {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    return BackPageTransform(1f - .08f * p, (if (fromRightEdge) -1f else 1f) * travelPx * p)
}

/**
 * Compact title of a scrolled tab page. Real glass (it sits outside the captured
 * content layer), announced as a heading, and it never takes touches from the list.
 */
@Composable
private fun CompactTabTitle(title: String, visible: Boolean, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(Motion.duration(200))) + slideInVertically(tween(Motion.duration(260), easing = Motion.EASE_DECELERATE)) { -it / 2 },
        exit = fadeOut(tween(Motion.duration(160))) + slideOutVertically(tween(Motion.duration(200), easing = Motion.EASE_ACCELERATE)) { -it / 2 },
        modifier = modifier
    ) {
        com.ketvoice.app.glass.GlassPanel(
            Modifier.statusBarsPadding().padding(top = 6.dp).testTag("compact-tab-title"),
            RoundedCornerShape(50),
            com.ketvoice.app.glass.GlassParams.navigation(dark = theme.isDark),
            enabled = AppSettings.liquidGlass.value
        ) {
            com.ketvoice.app.ui.common.Text(title, color = theme.onBackground, fontSize = 15.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
                    .semantics { heading() })
        }
    }
}
