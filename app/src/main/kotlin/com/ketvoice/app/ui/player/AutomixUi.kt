package com.ketvoice.app.ui.player

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.automix.Automix
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.TrackActionFeedback
import com.ketvoice.app.ui.motion.Haptics
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.rememberHaptics
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos

/**
 * The Ketmix switch: a tap turns it on or off; a long press opens the DJ editor (6.1) on the transition
 * about to play, the title playing into the next one.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Modifier.ketmixSwitchGestures(flip: () -> Unit): Modifier {
    val haptic = rememberHaptics()
    val editor = AppText.t("Éditeur Ketmix")
    return this.combinedClickable(role = Role.Switch, onClick = flip, onLongClickLabel = editor, onLongClick = {
        haptic(Haptics.Kind.Press)
        KetmixEditor.openNext()
    })
}

/** Numbers behind the mix animations, kept apart so they can be checked without a screen. */
internal object MixVisuals {
    /** Share of the mix after which the incoming title is the one shown. */
    const val HANDOVER = .55f

    fun progress(nowMs: Long, startedAt: Long, durationMs: Long): Float =
        if (durationMs <= 0L) 0f else ((nowMs - startedAt).toFloat() / durationMs).coerceIn(0f, 1f)

    private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /**
     * Opacity of the other title's cover over the one being shown. It grows to one half
     * until the hand-over; there the two covers swap places (same picture on screen), and
     * it falls back to nothing: one continuous dissolve from a cover to the next.
     */
    fun overlayAlpha(progress: Float, promoted: Boolean): Float =
        if (!promoted) .5f * smooth(progress / HANDOVER) else .5f * (1f - smooth((progress - HANDOVER) / (1f - HANDOVER)))

    /** Sideways drift of the overlaid cover, as a share of the width: in from the right, out to the left. */
    fun overlayShift(progress: Float, promoted: Boolean): Float =
        if (!promoted) .10f * (1f - smooth(progress / HANDOVER)) else -.10f * smooth((progress - HANDOVER) / (1f - HANDOVER))

