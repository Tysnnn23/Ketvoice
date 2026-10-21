package com.ketvoice.app.ui

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.glass.LocalBackdropLayer
import com.ketvoice.app.glass.rememberGlassBackdrop
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.account.AccountDialog
import com.ketvoice.app.ui.chrome.GlassBottomBar
import com.ketvoice.app.ui.chrome.MiniPlayerPill
import com.ketvoice.app.ui.explorer.ExplorerScreen
import com.ketvoice.app.ui.home.HomeScreen
import com.ketvoice.app.ui.library.LibraryScreen
import com.ketvoice.app.ui.overlay.SearchScreen
import com.ketvoice.app.ui.player.FullPlayerScreen
import com.ketvoice.app.ui.settings.SettingsScreen
import com.ketvoice.app.ui.sub.QueueScreen
import com.ketvoice.app.ui.sub.SubScreensRouter
import com.kyant.backdrop.backdrops.layerBackdrop

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

        CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
            Box(Modifier.fillMaxSize()) {
                // ---- Contenu principal : capturé par le backdrop, animé à chaque page ----
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(th.background)
                        .layerBackdrop(backdrop)
                ) {
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

                // ---- Chrome flottant en verre ----
                AnimatedVisibility(
                    visible = AppUi.overlay.value != AppUi.SETTINGS &&
                        AppUi.overlay.value != AppUi.QUEUE &&
                        !AppUi.showPlayer.value,
                    enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 3 },
                    exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { it / 3 },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(3f)
                ) {
                    Column(Modifier.imePadding().navigationBarsPadding().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        MiniPlayerPill(onOpen = { AppUi.showPlayer.value = true })
                        Spacer(Modifier.height(10.dp))
                        GlassBottomBar(collapsed = AppUi.bottomCollapsed.value && AppUi.overlay.value == AppUi.NONE)
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
                    enter = fadeIn(tween(180)) + slideInVertically(
                        spring(dampingRatio = 0.92f, stiffness = 380f)
                    ) { fullHeight -> fullHeight },
                    exit = fadeOut(tween(160)) + slideOutVertically(tween(260)) { fullHeight -> fullHeight },
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(6f)
                ) {
                    FullPlayerScreen(modifier = Modifier.fillMaxSize())
                }

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
            }
        }
    }
}
