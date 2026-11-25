package com.ketvoice.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.LyricsOffsets
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.desktop.platform.ScreenArea
import com.ketvoice.desktop.platform.WindowStore
import com.ketvoice.desktop.ui.glass.GlassStyle
import com.ketvoice.desktop.ui.glass.glass
import com.ketvoice.desktop.ui.glass.glassSource
import com.ketvoice.desktop.ui.glass.rememberGlassBackdrop
import com.ketvoice.desktop.ui.theme.DeskGlyphs
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.KetDesktopTheme
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

/**
 * The compact player (Windows 2.0): a small glass window without a frame, above the others, with the cover, the
 * transport and the line being sung. Toggled from the island, the full player, the tray, Ctrl + M and Settings ›
 * Windows; dragged anywhere, it opens where it was left ([CompactPlayer.place]).
 */
object MiniPlayer {
    val open = mutableStateOf(false)
    fun toggle() { open.value = !open.value }
}

/** The compact window's measures and its remembered place (in the `desktop` preferences, dp). */
object CompactPlayer {
    /** The window, the glass card and the clear margin its shadow falls in. */
    const val WIDTH = 444
    const val HEIGHT = 172
    const val MARGIN = 14

    private val prefs by lazy { android.content.Context.app.getSharedPreferences("desktop", android.content.Context.MODE_PRIVATE) }

    fun load(): Pair<Float, Float>? = runCatching {
        val x = prefs.getFloat("compactX", Float.NaN); val y = prefs.getFloat("compactY", Float.NaN)
        if (x.isNaN() || y.isNaN()) null else x to y
    }.getOrNull()

    fun save(x: Float, y: Float) { runCatching { prefs.edit().putFloat("compactX", x).putFloat("compactY", y).apply() } }

    /**
     * Where the window opens: where it was left when that is still on a screen (most of it visible), else the bottom
     * right of the first screen. Null when there is no screen to ask (tests, headless).
     */
    fun place(saved: Pair<Float, Float>?, screens: List<ScreenArea>, w: Float = WIDTH.toFloat(), h: Float = HEIGHT.toFloat()): Pair<Float, Float>? {
        if (saved != null && screens.any { s -> visible(saved, s, w, h) }) return saved
        val s = screens.firstOrNull() ?: return null
        return (s.x + s.width - w - 16f) to (s.y + s.height - h - 16f)
    }

    private fun visible(p: Pair<Float, Float>, s: ScreenArea, w: Float, h: Float): Boolean {
        val left = maxOf(p.first, s.x); val right = minOf(p.first + w, s.x + s.width)
        val top = maxOf(p.second, s.y); val bottom = minOf(p.second + h, s.y + s.height)
        return right - left >= w * .5f && bottom - top >= h * .5f
    }
}

/** The compact player's window, while [MiniPlayer.open]; « Agrandir » brings the main window back. */
@OptIn(FlowPreview::class)
@Composable
fun MiniPlayerWindow(icon: Painter?, dark: Boolean, onShowMain: () -> Unit) {
    if (!MiniPlayer.open.value) return
    val start = remember { CompactPlayer.place(CompactPlayer.load(), WindowStore.awtScreens()) }
    val state = rememberWindowState(size = DpSize(CompactPlayer.WIDTH.dp, CompactPlayer.HEIGHT.dp),
        position = start?.let { WindowPosition(it.first.dp, it.second.dp) } ?: WindowPosition(Alignment.BottomEnd))
    Window(onCloseRequest = { MiniPlayer.open.value = false }, state = state, title = AppText.t("Mini-lecteur"), icon = icon,
        undecorated = true, transparent = true, resizable = false, alwaysOnTop = true) {
        LaunchedEffect(state) {
            snapshotFlow { state.position }.debounce(400).collect { p -> if (p.isSpecified) CompactPlayer.save(p.x.value, p.y.value) }
        }
        KetDesktopTheme(dark) {
            val player = LocalPlayer.current
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val actions = remember(player) { TrackActions(player, LibraryBridge, Navigator(), scope) }
            Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                // The same keys as the main window, without the ones that open a page there.
                val t = object : Shortcuts.Target {
                    override val typing = false; override val fullPlayer = false
                    override fun focusSearch() { onShowMain() }
                    override fun leaveField() {}
                    override fun setFullPlayer(open: Boolean) { onShowMain() }
                    override fun settings() { onShowMain() }
                    override fun back() = false
                }
                Shortcuts.handle(e, player, actions, t)
            }) {
                WindowDraggableArea { MiniPlayerContent(onExpand = { MiniPlayer.open.value = false; onShowMain() }, onClose = { MiniPlayer.open.value = false }) }
            }
        }
    }
}

/**
 * The compact player's card: the cover's picture blurred behind clear glass, the cover, title and artist, the line
 * being sung, the transport on a glass capsule and how far the title has played. « Agrandir » and close show under
 * the pointer.
 */
