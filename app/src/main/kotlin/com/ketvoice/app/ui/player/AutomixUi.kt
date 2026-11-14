package com.ketvoice.app.ui.player

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
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
import com.ketvoice.app.ui.motion.Haptics
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.rememberHaptics
import kotlin.math.PI
import kotlin.math.cos

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
 * The Automix sign: two rings that overlap, one drawn fine and one bold, like two records
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

/** The switch at the bottom left of the queue. */
@Composable
internal fun AutomixToggle(modifier: Modifier = Modifier, size: Dp = 52.dp, onChanged: (Boolean) -> Unit = {}) {
    val theme = LocalTheme.current
    val on = AppSettings.automix.value
    val ui by Automix.ui.collectAsState()
    val haptic = rememberHaptics()
    val progress by rememberMixProgress()
    val busy = on && ui.phase != Automix.Phase.Idle
    val glow by animateFloatAsState(if (busy) 1f else 0f, tween(Motion.duration(360)), label = "automix-busy")
    val lift by animateFloatAsState(if (on) 1f else 0f,
        if (Motion.reduced) tween(0) else spring(dampingRatio = .6f, stiffness = 380f), label = "automix-on")
    val description = AppText.t("Automix")
    val stateText = AppText.t(if (on) "Activé" else "Désactivé")
    Box(modifier.size(size)
        .graphicsLayer { val s = 1f + .06f * lift; scaleX = s; scaleY = s }
        .clip(CircleShape)
        .background(if (on) theme.accent.copy(alpha = .26f) else Color.White.copy(alpha = .10f))
        .border(1.dp, if (on) theme.accent.copy(alpha = .5f + .4f * glow) else Color.White.copy(alpha = .14f), CircleShape)
        .clickable(role = Role.Switch) {
            val next = !AppSettings.automix.value
            AppSettings.automix.value = next
            SettingsStore.save()
            haptic(Haptics.Kind.Tick)
            onChanged(next)
        }
        .semantics { contentDescription = description; stateDescription = stateText }
        .testTag("automix-toggle"), contentAlignment = Alignment.Center) {
        AutomixGlyph(if (on) theme.accent else Color.White.copy(alpha = .82f), Modifier.size(size * .58f),
            overlap = if (Motion.reduced) .42f else MixVisuals.ringOverlap(progress))
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
