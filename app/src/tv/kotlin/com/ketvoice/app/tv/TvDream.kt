package com.ketvoice.app.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.collectPick
import com.ketvoice.app.ui.common.pick
import com.ketvoice.app.ui.motion.Motion
import kotlinx.coroutines.delay
import kotlin.math.sin

/** What the screensaver shows: the title playing, or the art frame's gallery when nothing plays. */
sealed interface TvDreamScene {
    data class Playing(val track: Track) : TvDreamScene
    data object Gallery : TvDreamScene
}

/**
 * « Écran de veille Ketvoice » (Ketvoice TV 1.1.0): what the screensaver shows and where. The block of text and
 * cover drifts once a minute along a path that sweeps the whole screen (no pixel holds the same light for long), and
 * the only clocks are the track changing and the minute turning. Pure: host-tested.
 */
object TvDreamPlan {
    const val MINUTE_MS = 60_000L
    /** How long the block glides to its next place (none under reduced motion or on modest TVs: it jumps). */
    const val DRIFT_MS = 4_000

    fun scene(current: Track?, playing: Boolean): TvDreamScene =
        if (current != null && playing) TvDreamScene.Playing(current) else TvDreamScene.Gallery

    /** Milliseconds until the next minute begins (at least one second, so a late wake-up never spins). */
    fun untilNextMinute(nowMs: Long): Long = (MINUTE_MS - nowMs.mod(MINUTE_MS)).coerceAtLeast(1_000L)

    /**
     * Where the block sits during [minute], as fractions (0..1) of the free room across and down: two slow sines of
     * unrelated periods, so the path never settles on a loop that leaves part of the screen unused.
     */
    fun drift(minute: Long): Pair<Float, Float> {
        val m = minute.toDouble()
        val x = (sin(m * 0.83) + 1.0) / 2.0
        val y = (sin(m * 0.53 + 1.7) + 1.0) / 2.0
        return x.toFloat().coerceIn(0f, 1f) to y.toFloat().coerceIn(0f, 1f)
    }

    /** « 21:07 » or « 9:07 PM », as the TV is set. */
    fun clock(hour: Int, minute: Int, is24Hour: Boolean): String {
        val mm = minute.toString().padStart(2, '0')
        if (is24Hour) return hour.toString().padStart(2, '0') + ":" + mm
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return "$h12:$mm " + if (hour < 12) "AM" else "PM"
    }

    fun driftMs(tier: TvTier, reduced: Boolean): Int = if (reduced || tier == TvTier.Light) 0 else DRIFT_MS
}

/** The time, refreshed when the minute turns and never more often. */
@Composable
fun rememberMinute(now: () -> Long = System::currentTimeMillis): State<Long> = produceState(now()) {
    while (true) {
        delay(TvDreamPlan.untilNextMinute(value))
        value = now()
    }
}

/** The screensaver's screen: [TvDreamPlayingScene] while music plays, the art frame's gallery otherwise, the time drifting over it. */
@Composable
fun TvDreamScreen(now: () -> Long = System::currentTimeMillis) {
    val current by remember { Player.state.pick { it.current } }.collectPick()
    val playing by remember { Player.state.pick { it.playWhenReady } }.collectPick()
    val minute by rememberMinute(now)
    val scene = TvDreamPlan.scene(current, playing)
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("tv-dream")) {
        when (scene) {
            is TvDreamScene.Playing -> TvDreamPlayingScene(scene.track, minute)
            TvDreamScene.Gallery -> TvDreamGallery(minute)
        }
    }
}

@Composable
private fun TvDreamPlayingScene(track: Track, nowMs: Long) {
    val cover = remember(track.id) { ArtworkRepo.coverForTrack(track) ?: track.coverUrl }
    TvBackdrop(cover, Modifier.fillMaxSize(), veil = .78f)
    DriftBox(nowMs) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("dream-playing")) {
            TvCover(cover, 220.dp, RoundedCornerShape(TvDesign.Radius.panel), placeholder = com.ketvoice.app.ui.common.KetIcons.Music)
            Spacer(Modifier.width(32.dp))
            Column(Modifier.widthIn(max = 420.dp)) {
                DreamClock(nowMs)
                Spacer(Modifier.height(10.dp))
                Text(track.title, localize = false, style = TvDesign.Type.title, color = Color.White.copy(alpha = .9f), maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(track.artist, localize = false, style = TvDesign.Type.row, color = Color.White.copy(alpha = .6f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TvDreamGallery(nowMs: Long) {
    val items by rememberArtFrames()
    val index = rememberFrameIndex(items.size)
    if (items.isNotEmpty()) ArtFrameGallery(items, index.intValue, Modifier.fillMaxSize(), showText = false)
    DriftBox(nowMs) { DreamClock(nowMs, Modifier.testTag("dream-clock")) }
}

/** A soft shadow keeps the time readable over a light cover of the gallery (drawn with the text, no extra layer). */
private val clockStyle get() = TextStyle(fontFamily = Ket27.font, fontWeight = FontWeight.Light, fontSize = 64.sp, lineHeight = 68.sp,
    letterSpacing = (-1).sp, shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = .55f), blurRadius = 18f))

@Composable
private fun DreamClock(nowMs: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val is24 = remember { android.text.format.DateFormat.is24HourFormat(context) }
    val time = remember(nowMs) { java.util.Calendar.getInstance().apply { timeInMillis = nowMs } }
    Text(TvDreamPlan.clock(time.get(java.util.Calendar.HOUR_OF_DAY), time.get(java.util.Calendar.MINUTE), is24), localize = false,
        style = clockStyle, color = Color.White.copy(alpha = .82f), modifier = modifier)
}

/**
 * Places its content at [TvDreamPlan.drift] of the minute inside the overscan-safe frame. The glide is read in the
 * layout phase only: moving the block never recomposes it.
 */
@Composable
private fun DriftBox(nowMs: Long, content: @Composable () -> Unit) {
    val (fx, fy) = TvDreamPlan.drift(nowMs / TvDreamPlan.MINUTE_MS)
    val ms = TvDreamPlan.driftMs(tvTier(), Motion.reduced)
    val x by animateFloatAsState(fx, if (ms == 0) snap() else tween(ms), label = "dream-x")
    val y by animateFloatAsState(fy, if (ms == 0) snap() else tween(ms), label = "dream-y")
    Box(Modifier.fillMaxSize().padding(horizontal = TvDesign.Safe.horizontal, vertical = TvDesign.Safe.vertical)) {
        Box(Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place(((constraints.maxWidth - placeable.width).coerceAtLeast(0) * x).toInt(),
                    ((constraints.maxHeight - placeable.height).coerceAtLeast(0) * y).toInt())
            }
        }) { content() }
    }
}
