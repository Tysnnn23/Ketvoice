package com.ketvoice.app.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Track
import com.ketvoice.app.glass.LocalBackdropLayer
import com.ketvoice.app.glass.rememberGlassBackdrop
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.quest.QuestLayout
import com.kyant.backdrop.backdrops.layerBackdrop
import com.ketvoice.app.ui.motion.Motion

/** Original mobile controls, arranged for the Quest's landscape window.
 * Lyrics/queue never replace or hide the artwork and transport column. */
@Composable
internal fun QuestLandscapePlayer(
    state: PlayerUiState, track: Track, cover: String?, canvas: String?, ambience: CoverAmbience,
    page: PlayerPage, onPage: (PlayerPage) -> Unit, onClose: () -> Unit,
    onTrackMenu: () -> Unit, onSources: () -> Unit, onOutput: () -> Unit
) {
    val backdrop = rememberGlassBackdrop(Color.Black)
    val companion = page != PlayerPage.ARTWORK
    val duration = Motion.duration(280)
    CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
        Box(Modifier.fillMaxSize().background(Color.Black).layerBackdrop(backdrop)) {
            // Bounded gradient rather than a second full-window blurred video.
            PlayerSceneBackground(track, canvas, state.isPlaying, cover, ambience, simple = true)
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallCircleButton(onClose, size = 36.dp, transparent = true) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Fermer le lecteur", tint = it, modifier = Modifier.size(26.dp))
                    }
                    Text("Lecture en cours", color = Color.White.copy(alpha = .80f), fontSize = 14.sp,
                        modifier = Modifier.padding(start = 8.dp))
                }
                Row(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    BoxWithConstraints(Modifier.weight(QuestLayout.CONTROL_PANE_FRACTION).fillMaxHeight()) {
                        val targetSize = minOf(maxWidth, 480.dp,
                             if (companion) (maxHeight - (if (Motion.reduced) 326.dp else 296.dp)).coerceAtLeast(96.dp) else maxHeight)
                        val artworkSize by animateDpAsState(targetSize, tween(duration), label = "quest-landscape-artwork")
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.Start,
                            verticalArrangement = if (companion) Arrangement.Top else Arrangement.Center) {
                            ArtworkCanvas(track, if (panoramicCanvasActive(canvas)) null else canvas, state.isPlaying,
                                Modifier.size(artworkSize).clip(RoundedCornerShape(24.dp))
                                    .semantics { contentDescription = AppText.t("Pochette") }
                                    .onGloballyPositioned { AppUi.playerCoverBounds.value = it.boundsInRoot() }, depth = true)
                            AnimatedVisibility(companion, enter = fadeIn(tween(duration)), exit = fadeOut(tween(duration))) {
                                Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                                    QuestMobileControls(state, track, cover, page, onPage, onTrackMenu, onOutput)
                                }
                            }
                        }
                    }
                    AnimatedContent(page, transitionSpec = {
                        fadeIn(tween(duration)).togetherWith(fadeOut(tween(duration)))
                    }, label = "quest-landscape-pane", modifier = Modifier.weight(1f - QuestLayout.CONTROL_PANE_FRACTION).fillMaxHeight()) { shown ->
                        if (shown == PlayerPage.ARTWORK) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.Center) {
                                QuestMobileControls(state, track, cover, shown, onPage, onTrackMenu, onOutput)
                            }
                        } else Column(Modifier.fillMaxSize()) {
                            if (shown == PlayerPage.LYRICS) Text("Paroles", color = Color.White.copy(alpha = .7f),
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
                            if (shown == PlayerPage.QUEUE) QueuePane(state, Modifier.weight(1f).fillMaxWidth())
                            else LyricsPane(track, expanded = false, onSource = onSources,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                fontScale = QuestLayout.lyricsScale(AppSettings.questLyricsSize.value))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestMobileControls(
    state: PlayerUiState, track: Track, cover: String?, page: PlayerPage,
    onPage: (PlayerPage) -> Unit, onTrackMenu: () -> Unit, onOutput: () -> Unit
) {
    PlayerTitle(track, false, cover, onTrackMenu, Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))
    ScrubBar(state.durationMs, Modifier.fillMaxWidth(), onSeek = Player::seekTo)
    PlayerTransport(state.playWhenReady, Modifier.fillMaxWidth().height(88.dp), Player::previous, Player::toggle, Player::next)
    PlayerVolume(Modifier.fillMaxWidth(), compact = true)
    Spacer(Modifier.height(8.dp))
    PlayerBottomBar(page == PlayerPage.LYRICS,
        onLyrics = { onPage(if (page == PlayerPage.LYRICS) PlayerPage.ARTWORK else PlayerPage.LYRICS) },
        onOutput = onOutput, onProfile = { AppUi.showAccount.value = true },
        onQueue = { onPage(if (page == PlayerPage.QUEUE) PlayerPage.ARTWORK else PlayerPage.QUEUE) })
    if (state.buffering) Text("Chargement…", color = Color.White.copy(alpha = .8f), fontSize = 13.sp)
}
