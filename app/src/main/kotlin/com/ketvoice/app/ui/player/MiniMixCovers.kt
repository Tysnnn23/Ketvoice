package com.ketvoice.app.ui.player

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ketvoice.app.automix.Automix
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.motion.Motion
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** The geometry of the mini-player's double cover (7.3), kept apart so it is tested without drawing. */
internal object MiniMix {
    /** Room between the two covers, where the wave runs. */
    val GAP = 22.dp

    fun ease(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /** Opacity of the outgoing cover: it dims as the mix goes on and leaves while the slot closes. */
    fun outgoingAlpha(progress: Float, open: Float, closing: Boolean): Float =
        (1f - .55f * ease(progress)) * (if (closing) open.coerceIn(0f, 1f) else 1f)

    /** Opacity of the incoming cover: it arrives with the slot and is whole by the end of the mix. */
    fun incomingAlpha(progress: Float, open: Float, closing: Boolean): Float =
        (.4f + .6f * ease(progress)) * (if (closing) 1f else open.coerceIn(0f, 1f))

    /** Height of the wave, as a share of the cover: strongest mid-mix, breathing on the beat. */
    fun waveHeight(progress: Float, pulse: Float): Float =
        .13f * (.35f + .65f * sin(PI * progress.coerceIn(0f, 1f)).toFloat()) * (.7f + .3f * pulse.coerceIn(0f, 1f))

    /** Under reduced motion: opacity of the other title's cover over the one shown, one plain crossfade. */
    fun crossfade(progress: Float, promoted: Boolean): Float = if (promoted) 1f - progress.coerceIn(0f, 1f) else progress.coerceIn(0f, 1f)
}

private class MixPair { var from: Track? = null; var to: Track? = null }

/**
 * The docked mini-player's cover (phone and tablet). While Ketmix mixes the title shown, the slot opens
 * to hold both covers, the outgoing one dimming on the left and the incoming one arriving on the right,
 * with a fine wave between them that moves with the mix; once the mix is over the slot closes and the
 * incoming cover slides into the place. Everything that moves is read while placing or drawing: a mix
 * recomposes this only as it starts and ends. Under reduced motion the slot never opens and the next
 * cover simply fades over the current one. [single] is the cover as it is drawn outside a mix.
 */
@Composable
internal fun MiniMixCovers(shown: Track, coverSize: Dp, radius: Dp, single: @Composable () -> Unit) {
    val ui by Automix.ui.collectAsState()
    val from = ui.from; val to = ui.to
    val mixing = ui.phase == Automix.Phase.Mixing && from != null && to != null && (from.id == shown.id || to.id == shown.id)
    val reduced = Motion.reduced
    val progress = rememberMixProgress()
    if (reduced) {
        Box(Modifier.size(coverSize)) {
            single()
            val other = (if (ui.promoted) from else to)?.takeIf { mixing }
            if (other != null) AsyncImage(ArtworkRepo.coverForTrack(other) ?: other.coverUrl, null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(radius)).testTag("mini-mix-fade")
                    .graphicsLayer { alpha = MiniMix.crossfade(progress.value ?: 0f, ui.promoted) })
        }
        return
    }
    // The pair is kept while the slot closes after the mix.
    val held = remember { MixPair() }
    if (mixing) { held.from = from; held.to = to }
    val open = animateFloatAsState(if (mixing) 1f else 0f,
        if (mixing) spring(dampingRatio = .9f, stiffness = 240f) else spring(dampingRatio = .82f, stiffness = 300f), label = "mini-mix-open")
    val opened by remember { derivedStateOf { open.value > .002f } }
    val outgoing = held.from; val incoming = held.to
    if (!opened || outgoing == null || incoming == null) {
        Box(Modifier.size(coverSize)) { single() }
        return
    }
    val closing = !mixing
    val wave = LocalTheme.current.onBackground
    val bpm = ui.bpm
    val startedAt = ui.startedAt
    fun mixAt(): Float = progress.value ?: if (closing) 1f else 0f
    Box(Modifier
        .layout { measurable, _ ->
            val side = coverSize.roundToPx()
            val width = side + ((side + MiniMix.GAP.toPx()) * open.value.coerceIn(0f, 1f)).roundToInt()
            val placeable = measurable.measure(Constraints.fixed(width, side))
            layout(width, side) { placeable.place(0, 0) }
        }
        .semantics { contentDescription = com.ketvoice.app.data.AppText.t("Ketmix mixe les titres") }
        .testTag("mini-mix-covers")
        .drawBehind {
            val p = mixAt()
            val shown = open.value.coerceIn(0f, 1f)
            val side = coverSize.toPx()
            val start = side + 3.dp.toPx(); val end = size.width - side - 3.dp.toPx()
            if (end - start < 4.dp.toPx()) return@drawBehind
            val pulse = MixVisuals.pulse(SystemClock.elapsedRealtime() - startedAt, bpm)
            val height = size.height * MiniMix.waveHeight(p, pulse)
            val phase = (SystemClock.elapsedRealtime() % 60_000L) / 1000f * 2f * PI.toFloat() * .9f
            val mid = size.height / 2f
            for (line in 0..1) {
                val path = Path()
                val steps = 28
                for (i in 0..steps) {
                    val x = i / steps.toFloat()
                    // The wave leaves one cover and reaches the other at rest: its swing tapers at both ends.
                    val y = mid + height * (if (line == 0) 1f else -.6f) * sin(PI.toFloat() * x) *
                        sin(x * 2f * PI.toFloat() * 1.5f - phase + line * 1.2f)
                    val at = Offset(start + (end - start) * x, y)
                    if (i == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
                }
                drawPath(path, wave.copy(alpha = (if (line == 0) .55f else .28f) * shown),
                    style = Stroke(width = (if (line == 0) 1.3f else 1f) * density, cap = StrokeCap.Round))
            }
        }) {
        val shape = RoundedCornerShape(radius)
        Box(Modifier.align(Alignment.CenterStart).size(coverSize).graphicsLayer {
            val p = mixAt()
            alpha = MiniMix.outgoingAlpha(p, open.value, closing)
            val s = 1f - .1f * MiniMix.ease(p)
            scaleX = s; scaleY = s
            translationX = -2.dp.toPx() * MiniMix.ease(p)
        }.clip(shape)) {
            Cover(outgoing.title, ArtworkRepo.coverForTrack(outgoing) ?: outgoing.coverUrl, size = coverSize, radius = radius)
        }
        Box(Modifier.align(Alignment.CenterEnd).size(coverSize).graphicsLayer {
            val p = mixAt()
            alpha = MiniMix.incomingAlpha(p, open.value, closing)
            val s = .9f + .1f * MiniMix.ease(p)
            scaleX = s; scaleY = s
        }.clip(shape).testTag("mini-mix-incoming")) {
            Cover(incoming.title, ArtworkRepo.coverForTrack(incoming) ?: incoming.coverUrl, size = coverSize, radius = radius)
        }
    }
}
