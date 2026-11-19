package com.ketvoice.app.ui.tablet

import com.ketvoice.app.ui.player.trackSwipe
import com.ketvoice.app.ui.player.trackSwipeContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LargeScreenNav
import com.ketvoice.app.ui.adaptive.hoverHighlight
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.player.MiniPlayerAnchors

/** How the docked mini-player arranges itself for its width. */
internal object DockedMiniPlayer {
    /** From this width the progress is a scrubber with its times in the middle of the bar; below, a hairline under it. */
    const val SCRUBBER_FROM = 760f
    /** The bar never grows wider than this, centred over the page. */
    const val MAX_WIDTH = 1040f
    const val HEIGHT = 64f

    fun scrubberInside(widthDp: Float): Boolean = widthDp >= SCRUBBER_FROM
}

/**
 * The mini-player of a large window: docked along the bottom of the page (never under the sidebar),
 * a capsule of glass with the cover, the title, the transport, the progress (a real scrubber once the
 * bar is wide enough), the lyrics and the queue. Touched anywhere else, it unfolds into the full
 * player with the same movement as on the phone: [PlayerPresentation] grows from its bounds and the
 * cover, the title, play and next travel from their places here.
 */
@Composable
fun TabletMiniPlayer(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val state = Player.state.collectAsState().value
    val current = state.current ?: return
    val theme = LocalTheme.current
    val art by ArtworkRepo.covers.collectAsState()
    val cover = com.ketvoice.app.data.MediaOverrides.cover(current, art["t:${current.id}"] ?: current.coverUrl)
    val shape = RoundedCornerShape(32.dp)
    // Swiped sideways, the cover and the title change track (7.1), as on the phone.
    val swipe = com.ketvoice.app.ui.player.rememberTrackSwipe()
    BoxWithConstraints(modifier.widthIn(max = DockedMiniPlayer.MAX_WIDTH.dp).fillMaxWidth()) {
        val inside = DockedMiniPlayer.scrubberInside(maxWidth.value)
        GlassPanel(Modifier.fillMaxWidth().height(DockedMiniPlayer.HEIGHT.dp)
            .onGloballyPositioned { AppUi.miniPlayerBounds.value = it.boundsInRoot() }
            .shadow(if (theme.isDark) 2.dp else 6.dp, shape, spotColor = Color.Black.copy(alpha = .16f))
            .testTag("tablet-mini-player"),
            shape, GlassParams.pill(dark = theme.isDark), enabled = AppSettings.liquidGlass.value) {
            Box(Modifier.fillMaxSize().then(if (AppSettings.liquidGlass.value) Modifier else Modifier.background(theme.card, shape))) {
                Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Cover and title: the part that opens the player.
                    Row(Modifier.weight(if (inside) .9f else 1f).fillMaxHeight().clip(RoundedCornerShape(24.dp))
                        .trackSwipe(swipe, threshold = 64.dp, settle = { com.ketvoice.app.ui.player.miniSwipeSpring() })
                        .clickable(role = Role.Button, onClickLabel = AppText.t("Lecture en cours"), onClick = onOpen),
                        verticalAlignment = Alignment.CenterVertically) {
                        val canvases by com.ketvoice.app.data.CanvasRepo.paths.collectAsState()
                        val canvas = canvases[current.id]?.takeIf { !com.ketvoice.app.data.MediaOverrides.canvasHiddenInPlayer(current) }
                            ?.takeIf { AppSettings.animatedArtwork.value && !Motion.reduced && !AppSettings.highPerformanceMode.value }
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                            .onGloballyPositioned { AppUi.miniCoverBounds.value = it.boundsInRoot() }) {
                            if (canvas != null) com.ketvoice.app.ui.player.ArtworkCanvas(current.copy(coverUrl = cover), canvas, state.isPlaying, Modifier.fillMaxSize())
                            else {
                                Cover(current.title, cover, size = 48.dp, radius = 10.dp)
                                com.ketvoice.app.ui.player.MixArtworkOverlay(current)
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)
                            .trackSwipeContent(swipe, travel = .55f)
                            .onGloballyPositioned { MiniPlayerAnchors.title = it.boundsInRoot() }) {
                            Text(current.title, localize = false, color = theme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                com.ketvoice.app.ui.together.KetogetherLivePill(Modifier.padding(end = 5.dp), compact = true, onDark = theme.isDark)
                                Text(current.artist, localize = false, color = theme.secondary, fontSize = 13.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    DockButton("Précédent", 44.dp, "dock-previous", { com.ketvoice.app.ui.player.TransportGlyph("previous", Modifier.size(26.dp), it) }) { Player.previous() }
                    Box(Modifier.onGloballyPositioned { MiniPlayerAnchors.play = it.boundsInRoot() }) {
                        val interactions = remember { MutableInteractionSource() }
                        Box(Modifier.size(48.dp).clip(CircleShape).hoverHighlight(interactions, CircleShape)
                            .clickable(interactions, null, role = Role.Button) { Player.toggle() }.testTag("dock-play"),
                            contentAlignment = Alignment.Center) {
                            com.ketvoice.app.ui.player.MorphingTransportGlyph(state.playWhenReady, Modifier.size(28.dp), theme.onBackground)
                        }
                    }
                    Box(Modifier.onGloballyPositioned { MiniPlayerAnchors.next = it.boundsInRoot() }) {
                        DockButton("Suivant", 44.dp, "dock-next", { com.ketvoice.app.ui.player.TransportGlyph("next", Modifier.size(26.dp), it) }) { Player.next() }
                    }
                    if (inside) DockScrubber(state.durationMs, Modifier.weight(1.1f).padding(horizontal = 14.dp))
                    DockButton("Paroles", 44.dp, "dock-lyrics", { com.ketvoice.app.ui.player.LyricsGlyph(Modifier.size(22.dp), it) }) {
                        AppUi.showLyrics.value = true; onOpen()
                    }
                    DockButton("File d'attente", 44.dp, "dock-queue", { com.ketvoice.app.ui.player.QueueGlyph(Modifier.size(22.dp), it) }) {
                        LargeScreenNav.openQueue.value = true; onOpen()
                    }
                }
                if (!inside) DockProgressLine(Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DockButton(label: String, size: Dp, tag: String, glyph: @Composable (Color) -> Unit, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val interactions = remember { MutableInteractionSource() }
    val description = AppText.t(label)
    Box(Modifier.size(size).clip(CircleShape).hoverHighlight(interactions, CircleShape)
        .clickable(interactions, null, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }.testTag(tag), contentAlignment = Alignment.Center) {
        glyph(theme.onBackground)
    }
}

/**
 * Elapsed time, a bar to touch or drag along, remaining time. Isolated: only it follows the position. The glide is
 * read while the bar is drawn and the two times change once a second (6.7): read in composition, it composed and
 * measured the scrubber again at every frame while a title played.
 */
@Composable
private fun DockScrubber(durationMs: Long, modifier: Modifier) {
    val theme = LocalTheme.current
    val position by Player.positionMs.collectAsState()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = durationMs.coerceAtLeast(1L)
    val fraction = dragging ?: (position.toFloat() / duration).coerceIn(0f, 1f)
    val smooth = animateFloatAsState(fraction, if (Motion.reduced || dragging != null) androidx.compose.animation.core.snap()
        else tween(500, easing = androidx.compose.animation.core.LinearEasing), label = "dock-progress")
    // Whole seconds: each changes once a second, and only then is its text made and composed again.
    val elapsed by remember(duration) { derivedStateOf { (smooth.value * duration).toLong() / 1000 } }
    val remaining by remember(duration) { derivedStateOf { ((1f - smooth.value) * duration).toLong() / 1000 } }
    val accent = theme.accent
    Row(modifier.testTag("dock-scrubber"), verticalAlignment = Alignment.CenterVertically) {
        Text(com.ketvoice.app.ui.player.formatSecs(elapsed * 1000), localize = false, color = theme.secondary, fontSize = 11.sp)
        Box(Modifier.weight(1f).height(28.dp).padding(horizontal = 10.dp)
            .pointerInput(duration) {
                detectTapGestures { offset -> Player.seekTo((offset.x / size.width).coerceIn(0f, 1f).times(duration).toLong()) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { change, _ -> change.consume(); dragging = (change.position.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging?.let { Player.seekTo((it * duration).toLong()) }; dragging = null },
                    onDragCancel = { dragging = null })
            }, contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(if (dragging != null) 6.dp else 4.dp).clip(RoundedCornerShape(3.dp))
                .background(theme.onBackground.copy(alpha = .12f))
                .drawBehind { drawFilled(accent, smooth.value, 3.dp.toPx()) })
        }
        Text("-" + com.ketvoice.app.ui.player.formatSecs(remaining * 1000), localize = false, color = theme.secondary, fontSize = 11.sp)
    }
}

@Composable
private fun DockProgressLine(modifier: Modifier) {
    val position by Player.positionMs.collectAsState()
    val duration = Player.state.collectAsState().value.durationMs.coerceAtLeast(1L)
    // Read while drawing, as the phone's mini-player line (Chrome.kt, 6.6).
    val smooth = animateFloatAsState((position.toFloat() / duration).coerceIn(0f, 1f),
        if (Motion.reduced) androidx.compose.animation.core.snap() else tween(500, easing = androidx.compose.animation.core.LinearEasing), label = "dock-line")
    val accent = LocalTheme.current.accent
    Box(modifier.padding(horizontal = 26.dp).padding(bottom = 4.dp).height(2.5.dp).clip(RoundedCornerShape(2.dp))
        .background(LocalTheme.current.onBackground.copy(alpha = .10f))
        .drawBehind { drawFilled(accent, smooth.value, 0f) })
}

/** The filled part of a progress track, from its start: [fraction] of the width, its own corners rounded by [cornerPx]. */
private fun DrawScope.drawFilled(color: Color, fraction: Float, cornerPx: Float) {
    val filled = Size(size.width * fraction.coerceIn(0f, 1f), size.height)
    if (filled.width <= 0f) return
    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - filled.width else 0f
    if (cornerPx <= 0f) drawRect(color, Offset(left, 0f), filled)
    else drawRoundRect(color, Offset(left, 0f), filled, CornerRadius(cornerPx))
}
