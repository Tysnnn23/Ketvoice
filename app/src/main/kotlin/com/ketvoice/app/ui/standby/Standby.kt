package com.ketvoice.app.ui.standby

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.LyricsOffsets
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.LiquidGlassButton
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.player.LyricsCascade
import kotlinx.coroutines.delay

/**
 * « Mode veille au chargement » (7.5, after iOS's StandBy): while the phone charges on its side and music
 * plays (or played a moment ago), and the listener turned it on (off by default), Ketvoice shows a calm
 * full-screen Now Playing: the Canvas or the cover, a large clock, the title and artist, the sung line in
 * Cascade's look. A tap shows a few controls; it dims at night and drifts slowly against burn-in. The
 * screen is kept on only while it is shown, so only while charging. Unplugging, turning the phone upright
 * or closing it ends it. [StandbyDream] offers the same screen as Android's screen saver.
 */
internal object StandbyRules {
    /** A pause shorter than this still counts as listening (« ou récemment »). */
    const val RECENT_MS = 5 * 60_000L
    const val CONTROLS_MS = 4_000L
    /** How far (dp) the screen wanders against burn-in, and how often it moves. */
    const val DRIFT_DP = 10
    const val DRIFT_EVERY_MS = 60_000L

    fun shows(enabled: Boolean, charging: Boolean, landscape: Boolean, playing: Boolean, lastPlayedAt: Long, now: Long,
              hasTrack: Boolean, closed: Boolean): Boolean =
        enabled && charging && landscape && hasTrack && !closed && (playing || now - lastPlayedAt in 0..RECENT_MS)

    fun clockText(time: java.util.Calendar, h24: Boolean): String {
        val minute = time.get(java.util.Calendar.MINUTE)
        return if (h24) String.format(java.util.Locale.ROOT, "%02d:%02d", time.get(java.util.Calendar.HOUR_OF_DAY), minute)
            else String.format(java.util.Locale.ROOT, "%d:%02d", time.get(java.util.Calendar.HOUR).let { if (it == 0) 12 else it }, minute)
    }

    /** At night (22 h – 7 h) the screen dims to a glow that does not light the room. */
    fun night(hour: Int): Boolean = hour >= 22 || hour < 7
    fun dim(hour: Int): Float = if (night(hour)) .38f else 1f

    /** The drift of minute [step]: a slow walk around a small square, never twice the same place in a row. */
    fun drift(step: Long): IntOffset {
        val points = intArrayOf(0, 1, -1, 1, 0, -1, -1, 0, 1, -1, 0, 0)
        val i = ((step % 6 + 6) % 6).toInt() * 2
        return IntOffset(points[i] * DRIFT_DP, points[i + 1] * DRIFT_DP)
    }

    /** A Canvas moves only when the battery and the listener allow it: not in the performance mode, battery saver or at night. */
    fun canvasAllowed(performance: Boolean, saver: Boolean, reduced: Boolean, hour: Int): Boolean =
        AppSettings.animatedArtwork.value && !performance && !saver && !reduced && !night(hour)
}

/** Whether the phone is on its charger now, kept up to date by the system's battery broadcasts. */
@Composable
internal fun rememberCharging(): Boolean {
    val context = LocalContext.current.applicationContext
    var charging by remember { mutableStateOf(isCharging(context.registerReceiverSafely(null))) }
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { charging = isCharging(intent) }
        }
        context.registerReceiverSafely(receiver)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return charging
}

