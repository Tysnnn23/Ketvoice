package com.ketvoice.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.desktop.ui.theme.DeskGlyphs
import kotlinx.coroutines.delay

/** « Lecteur plein écran »: F11 or the full player's button opens it (the window goes full screen), Esc leaves. */
object PlayerFullScreen {
    val on = mutableStateOf(false)
    /** Pointer still for this long while music plays: the controls and the cursor step aside. */
    const val IDLE_MS = 3_000L
}

/**
 * Apple Music's full-screen player for macOS: the cover (or its Canvas) giant and edge to edge on the left, the whole
 * height of the screen, feathered into the cover's slowly moving colours; Cascade lyrics large on the right with the
 * voice level; title and transport over the foot of the cover. Still pointer while it plays: the controls and the
 * cursor fade out, and any movement brings them back.
 */
@Composable
fun FullScreenPlayer(onExit: () -> Unit) {
    val player = LocalPlayer.current
    val state by player.state.collectAsState()
    val track = state.current ?: run { LaunchedEffect(Unit) { onExit() }; return }
    val colors = rememberCoverColors(track.coverUrl)
    val ground = lerp(colors?.first ?: Color(0xFF2C2C2E), Color.Black, .5f)
    val canvas = rememberPlayerCanvas(track, state.queue, state.index)
    val actions = LocalTrackActions.current

    var moves by remember { mutableIntStateOf(0) }
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(moves, state.isPlaying) {
        idle = false
        if (state.isPlaying) { delay(PlayerFullScreen.IDLE_MS); idle = true }
    }
    val shown by animateFloatAsState(if (idle) 0f else 1f, if (Motion.reduced) snap() else Motion.Springs.SMOOTH.spec(), label = "fs-controls")

    Box(Modifier.fillMaxSize().background(ground)
        .pointerInput(Unit) {
            awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); moves++ } }
        }
        .pointerHoverIcon(if (idle) HiddenCursor.icon else PointerIcon.Default)
        .testTag("fullscreen-player")) {
        CoverFlowBackground(track.coverUrl, state.isPlaying)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .34f)))
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // As tall as the screen, at most a little over half its width.
            val side = minOf(maxHeight, maxWidth * .54f)
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(side).fillMaxHeight().testTag("fullscreen-art")) {
                    Box(Modifier.fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            // Its right edge dissolves into the colours behind it.
                            drawRect(Brush.horizontalGradient(.84f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                        }) {
                        val cover = rememberCover(track.coverUrl)
                        val poster = rememberCanvasPoster(canvas)
                        when {
                            canvas != null -> {
                                (poster ?: cover)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                CanvasVideo(canvas, state.isPlaying && canvasMoves(), Modifier.fillMaxSize().testTag("fullscreen-canvas"))
                            }
                            cover != null -> Image(cover, null, Modifier.fillMaxSize().testTag("fullscreen-cover"), contentScale = ContentScale.Crop)
                        }
                    }
                    // Title and transport over the foot of the cover, on a shade that keeps white words legible.
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().graphicsLayer { alpha = shown }
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .62f))))) {
                        Column(Modifier.widthIn(max = 560.dp).padding(start = 56.dp, end = 48.dp, top = 96.dp, bottom = 44.dp)) {
                            PlayerControlsBlock(track, compact = false)
                        }
                    }
                }
                Row(Modifier.weight(1f).fillMaxHeight().padding(start = 40.dp, end = 32.dp, top = 84.dp, bottom = 56.dp)) {
                    CascadeLyrics(track, LyricsLook.Full, Modifier.weight(1f).fillMaxHeight())
                    Box(Modifier.fillMaxHeight().padding(start = 12.dp).graphicsLayer { alpha = shown }, contentAlignment = Alignment.Center) {
                        DeskVoiceSlider(track, onDark = true)
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.TopEnd).padding(20.dp).graphicsLayer { alpha = shown }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MoreButton(Color.White.copy(alpha = .6f)) { actions.menu(track) }
            KetIconButton(DeskGlyphs.ExitFullScreen, "Quitter le plein écran", onExit, Modifier.testTag("exit-fullscreen"),
                size = 36.dp, glyph = 18.dp, tint = Color.White.copy(alpha = .75f))
        }
    }
}

/** An empty cursor, for the still pointer. */
private object HiddenCursor {
    val icon: PointerIcon by lazy {
        runCatching {
            val blank = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            PointerIcon(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(blank, java.awt.Point(0, 0), "ketvoice-hidden"))
        }.getOrDefault(PointerIcon.Default)
    }
}