    /** 0..1, peaking on each beat: the badge breathes with the music. Steady when the tempo is unknown. */
    fun pulse(elapsedMs: Long, bpm: Float): Float {
        if (bpm <= 0f) return .5f
        val beat = 60_000f / bpm
        val phase = (elapsedMs % beat.toLong().coerceAtLeast(1L)) / beat
        return (.5f + .5f * cos(2.0 * PI * phase).toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Height (0..1) of bar [index] of [count] in the mix strip. The outgoing title (top) fades
     * along the strip and the incoming one (bottom) grows, with a texture of their own so that
     * the two read as two pieces of music; [pulse] makes them breathe with the beat.
     */
    fun stripLevel(index: Int, count: Int, outgoing: Boolean, pulse: Float = .5f): Float {
        if (count <= 1) return 0f
        val x = (index.toFloat() / (count - 1)).coerceIn(0f, 1f)
        val envelope = if (outgoing) 1f - smooth(x) else smooth(x)
        val seed = index * (if (outgoing) 12.9898 else 78.233)
        val texture = .45f + .55f * (.5f + .5f * kotlin.math.sin(seed).toFloat())
        return (envelope * texture * (.85f + .3f * pulse)).coerceIn(0f, 1f)
    }

    /** How far the two rings of the glyph overlap: apart when idle, merged at the hand-over. */
    fun ringOverlap(progress: Float?): Float = if (progress == null) .42f else .42f + .5f * smooth(1f - kotlin.math.abs(progress - HANDOVER) / HANDOVER)
}

/** Progress (0..1) of the mix being heard, or null when no mix is. Redraws every frame only while mixing. */
@Composable
internal fun rememberMixProgress(): State<Float?> {
    val ui by Automix.ui.collectAsState()
    val progress = remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    LaunchedEffect(ui.phase, ui.startedAt, ui.durationMs) {
        if (ui.phase != Automix.Phase.Mixing) { progress.value = null; return@LaunchedEffect }
        while (true) {
            progress.value = MixVisuals.progress(SystemClock.elapsedRealtime(), ui.startedAt, ui.durationMs)
            withFrameMillis { }
        }
    }
    return progress
}

/**
 * The Ketmix sign: two rings that overlap, one drawn fine and one bold, like two records
 * blending into each other.
 */
@Composable
internal fun AutomixGlyph(tint: Color, modifier: Modifier = Modifier, overlap: Float = .42f) {
    Canvas(modifier) {
        val radius = size.minDimension * .30f
        val gap = radius * 2f * (1f - overlap.coerceIn(0f, .92f))
        val centre = Offset(size.width / 2f, size.height / 2f)
        drawCircle(tint, radius, Offset(centre.x - gap / 2f, centre.y), style = Stroke(size.minDimension * .075f))
        drawCircle(tint, radius, Offset(centre.x + gap / 2f, centre.y), style = Stroke(size.minDimension * .135f))
    }
}

/** Flips Ketmix, keeps the choice and says what changed. */
@Composable
internal fun rememberKetmixSwitch(): () -> Unit {
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    return remember(scope) {
        {
            val next = !AppSettings.automix.value
            AppSettings.automix.value = next
            SettingsStore.save()
            haptic(Haptics.Kind.Tick)
            scope.launch {
                TrackActionFeedback.snackbar.currentSnackbarData?.dismiss()
                TrackActionFeedback.snackbar.showSnackbar(
                    AppText.t(if (next) "Ketmix activé : les titres s'enchaînent comme dans un mix" else "Ketmix désactivé"),
                    duration = SnackbarDuration.Short)
            }
        }
    }
}

/**
 * Ketmix as the first segment of the bar under the transport, left of shuffle, repeat and
 * autoplay: the queue's modes live in one place and nothing floats over the list.
 */
@Composable
internal fun RowScope.KetmixSegment() {
    val accent = LocalTheme.current.accent
    val on = AppSettings.automix.value
    val ui by Automix.ui.collectAsState()
    val progress by rememberMixProgress()
    val flip = rememberKetmixSwitch()
    val shade by animateFloatAsState(if (on) .48f else 0f, tween(Motion.duration(Motion.FADE)), label = "ketmix-segment")
    val busy by animateFloatAsState(if (on && ui.phase != Automix.Phase.Idle) 1f else 0f, tween(Motion.duration(360)), label = "ketmix-busy")
    val description = AppText.t("Ketmix")
    val stateText = AppText.t(if (on) "Activé" else "Désactivé")
    Box(Modifier.weight(1f).fillMaxHeight()
        .background(Color.Black.copy(alpha = shade))
        .ketmixSwitchGestures(flip)
        .semantics { contentDescription = description; stateDescription = stateText }
        .testTag("automix-toggle"), contentAlignment = Alignment.Center) {
        AutomixGlyph(if (on) accent else Color.White.copy(alpha = .78f),
            Modifier.size(27.dp).graphicsLayer { val s = 1f + .08f * busy; scaleX = s; scaleY = s },
            overlap = if (Motion.reduced) .42f else MixVisuals.ringOverlap(progress))
    }
}

/** The round switch, for the layouts whose queue carries its modes above the list. */
@Composable
internal fun AutomixToggle(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val theme = LocalTheme.current
    val on = AppSettings.automix.value
    val ui by Automix.ui.collectAsState()
    val progress by rememberMixProgress()
    val flip = rememberKetmixSwitch()
    val busy = on && ui.phase != Automix.Phase.Idle
    val glow by animateFloatAsState(if (busy) 1f else 0f, tween(Motion.duration(360)), label = "automix-busy")
    val lift by animateFloatAsState(if (on) 1f else 0f,
        if (Motion.reduced) tween(0) else spring(dampingRatio = .6f, stiffness = 380f), label = "automix-on")
    val description = AppText.t("Ketmix")
    val stateText = AppText.t(if (on) "Activé" else "Désactivé")
    Box(modifier.size(size)
        .graphicsLayer { val s = 1f + .05f * lift; scaleX = s; scaleY = s }
        .clip(CircleShape)
        .background(if (on) theme.accent.copy(alpha = .22f) else Color.White.copy(alpha = .08f))
        .border(1.dp, if (on) theme.accent.copy(alpha = .35f + .45f * glow) else Color.Transparent, CircleShape)
        .ketmixSwitchGestures(flip)
        .semantics { contentDescription = description; stateDescription = stateText }
        .testTag("automix-toggle"), contentAlignment = Alignment.Center) {
        AutomixGlyph(if (on) theme.accent else Color.White.copy(alpha = .78f), Modifier.size(size * .58f),
            overlap = if (Motion.reduced) .42f else MixVisuals.ringOverlap(progress))
    }
}

/** What Ketmix is doing, or why it is not mixing: the line that tells a listener why nothing happens. */
@Composable
internal fun AutomixStatusLine(modifier: Modifier = Modifier, color: Color = Color.White.copy(alpha = .72f),
                               fontSize: androidx.compose.ui.unit.TextUnit = 12.sp, glyph: Boolean = false) {
    val text by Automix.status.collectAsState()
    if (text.isBlank() || !AppSettings.automix.value) return
    Row(modifier.testTag("automix-status"), verticalAlignment = Alignment.CenterVertically) {
        if (glyph) AutomixGlyph(LocalTheme.current.accent, Modifier.padding(end = 7.dp).size(18.dp))
        Text(text, color = color, fontSize = fontSize, lineHeight = fontSize * 1.25f, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The mix as it happens, above the progress bar: the outgoing title's bars on top fade out
 * while the incoming title's grow underneath, a playhead walks across, and the moment the
 * bass changes hands is marked. What was measured (tempo, key) sits at its end.
 * The bars are drawn from the plan, not read from the audio.
 */
@Composable
internal fun MixStrip(modifier: Modifier = Modifier) {
    val ui by Automix.ui.collectAsState()
    if (ui.phase != Automix.Phase.Mixing) return
    val progress by rememberMixProgress()
    val pulse = remember { mutableFloatStateOf(.5f) }
    LaunchedEffect(ui.bpm, Motion.reduced) {
        if (Motion.reduced) { pulse.floatValue = .5f; return@LaunchedEffect }
        val origin = SystemClock.elapsedRealtime()
        while (true) withFrameMillis { pulse.floatValue = MixVisuals.pulse(SystemClock.elapsedRealtime() - origin, ui.bpm) }
    }
    val accent = LocalTheme.current.accent
    Row(modifier.testTag("mix-strip"), verticalAlignment = Alignment.CenterVertically) {
        AutomixGlyph(accent, Modifier.size(18.dp), overlap = if (Motion.reduced) .6f else MixVisuals.ringOverlap(progress))
        Canvas(Modifier.padding(horizontal = 8.dp).weight(1f).height(24.dp)) {
            val count = 36
            val gap = size.width / count
            val bar = (gap * .5f).coerceAtLeast(1.5f)
            val middle = size.height / 2f
            val p = progress ?: 0f
            for (i in 0 until count) {
                val x = gap * (i + .5f)
                val played = x / size.width <= p
                val top = MixVisuals.stripLevel(i, count, outgoing = true, pulse = pulse.floatValue) * (middle - 1.5f)
                val bottom = MixVisuals.stripLevel(i, count, outgoing = false, pulse = pulse.floatValue) * (middle - 1.5f)
                drawLine(Color.White.copy(alpha = if (played) .9f else .35f), Offset(x, middle - 1f), Offset(x, middle - 1f - top), bar,
                    androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(accent.copy(alpha = if (played) 1f else .4f), Offset(x, middle + 1f), Offset(x, middle + 1f + bottom), bar,
                    androidx.compose.ui.graphics.StrokeCap.Round)
            }
            if (ui.swapAt >= 0f) {
                val sx = size.width * ui.swapAt
                drawLine(accent, Offset(sx, 0f), Offset(sx, size.height), 1.5.dp.toPx())
            }
            val px = size.width * p
            drawLine(Color.White, Offset(px, 0f), Offset(px, size.height), 2.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
        }
        if (ui.measures.isNotBlank()) Text(ui.measures, localize = false, color = Color.White.copy(alpha = .78f), fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
    }
}

/** A light vibration when the bass changes hands, when the listener asked for it and the app is in front. */
@Composable
internal fun KetmixSwapHaptics() {
    val haptic = rememberHaptics()
    LaunchedEffect(Unit) {
        Automix.swaps.collect { if (AppSettings.ketmixHaptics.value && AppSettings.automix.value) haptic(Haptics.Kind.Confirm) }
    }
}

/** "Mix en cours" over the artwork while two titles play together. */
@Composable
internal fun MixBadge(modifier: Modifier = Modifier) {
    val ui by Automix.ui.collectAsState()
    if (ui.phase != Automix.Phase.Mixing) return
    val progress by rememberMixProgress()
    val pulse = remember { mutableFloatStateOf(.5f) }
    LaunchedEffect(ui.bpm, Motion.reduced) {
        if (Motion.reduced) { pulse.floatValue = .5f; return@LaunchedEffect }
        val origin = SystemClock.elapsedRealtime()
        while (true) withFrameMillis { pulse.floatValue = MixVisuals.pulse(SystemClock.elapsedRealtime() - origin, ui.bpm) }
    }
    val accent = LocalTheme.current.accent
    Row(modifier.clip(RoundedCornerShape(999.dp))
        .background(Color.Black.copy(alpha = .46f))
        .border(1.dp, Color.White.copy(alpha = .16f + .22f * pulse.floatValue), RoundedCornerShape(999.dp))
        .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp).testTag("mix-badge"),
        verticalAlignment = Alignment.CenterVertically) {
        AutomixGlyph(accent, Modifier.size(22.dp).graphicsLayer { val s = .94f + .12f * pulse.floatValue; scaleX = s; scaleY = s },
            overlap = if (Motion.reduced) .6f else MixVisuals.ringOverlap(progress))
        Text("Mix en cours", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.padding(start = 6.dp))
        (if (ui.promoted) ui.from else ui.to)?.let { other ->
            Text(" · ${other.title}", localize = false, color = Color.White.copy(alpha = .72f), fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
        }
    }
}

/**
 * The other title's cover dissolving over [shown]'s artwork during a mix, and the badge
 * when the artwork is large enough to carry it. Draws nothing for any other title.
 */
@Composable
internal fun MixArtworkOverlay(shown: Track, modifier: Modifier = Modifier) {
    val ui by Automix.ui.collectAsState()
    if (ui.phase != Automix.Phase.Mixing) return
    val base = if (ui.promoted) ui.to else ui.from
    val other = (if (ui.promoted) ui.from else ui.to) ?: return
    if (base?.id != shown.id) return
    val progress by rememberMixProgress()
    val cover = ArtworkRepo.coverForTrack(other) ?: other.coverUrl
    BoxWithConstraints(modifier.fillMaxSize().testTag("mix-artwork")) {
        if (cover != null) AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer {
            val p = progress ?: 0f
            alpha = MixVisuals.overlayAlpha(p, ui.promoted)
            if (!Motion.reduced) {
                translationX = MixVisuals.overlayShift(p, ui.promoted) * size.width
                val s = 1.04f - .04f * (alpha / .5f)
                scaleX = s; scaleY = s
            }
        })
        if (maxWidth >= 200.dp) MixBadge(Modifier.align(Alignment.TopStart).padding(12.dp))
    }
}