private fun Context.registerReceiverSafely(receiver: android.content.BroadcastReceiver?): Intent? = runCatching {
    androidx.core.content.ContextCompat.registerReceiver(this, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
}.getOrNull()

internal fun isCharging(battery: Intent?): Boolean =
    (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

/** The standby screen over the app, when its conditions are met (MainActivity). */
@Composable
fun StandbyHost() {
    if (com.ketvoice.app.BuildConfig.IS_QUEST) return
    val enabled = AppSettings.standbyMode.value
    if (!enabled) return
    val charging = rememberCharging()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val state by Player.state.collectAsState()
    var lastPlayedAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.isPlaying) {
        while (true) {
            now = System.currentTimeMillis()
            if (state.isPlaying) lastPlayedAt = now
            delay(30_000)
        }
    }
    // Closed by hand, it stays closed until the phone is unplugged or turned upright.
    var closed by remember { mutableStateOf(false) }
    LaunchedEffect(charging, landscape) { if (!charging || !landscape) closed = false }
    val track = state.current
    val shown = StandbyRules.shows(enabled, charging, landscape, state.isPlaying, lastPlayedAt, now, track != null, closed)
    val view = LocalView.current
    DisposableEffect(shown) {
        if (shown) view.keepScreenOn = true
        onDispose { if (shown) view.keepScreenOn = false }
    }
    AnimatedVisibility(shown && track != null, enter = fadeIn(tween(if (Motion.reduced) 0 else 420)),
        exit = fadeOut(tween(if (Motion.reduced) 0 else 300))) {
        BackHandler { closed = true }
        track?.let { StandbyScreen(it, state.isPlaying, onClose = { closed = true }) }
    }
}

/** The ambient Now Playing itself, for the app and for the screen saver. [onClose] null hides the close button. */
@Composable
internal fun StandbyScreen(track: Track, playing: Boolean, onClose: (() -> Unit)?, modifier: Modifier = Modifier,
                           clock: () -> java.util.Calendar = { java.util.Calendar.getInstance() }) {
    val context = LocalContext.current
    var time by remember { mutableStateOf(clock()) }
    LaunchedEffect(Unit) {
        while (true) {
            time = clock()
            // Wakes once a minute, on the minute: the clock never shows a stale time and the CPU stays idle.
            delay(60_000L - (System.currentTimeMillis() % 60_000L) + 50L)
        }
    }
    val hour = time.get(java.util.Calendar.HOUR_OF_DAY)
    val reduced = Motion.reduced
    val saver = remember(hour) { runCatching { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode }.getOrDefault(false) }
    val covers by ArtworkRepo.covers.collectAsState()
    val canvases by CanvasRepo.paths.collectAsState()
    val cover = MediaOverrides.cover(track, covers["t:${track.id}"] ?: track.coverUrl)
    val canvas = canvases[track.id]?.takeIf { !MediaOverrides.canvasHiddenInPlayer(track) &&
        StandbyRules.canvasAllowed(AppSettings.highPerformanceMode.value, saver, reduced, hour) }
    // Against burn-in: everything wanders a few dp once a minute (a jump under reduced motion).
    val drift = remember { Animatable(IntOffset.Zero, IntOffset.VectorConverter) }
    LaunchedEffect(reduced) {
        var step = 0L
        while (true) {
            delay(StandbyRules.DRIFT_EVERY_MS)
            step++
            val target = StandbyRules.drift(step)
            if (reduced) drift.snapTo(target) else drift.animateTo(target, tween(4_000))
        }
    }
    var controls by remember { mutableStateOf(false) }
    var touched by remember { mutableLongStateOf(0L) }
    LaunchedEffect(controls, touched) { if (controls) { delay(StandbyRules.CONTROLS_MS); controls = false } }
    val dim by animateFloatAsState(StandbyRules.dim(hour), tween(if (reduced) 0 else 1_200), label = "standby-dim")
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(modifier.fillMaxSize().background(Color.Black).testTag("standby")
        .clickable(remember { MutableInteractionSource() }, null) { controls = !controls; touched = System.currentTimeMillis() }) {
        BoxWithConstraints(Modifier.fillMaxSize()
            .offset { drift.value.let { IntOffset((it.x * density.density).toInt(), (it.y * density.density).toInt()) } }
            .graphicsLayer { alpha = dim }
            .padding(horizontal = 40.dp, vertical = 28.dp)) {
            val side = minOf(maxHeight, maxWidth * .42f)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                // 7.6: an upright Canvas gets an upright tile, whole and as tall as the cover's (no lit bands on the
                // night screen); a wide one is shown whole inside the square.
                val canvasShape by com.ketvoice.app.ui.player.rememberCanvasAspectRatio(canvas)
                val tileWidth = if (canvas == null) side else side * (canvasShape ?: com.ketvoice.app.ui.player.CanvasFit.UPRIGHT).coerceIn(.4f, 1f)
                Box(Modifier.height(side).width(tileWidth).clip(RoundedCornerShape(18.dp)).background(Color(0xFF1C1C1E)).testTag("standby-artwork")) {
                    if (canvas != null) com.ketvoice.app.ui.player.ArtworkCanvas(track.copy(coverUrl = cover), canvas, playing,
                        Modifier.fillMaxSize(), fit = androidx.compose.foundation.layout.PaddingValues(0.dp))
                    else if (cover != null) AsyncImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Spacer(Modifier.width(40.dp))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    // The hours and minutes in the listener's own way (24 h or 12 h), without AM / PM, as StandBy shows them.
                    Text(StandbyRules.clockText(time, android.text.format.DateFormat.is24HourFormat(context)), localize = false,
                        color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Light, maxLines = 1,
                        modifier = Modifier.testTag("standby-clock"))
                    Text(track.title, localize = false, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    Text(track.artist, localize = false, color = Color.White.copy(alpha = .6f), fontSize = 18.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    StandbyLyric(Modifier.padding(top = 18.dp))
                }
            }
        }
        AnimatedVisibility(controls, Modifier.align(Alignment.BottomEnd).padding(24.dp),
            enter = fadeIn(tween(if (reduced) 0 else 200)), exit = fadeOut(tween(if (reduced) 0 else 200))) {
            StandbyControls(playing, onAny = { touched = System.currentTimeMillis() })
        }
        if (onClose != null) AnimatedVisibility(controls, Modifier.align(Alignment.TopEnd).padding(20.dp),
            enter = fadeIn(tween(if (reduced) 0 else 200)), exit = fadeOut(tween(if (reduced) 0 else 200))) {
            LiquidGlassButton(onClose, Modifier.size(48.dp).testTag("standby-close")) {
                Icon(KetIcons.Close, "Fermer", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** The line being sung, as Cascade shows it: the sung line forward in white, the next one stepped back below. */
@Composable
private fun StandbyLyric(modifier: Modifier = Modifier) {
    val lyrics by LyricsRepo.state.collectAsState()
    val posMs by Player.positionMs.collectAsState()
    val playback by Player.state.collectAsState()
    val offsets by LyricsOffsets.values.collectAsState()
    val key = remember(playback.current?.id) { playback.current?.id?.let(LyricsOffsets::key) }
    val position = LyricsOffsets.adjustedPosition(posMs, key?.let { offsets[it] } ?: 0)
    if (!lyrics.isTimed || lyrics.lines.isEmpty()) return
    val index = lyrics.indexAt(position)
    val current = lyrics.lines.getOrNull(index)?.text?.takeIf { it.isNotBlank() } ?: return
    val next = lyrics.lines.getOrNull(index + 1)?.text?.takeIf { it.isNotBlank() }
    Column(modifier.testTag("standby-lyric")) {
        Text(current, localize = false, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 31.sp)
        if (next != null) Text(next, localize = false, color = Color.White.copy(alpha = LyricsCascade.INACTIVE_ALPHA),
            fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp).graphicsLayer {
                scaleX = LyricsCascade.INACTIVE_SCALE; scaleY = LyricsCascade.INACTIVE_SCALE
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, .5f)
            })
    }
}

@Composable
private fun StandbyControls(playing: Boolean, onAny: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag("standby-controls")) {
        com.ketvoice.app.ui.player.PlayerTransport(playing, Modifier.width(240.dp).height(64.dp),
            onPrevious = { onAny(); Player.previous() }, onToggle = { onAny(); Player.toggle() }, onNext = { onAny(); Player.next() })
    }
}