@Composable
fun MiniPlayerContent(onExpand: () -> Unit, onClose: () -> Unit = {}) {
    val ket = LocalKet.current
    val player = LocalPlayer.current
    val state by player.state.collectAsState()
    val track = state.current
    val image = rememberCover(track?.coverUrl)
    val backdrop = rememberGlassBackdrop()
    val (source, hovered) = rememberHover()
    val radius = 26.dp
    val ink = if (ket.dark || image != null) Color.White else ket.label
    Box(Modifier.fillMaxSize().padding(CompactPlayer.MARGIN.dp).shadow(16.dp, RoundedCornerShape(radius), clip = false)
        .clip(RoundedCornerShape(radius)).hoverable(source).testTag("mini-player")) {
        Box(Modifier.fillMaxSize().background(ket.card).glassSource(backdrop)) {
            if (image != null) {
                Image(image, null, Modifier.fillMaxSize().blur(30.dp), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (ket.dark) .42f else .28f)))
            }
        }
        Row(Modifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track?.coverUrl, 92.dp, radius = 16.dp, shadow = 10.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    if (track == null) KText("Aucune lecture en cours", Ket27Desk.Type.subheadline, color = ink.copy(alpha = .7f))
                    else {
                        KText(track.title, Ket27Desk.Type.subheadline, weight = FontWeight.SemiBold, color = ink, translate = false)
                        KText(track.artist, Ket27Desk.Type.footnote, color = ink.copy(alpha = .72f), translate = false)
                    }
                    if (track != null) CompactLyricLine(track, ink)
                }
                CompactTransport(backdrop, ink)
            }
        }
        AnimatedVisibility(hovered || Motion.reduced, Modifier.align(Alignment.TopEnd).padding(8.dp),
            enter = fadeIn(Motion.Springs.SMOOTH.spec()), exit = fadeOut(Motion.Springs.DISMISS.spec())) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                KetIconButton(DeskGlyphs.EnterFullScreen, "Agrandir", onExpand, Modifier.testTag("mini-expand"), size = 26.dp, glyph = 13.dp, tint = ink.copy(alpha = .8f))
                KetIconButton(KetIcons.Close, "Fermer", onClose, Modifier.testTag("mini-close"), size = 26.dp, glyph = 12.dp, tint = ink.copy(alpha = .8f))
            }
        }
        if (track != null) CompactProgress(state.positionMs, state.durationMs.takeIf { it > 0 } ?: track.durationMs, ink, Modifier.align(Alignment.BottomCenter))
    }
}

/** The line being sung (or nothing without timed lyrics), crossfading from one line to the next. */
@Composable
private fun CompactLyricLine(track: com.ketvoice.app.data.Track, ink: Color) {
    val feed = LocalLyricsFeed.current
    LaunchedEffect(track.id) { feed.load(track) }
    val repo by feed.state.collectAsState()
    val clock = rememberLyricsClock()
    val offsets by LyricsOffsets.values.collectAsState()
    val now = LyricsOffsets.adjustedPosition(clock.value, offsets[LyricsOffsets.key(track.id)] ?: 0)
    val line = if (repo.trackId == track.id && repo.isTimed) CompactLyric.lineAt(repo, now) else null
    AnimatedContent(line, Modifier.padding(top = 6.dp).fillMaxWidth().testTag("mini-lyric"), transitionSpec = {
        if (Motion.reduced) fadeIn(androidx.compose.animation.core.snap()) togetherWith fadeOut(androidx.compose.animation.core.snap())
        else (fadeIn(Motion.Springs.SMOOTH.spec()) + slideInVertically(Motion.Springs.SMOOTH.spec()) { it / 2 }) togetherWith
            (fadeOut(Motion.Springs.DISMISS.spec()) + slideOutVertically(Motion.Springs.DISMISS.spec()) { -it / 2 })
    }, label = "mini-lyric") { text ->
        if (!text.isNullOrBlank()) KText(text, Ket27Desk.Type.footnote, color = ink, weight = FontWeight.SemiBold, translate = false)
        else Spacer(Modifier.height(18.dp))
    }
}

/** The lyric line at a time (the active line of the repository's state). */
internal object CompactLyric {
    fun lineAt(state: com.ketvoice.app.data.LyricsRepo.State, nowMs: Long): String? =
        state.indexAt(nowMs).takeIf { it >= 0 }?.let { state.lines.getOrNull(it)?.text }
}

@Composable
private fun CompactTransport(backdrop: com.ketvoice.desktop.ui.glass.GlassBackdrop, ink: Color) {
    val player = LocalPlayer.current
    val state by player.state.collectAsState()
    val track = state.current
    Row(Modifier.glass(backdrop, 20.dp, Color.Black.copy(alpha = .2f), GlassStyle.Drop, rim = Color.White.copy(alpha = .3f)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        KetIconButton(DeskGlyphs.Previous, "Précédent", { player.previous() }, size = 34.dp, glyph = 17.dp, enabled = track != null, tint = ink)
        PlayPause(state.isPlaying, { player.toggle() }, 40.dp, 22.dp, tint = ink)
        KetIconButton(DeskGlyphs.Next, "Suivant", { player.next() }, size = 34.dp, glyph = 17.dp, enabled = track != null, tint = ink)
    }
}

@Composable
private fun CompactProgress(positionMs: Long, durationMs: Long, ink: Color, modifier: Modifier) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Box(modifier.fillMaxWidth().height(3.dp).background(ink.copy(alpha = .14f))) {
        Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(ink.copy(alpha = .85f)))
    }
}

/** The compact player as an icon button (the island and the full player). */
@Composable
internal fun MiniPlayerButton(tint: Color, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 36.dp) =
    KetIconButton(DeskGlyphs.Compact, "Mini-lecteur", { MiniPlayer.toggle() }, modifier.testTag("mini-toggle"), size = size, glyph = 17.dp, tint = tint,
        active = MiniPlayer.open.value)

