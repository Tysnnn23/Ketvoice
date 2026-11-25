package com.ketvoice.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.LyricsOffsets
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.desktop.lyrics.LyricsPcSettings
import com.ketvoice.desktop.platform.ClickThrough
import com.ketvoice.desktop.platform.ScreenArea
import com.ketvoice.desktop.platform.WindowStore
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.KetDesktopTheme
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

/**
 * « Paroles flottantes » (Windows 2.0, optional, off by default): a clear band above every window showing the line
 * being sung, lit word by word as in the lyrics view, on the monitor chosen in Settings. Locked (the default), clicks
 * go through it to what is under it; unlocked, it can be dragged and shows its edges and a lock. Toggled from the
 * lyrics' switches, the tray and Settings › Paroles sur PC. Shares the compact player's window code ([CompactPlayer]
 * for « still on a screen », [CompactLyric] for the line) and the lyrics view's karaoke ([WordLine]).
 */
object FloatingLyrics {
    const val WIDTH = 1000f
    const val HEIGHT = 132f

    fun toggle() { LyricsPcSettings.floating.value = !LyricsPcSettings.floating.value; LyricsPcSettings.save() }

    fun setLocked(locked: Boolean) { LyricsPcSettings.floatingLocked.value = locked; LyricsPcSettings.save() }

    /** The band's size on [screen]: at most [WIDTH] wide and 80 % of the screen. */
    fun size(screen: ScreenArea): Pair<Float, Float> = minOf(WIDTH, screen.width * .8f) to HEIGHT

    /**
     * Where the band goes on [screens]: on the chosen [monitor] (the first when it is gone), at the remembered share of
     * that screen ([place], 0..1 for the band's left and top) or at the bottom centre, kept whole on the screen.
     */
    fun bounds(screens: List<ScreenArea>, monitor: Int, place: Pair<Float, Float>?): FloatArray? {
        val s = screens.getOrNull(monitor) ?: screens.firstOrNull() ?: return null
        val (w, h) = size(s)
        val x = place?.let { s.x + it.first * (s.width - w) } ?: (s.x + (s.width - w) / 2)
        val y = place?.let { s.y + it.second * (s.height - h) } ?: (s.y + s.height - h - s.height * .08f)
        return floatArrayOf(x.coerceIn(s.x, s.x + s.width - w), y.coerceIn(s.y, s.y + s.height - h), w, h)
    }

    /** The share of [screen] a band dragged to ([x], [y]) is at, for [bounds]. */
    fun share(screen: ScreenArea, x: Float, y: Float): Pair<Float, Float> {
        val (w, h) = size(screen)
        fun part(v: Float, from: Float, room: Float) = if (room <= 0f) 0f else ((v - from) / room).coerceIn(0f, 1f)
        return part(x, screen.x, screen.width - w) to part(y, screen.y, screen.height - h)
    }
}

/** The band's window while « Paroles flottantes » is on. */
@OptIn(FlowPreview::class)
@Composable
fun FloatingLyricsWindow(dark: Boolean) {
    if (!LyricsPcSettings.floating.value) return
    val monitor = LyricsPcSettings.floatingMonitor.value
    val screens = remember(monitor) { WindowStore.awtScreens() }
    val b = remember(monitor) { FloatingLyrics.bounds(screens, monitor, LyricsPcSettings.floatingPlace()) }
    val state = rememberWindowState(size = DpSize((b?.get(2) ?: FloatingLyrics.WIDTH).dp, (b?.get(3) ?: FloatingLyrics.HEIGHT).dp),
        position = b?.let { WindowPosition(it[0].dp, it[1].dp) } ?: WindowPosition(Alignment.BottomCenter))
    val locked = LyricsPcSettings.floatingLocked.value
    Window(onCloseRequest = { FloatingLyrics.toggle() }, state = state, title = AppText.t("Paroles flottantes"), undecorated = true,
        transparent = true, resizable = false, alwaysOnTop = true, focusable = !locked) {
        LaunchedEffect(locked) { ClickThrough.apply(window, through = locked) }
        LaunchedEffect(state, monitor) {
            val screen = screens.getOrNull(monitor) ?: screens.firstOrNull() ?: return@LaunchedEffect
            snapshotFlow { state.position }.debounce(400).collect { p ->
                if (p.isSpecified) FloatingLyrics.share(screen, p.x.value, p.y.value).let { LyricsPcSettings.saveFloatingPlace(it.first, it.second) }
            }
        }
        KetDesktopTheme(dark) {
            if (locked) FloatingBand(locked = true) else WindowDraggableArea { FloatingBand(locked = false) }
        }
    }
}

/** The line being sung, large and white with a soft shadow, lit word by word; unlocked, a frame and « Verrouiller ». */
@Composable
internal fun FloatingBand(locked: Boolean) {
    val player = LocalPlayer.current
    val state by player.state.collectAsState()
    val track = state.current
    val feed = LocalLyricsFeed.current
    Box(Modifier.fillMaxSize().padding(8.dp).clip(RoundedCornerShape(22.dp))
        .background(if (locked) Color.Transparent else Color.Black.copy(alpha = .28f))
        .then(if (locked) Modifier else Modifier.border(1.dp, Color.White.copy(alpha = .35f), RoundedCornerShape(22.dp)))
        .testTag("floating-lyrics"), contentAlignment = Alignment.Center) {
        if (track != null) {
            LaunchedEffect(track.id) { feed.load(track) }
            val repo by feed.state.collectAsState()
            val clock = rememberLyricsClock()
            val offsets by LyricsOffsets.values.collectAsState()
            val now = LyricsOffsets.adjustedPosition(clock.value, offsets[LyricsOffsets.key(track.id)] ?: 0)
            val index = if (repo.trackId == track.id && repo.isTimed) repo.indexAt(now) else -1
            val line = repo.lines.getOrNull(index)
            AnimatedContent(line, Modifier.fillMaxWidth().padding(horizontal = 24.dp), transitionSpec = {
                if (Motion.reduced) fadeIn(androidx.compose.animation.core.snap()) togetherWith fadeOut(androidx.compose.animation.core.snap())
                else (fadeIn(Motion.Springs.SMOOTH.spec()) + slideInVertically(Motion.Springs.SMOOTH.spec()) { it / 3 }) togetherWith
                    (fadeOut(Motion.Springs.DISMISS.spec()) + slideOutVertically(Motion.Springs.DISMISS.spec()) { -it / 3 })
            }, label = "floating-line") { l -> BandLine(l, now) }
        }
        if (!locked) Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
            KetIconButton(KetIcons.Shield, "Verrouiller", { FloatingLyrics.setLocked(true) }, Modifier.testTag("floating-lock"), size = 26.dp, glyph = 13.dp, tint = Color.White)
            KetIconButton(KetIcons.Close, "Fermer", { FloatingLyrics.toggle() }, Modifier.testTag("floating-close"), size = 26.dp, glyph = 12.dp, tint = Color.White)
        }
    }
}

@Composable
private fun BandLine(line: MusicApi.LyricLine?, now: Long) {
    if (line == null || line.text.isBlank()) { Spacer(Modifier.height(40.dp)); return }
    val style = androidx.compose.ui.text.TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
        fontFamily = com.ketvoice.app.theme.LyricsLook.font(), shadow = Shadow(Color.Black.copy(alpha = .7f), blurRadius = 10f))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (line.words.size >= 2) WordLine(line, now, style, LineInk(Color.White.copy(alpha = .75f), Color.White))
        else androidx.compose.material3.Text(line.text, Modifier.fillMaxWidth(), color = Color.White, style = style, maxLines = 2)
    }
}
