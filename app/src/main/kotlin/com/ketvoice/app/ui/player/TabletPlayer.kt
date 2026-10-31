package com.ketvoice.app.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.AppText
import com.ketvoice.app.glass.LocalBackdropLayer
import com.ketvoice.app.glass.BackdropCapture
import com.ketvoice.app.glass.rememberGlassBackdrop
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.AdaptiveLayout
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.motion.Motion
import androidx.compose.animation.core.tween

@Composable
internal fun TabletPlayer(
    state: PlayerUiState, track: Track, cover: String?, canvas: String?, ambience: CoverAmbience,
    page: PlayerPage, onPage: (PlayerPage) -> Unit, onClose: () -> Unit,
    onTrackMenu: () -> Unit, onSources: () -> Unit, onOutput: () -> Unit,
    onCanvas: (() -> Unit)? = null
) {
    val backdrop = rememberGlassBackdrop(Color.Black)
    CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val layout = AdaptiveLayout(maxWidth.value, maxHeight.value)

            BackdropCapture(backdrop, Modifier.matchParentSize()) {
                PlayerSceneBackground(track, canvas, state.isPlaying, cover, ambience, simple = true)
            }
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = if (maxWidth >= 1000.dp) 40.dp else 24.dp)) {
                Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallCircleButton(onClose, size = 44.dp) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Fermer le lecteur", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    Text("Lecture en cours", color = Color.White.copy(alpha = .7f), fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp))
                }
                BoxWithConstraints(Modifier.weight(1f).widthIn(max = 1200.dp).fillMaxWidth()
                    .align(Alignment.CenterHorizontally).padding(bottom = 20.dp)) {
                    val bodyHeight = maxHeight
                    if (layout.playerPanes) {
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                            NowPlayingColumn(state, track, cover, canvas, page, bodyHeight,
                                onPage, onTrackMenu, onOutput, Modifier.weight(.46f).fillMaxHeight(), onCanvas)
                            PlayerCompanionPane(state, track, page, onPage, onSources,
                                Modifier.weight(.54f).fillMaxHeight())
                        }
                    } else if (page == PlayerPage.ARTWORK) {
                        NowPlayingColumn(state, track, cover, canvas, page, bodyHeight,
                            onPage, onTrackMenu, onOutput,
                            Modifier.widthIn(max = 500.dp).fillMaxSize().align(Alignment.Center), onCanvas)
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            PlayerTitle(track, true, cover, onTrackMenu, Modifier.fillMaxWidth(),
                                onCover = { onPage(PlayerPage.ARTWORK) })
                            Spacer(Modifier.height(18.dp))
                            PlayerCompanionPane(state, track, page, onPage, onSources, Modifier.weight(1f).fillMaxWidth())
                            ScrubBar(state.durationMs, Modifier.fillMaxWidth().padding(top = 12.dp), onSeek = Player::seekTo)
                            PlayerTransport(state.playWhenReady, Modifier.fillMaxWidth().height(88.dp), Player::previous, Player::toggle, Player::next)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingColumn(
    state: PlayerUiState, track: Track, cover: String?, canvas: String?, page: PlayerPage,
    availableHeight: Dp, onPage: (PlayerPage) -> Unit, onTrackMenu: () -> Unit,
    onOutput: () -> Unit, modifier: Modifier, onCanvas: (() -> Unit)?
) {
    BoxWithConstraints(modifier) {
        val artworkSize = minOf(maxWidth, 420.dp, (availableHeight - 340.dp).coerceAtLeast(100.dp))
        val topSpace = ((availableHeight - artworkSize - 340.dp) / 2).coerceAtLeast(0.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start) {
            Spacer(Modifier.height(topSpace))
            ArtworkCanvas(track, if (panoramicCanvasActive(canvas)) null else canvas, state.isPlaying,
                Modifier.size(artworkSize).clip(RoundedCornerShape(24.dp))
                    .then(if (onCanvas != null) Modifier.clickable(onClick = onCanvas)
                        .semantics { contentDescription = AppText.t("Voir le Canvas en plein écran") } else Modifier)
                     .onGloballyPositioned { AppUi.playerCoverBounds.value = it.boundsInRoot() }, depth = true)
            Spacer(Modifier.height(24.dp))
            PlayerTitle(track, false, cover, onTrackMenu, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            ScrubBar(state.durationMs, Modifier.fillMaxWidth(), onSeek = Player::seekTo)
            PlayerTransport(state.playWhenReady, Modifier.fillMaxWidth().height(100.dp), Player::previous, Player::toggle, Player::next)
            PlayerVolume(Modifier.fillMaxWidth(), compact = true)
            Spacer(Modifier.height(8.dp))
            PlayerBottomBar(page == PlayerPage.LYRICS,
                onLyrics = { onPage(PlayerPage.LYRICS) }, onOutput = onOutput,
                onProfile = { AppUi.showAccount.value = true }, onQueue = { onPage(PlayerPage.QUEUE) })
            if (state.buffering) Text("Chargement…", color = Color.White.copy(alpha = .6f), fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PlayerCompanionPane(
    state: PlayerUiState, track: Track, page: PlayerPage, onPage: (PlayerPage) -> Unit,
    onSources: () -> Unit, modifier: Modifier
) {
    Column(modifier.clip(RoundedCornerShape(28.dp)).background(Color.Black.copy(alpha = .22f)).padding(20.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .06f))) {
            listOf(PlayerPage.LYRICS to "Paroles", PlayerPage.QUEUE to "File d'attente").forEach { (target, label) ->
                val selected = if (target == PlayerPage.LYRICS) page != PlayerPage.QUEUE else page == target
                Box(Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                    .background(if (selected) Color.White.copy(alpha = .13f) else Color.Transparent)
                    .clickable { onPage(target) }.padding(horizontal = 8.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = Color.White.copy(alpha = if (selected) 1f else .55f),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        AnimatedContent(page == PlayerPage.QUEUE, transitionSpec = { fadeIn(tween(Motion.duration())).togetherWith(fadeOut(tween(Motion.duration()))) },
            label = "tablet-companion", modifier = Modifier.weight(1f).fillMaxWidth()) { queue ->
            if (queue) QueuePane(state, Modifier.fillMaxSize())
            else LyricsPane(track, expanded = false, onSource = onSources, modifier = Modifier.fillMaxSize())
        }
    }
}
