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
import com.ketvoice.app.glass.LocalBackdropLayer
import com.ketvoice.app.glass.rememberGlassBackdrop
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.account.AccountDialog
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

/**
 * Racine de Ketvoice :
 *   fond capturé (Backdrop SDK) → contenu animé (fondu + glissement)
 *   → chrome en vrai verre liquide → lecteur plein écran (slide) → dialogs.
 */
@Composable
fun AppRoot() {
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

    KetvoiceTheme(useDark = useDark) {
        val th = LocalTheme.current
        val backdrop = rememberGlassBackdrop(th.background)
        val playerState by Player.state.collectAsState()
        val artwork by ArtworkRepo.covers.collectAsState()
        // Un clavier ouvert masque le chrome flottant : sinon le mini-lecteur
        // et la barre d'onglets se dessinent au-dessus des résultats.
        val density = LocalDensity.current
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        var wasPlayerVisible by remember { mutableStateOf(AppUi.showPlayer.value) }

        // Ouvre/ferme le lecteur : une copie de la pochette vole entre la
        // position du lecteur et celle du mini-lecteur, en fondu.
        LaunchedEffect(AppUi.showPlayer.value, playerState.current?.id) {
            val visible = AppUi.showPlayer.value
            if (visible != wasPlayerVisible) {
                wasPlayerVisible = visible
                if (AppSettings.reduceAnimations.value) {
                    AppUi.coverFlight.value = null
                    return@LaunchedEffect
                }
                val current = playerState.current
                val mini = AppUi.miniCoverBounds.value
                val full = if (visible) {
                    // On first open, wait for the artwork's actual layout.
                    snapshotFlow { AppUi.playerCoverBounds.value }.filterNotNull().first()
                } else AppUi.playerCoverBounds.value
                if (current != null && mini != null) {
                    if (visible && full != null) {
                        AppUi.coverFlight.value = AppUi.CoverFlight(
                            cover = artwork["t:${current.id}"] ?: current.coverUrl,
                            title = current.title,
                            from = mini,
                            to = full,
                            opening = true
                        )
                    } else if (!visible && full != null) {
                        AppUi.coverFlight.value = AppUi.CoverFlight(
                            cover = artwork["t:${current.id}"] ?: current.coverUrl,
                            title = current.title,
                            from = full,
                            to = mini,
                            opening = false
                        )
                    }
                }
            }
        }

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
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(th.background)
                        .layerBackdrop(backdrop)
                ) {
                    Box(Modifier.fillMaxSize().padding(start = if (queueOverlay) 0.dp else layout.railWidthDp.dp), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = pageMaxWidth).fillMaxSize()
                        .padding(horizontal = if (layout.hasRail && !queueOverlay) 16.dp else 0.dp)) {
                    AnimatedContent(
                        targetState = AppUi.tab.value to AppUi.overlay.value,
                        contentAlignment = Alignment.Center,
                        transitionSpec = {
                            (fadeIn(tween(240)) + slideInVertically(
                                tween(300, easing = FastOutSlowInEasing)
                            ) { fullHeight -> fullHeight / 14 }).togetherWith(
                                fadeOut(tween(160))
                            )
                        },
                        label = "nav"
                    ) { (tab, overlay) ->
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
                                AppUi.CATALOG -> com.ketvoice.app.ui.sub.CatalogScreen()
                                AppUi.ARTIST -> com.ketvoice.app.ui.sub.ArtistScreen()
                                AppUi.SOURCES -> com.ketvoice.app.ui.sub.SourcesScreen()
                                else -> SubScreensRouter()
                            }
                        }
                    }
                    }
                    }
                }

                if (layout.hasRail && AppUi.overlay.value != AppUi.QUEUE) {
                    TabletNavigationRail(Modifier.width(layout.railWidthDp.dp).align(Alignment.CenterStart).zIndex(3f))
                }

                // ---- Chrome flottant en verre ----
                AnimatedVisibility(
                    visible = AppUi.overlay.value != AppUi.SETTINGS &&
                        AppUi.overlay.value != AppUi.QUEUE &&
                        !AppUi.showPlayer.value &&
                        !imeVisible,
                    enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 3 },
                    exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { it / 3 },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = layout.railWidthDp.dp)
                        .fillMaxWidth()
                        .zIndex(3f)
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(Modifier.widthIn(max = if (layout.hasRail) 640.dp else 560.dp).fillMaxWidth()
                        .imePadding().navigationBarsPadding().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        MiniPlayerPill(onOpen = { AppUi.showPlayer.value = true })
                        if (!layout.hasRail) {
                            Spacer(Modifier.height(10.dp))
                            GlassBottomBar(collapsed = AppUi.bottomCollapsed.value && AppUi.overlay.value == AppUi.NONE)
                        }
                    }
                    }
                }

                // ---- Retour système : ferme d'abord tout overlay ----
                androidx.activity.compose.BackHandler(
                    enabled = AppUi.overlay.value != AppUi.NONE &&
                        AppUi.overlay.value != AppUi.QUEUE &&
                        !AppUi.showPlayer.value
                ) {
                    AppUi.overlay.value = AppUi.NONE
                }

                // ---- Lecteur plein écran : glisse depuis le bas ----
                AnimatedVisibility(
                    visible = AppUi.showPlayer.value,
                    enter = fadeIn(tween(if (AppSettings.reduceAnimations.value) 0 else 300)),
                    exit = fadeOut(tween(if (AppSettings.reduceAnimations.value) 0 else 260)),
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(6f)
                ) {
                    FullPlayerScreen(modifier = Modifier.fillMaxSize())
                }

                // ---- Pochette qui vole entre le lecteur et le mini-lecteur ----
                CoverFlightOverlay()

                // ---- Dialog compte ----
                AppUi.trackMenuTrack.value?.let { track ->
                    Box(Modifier.fillMaxSize().zIndex(7f)) {
                        androidx.activity.compose.BackHandler { AppUi.trackMenuTrack.value = null }
                        com.ketvoice.app.ui.player.PlayerSheetScrim({ AppUi.trackMenuTrack.value = null }) {
                            com.ketvoice.app.ui.player.TrackMenuSheet(track) { AppUi.trackMenuTrack.value = null }
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

@Composable
private fun CoverFlightOverlay() {
    val flight = AppUi.coverFlight.value ?: return
    val density = LocalDensity.current
    val progress = remember(flight.token) { Animatable(0f) }
    LaunchedEffect(flight.token) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(if (flight.opening) 440 else 380, easing = FastOutSlowInEasing)
        )
        AppUi.coverFlight.value = null
    }
    val t = progress.value
    val eased = t * t * (3f - 2f * t)
    fun lerp(a: Float, b: Float): Float = a + (b - a) * eased
    val left = lerp(flight.from.left, flight.to.left)
    val top = lerp(flight.from.top, flight.to.top)
    val width = lerp(flight.from.width, flight.to.width).coerceAtLeast(1f)
    val height = lerp(flight.from.height, flight.to.height).coerceAtLeast(1f)
    val fade = if (t < .68f) 1f else (1f - (t - .68f) / .32f).coerceIn(0f, 1f)
    Box(
        Modifier
            .offset(x = with(density) { left.toDp() }, y = with(density) { top.toDp() })
            .size(width = with(density) { width.toDp() }, height = with(density) { height.toDp() })
            .graphicsLayer { alpha = fade }
            .clip(RoundedCornerShape(12.dp))
            .zIndex(6.5f)
    ) {
        val cover = flight.cover
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(Color(0xFF5E5CE6), Color(0xFFBF5AF2), Color(0xFF2C2C2E)))
                )
            )
        }
    }
}
