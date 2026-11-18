package com.ketvoice.app.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.player.Player
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.SliderPress
import com.ketvoice.app.ui.common.SliderPressState
import com.ketvoice.app.ui.common.SliderSteps
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.drawPressTrack
import com.ketvoice.app.ui.common.rememberSliderPress
import com.ketvoice.app.ui.common.sliderPress
import com.ketvoice.app.ui.motion.Haptics
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.rememberHaptics
import kotlinx.coroutines.launch

/*
 * The two sliders of the player — the progress (seek) bar and the volume — with the press-and-hold
 * of iOS 26/27 (see ui/common/SliderPress.kt): held, the track swells and reaches a little further
 * out with a soft spring, a sheen of glass lights it, the times or the speaker icons beside it make
 * room, and a finger past an end pulls it like a rubber band; let go, everything springs back.
 */

/** How many steps a scrub across the whole title is felt in (Ket 27). */
internal const val SCRUB_STEPS = 20
/** How many steps the volume is felt in (Ket 27): the sixteen of iOS. */
internal const val VOLUME_STEPS = 16

/** Height of the progress bar: 5 dp at rest, 12 dp under the finger (the spring may overshoot a little). */
internal fun scrubThicknessDp(progress: Float): Float = SliderPress.thicknessDp(SliderPress.Scrub, progress)

@Composable
fun ScrubBar(
    durationMs: Long,
    modifier: Modifier = Modifier,
    trackColor: Color = Color.White.copy(alpha = 0.28f),
    fillColor: Color = com.ketvoice.app.theme.LocalTheme.current.accent,
    timeColor: Color = DefaultTimeColor,
    showTimes: Boolean = true,
    onSeek: (Long) -> Unit
) {
    val posMs by Player.positionMs.collectAsState()
    val trackId = Player.state.collectAsState().value.current?.id
    val latestSeek by rememberUpdatedState(onSeek)
    // A seasonal theme fills the bar with its sunset, unless the caller chose its own colour (6.2.1).
    val season = com.ketvoice.app.theme.LocalTheme.current.season
    val seasonFill = fillColor == com.ketvoice.app.theme.LocalTheme.current.accent
    val fillBrush = if (!seasonFill) null else season?.fill
    val dur = durationMs.coerceAtLeast(1L)
    // Where the finger is while it drags (px); the value is read from the track as it is drawn, so
    // what lies under the finger is what is set, even while the track swells.
    var dragX by remember(trackId) { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableStateOf(0) }
    val anim = remember(trackId) { Animatable(0f) }
    val dragScope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val press = rememberSliderPress()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val reduced = Motion.reduced
    val fraction = (posMs.toFloat() / dur).coerceIn(0f, 1f)
    val seekEpoch by Player.seekEpoch.collectAsState()
    var lastSeek by remember(trackId) { mutableLongStateOf(seekEpoch) }

    fun trackFor(width: Float, withStretch: Boolean = true) = SliderPress.track(SliderPress.Scrub, density, 0f, width,
        press.expansion, if (withStretch) press.stretch else 0f, reduced)
    fun fractionAt(x: Float, width: Float) = trackFor(width, withStretch = false).fractionAt(x)

    LaunchedEffect(durationMs) { anim.snapTo(fraction) }
    LaunchedEffect(fraction, seekEpoch, Motion.reduced) {
        if (dragX == null) {
            if (Motion.reduced || lastSeek != seekEpoch || kotlin.math.abs(fraction - anim.value) > .04f ||
                kotlin.math.abs(fraction - anim.value) < 0.0005f) {
                anim.snapTo(fraction)
            } else {
                anim.animateTo(fraction, tween(230, easing = LinearEasing))
            }
            lastSeek = seekEpoch
        }
    }

    val dragFraction = dragX?.let { fractionAt(it, widthPx.toFloat()) }
    val shown = (dragFraction ?: anim.value).coerceIn(0f, 1f)
    // Ket 27: the progress bar of iOS 27 — a capsule that swells under the finger, without a knob, and a
    // tick in the hand at each twentieth of the title that the finger crosses.
    var lastScrubStep by remember(trackId) { mutableIntStateOf(-1) }
    fun feelScrub(next: Float) {
        val step = SliderSteps.index(next, SCRUB_STEPS)
        if (lastScrubStep != -1 && step != lastScrubStep) haptic(Haptics.Kind.Tick)
        lastScrubStep = step
    }
    // During a mix a second, finer line runs above the bar: the transition's own progress.
    val mix = rememberMixProgress()

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .onSizeChanged { widthPx = it.width }
                .sliderPress(press)
                .pointerInput(dur) {
                    detectTapGestures { off ->
                        if (durationMs > 0) latestSeek(((off.x / size.width).coerceIn(0f, 1f) * dur).toLong())
                    }
                }
                .pointerInput(dur) {
                    fun follow(x: Float) {
                        val w = size.width.toFloat()
                        dragX = x
                        press.pull(trackFor(w, withStretch = false).overshoot(x), SliderPress.Scrub.maxStretch * density.density)
                        feelScrub(fractionAt(x, w))
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { off ->
                            press.press()
                            lastScrubStep = -1
                            follow(off.x)
                        },
                        onHorizontalDrag = { change, _ -> follow(change.position.x) },
                        onDragEnd = {
                            val f = dragX?.let { fractionAt(it, size.width.toFloat()) }
                            dragX = null
                            press.release()
                            if (f != null && durationMs > 0) {
                                dragScope.launch { anim.snapTo(f) }
                                haptic(Haptics.Kind.Tick)
                                latestSeek((f * dur).toLong())
                            }
                        },
                        onDragCancel = { dragX = null; press.release() }
                    )
                }
        ) {
            val track = trackFor(size.width)
            drawPressTrack(track, shown, trackColor, fillColor, press.expansion, fillBrush = fillBrush)
            mix.value?.let { progress ->
                val w = size.width
                val lineY = size.height / 2f - 8.dp.toPx()
                // It fades in and out at the ends of the mix instead of popping.
                val presence = (progress / .08f).coerceAtMost(1f) * ((1f - progress) / .08f).coerceIn(0f, 1f)
                drawLine(Color.White.copy(alpha = .16f * presence), Offset(0f, lineY), Offset(w, lineY), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                if (progress > .002f) drawLine(Color.White.copy(alpha = .9f * presence), Offset(0f, lineY), Offset(w * progress, lineY),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        if (showTimes) ScrubTimes(shown, dur, press, mix.value, timeColor)
    }
}

private val DefaultTimeColor = Color.White.copy(alpha = 0.62f)

/** The times under the bar and, between them, Ketmix's sign while a mix plays. */
@Composable
private fun ScrubTimes(shown: Float, dur: Long, press: SliderPressState, mix: Float?, timeColor: Color) {
    val elapsed = timeColor
    val remaining = timeColor
    Row(Modifier.fillMaxWidth().padding(top = 1.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        // The times step aside as the bar reaches out, and grow a little while it is held.
        Text(formatSecs((shown * dur).toLong()), color = elapsed, fontSize = 11.sp,
            modifier = Modifier.pressLabel(press, outwards = -1f).testTag("scrub-elapsed"))
        // Between the two times, where Apple Music says it is mixing (6.0.0 Beta 5): Ketmix's sign while a mix plays.
        androidx.compose.foundation.layout.Box(Modifier.weight(1f), contentAlignment = androidx.compose.ui.Alignment.Center) {
            KetmixMixingBadge(mix, remaining)
        }
        Text("-" + formatSecs(((1f - shown) * dur).toLong()), color = remaining, fontSize = 11.sp,
            modifier = Modifier.pressLabel(press, outwards = 1f).testTag("scrub-remaining"))
    }
}

/**
 * A label or icon beside a pressable track: it moves out by as much as the track reaches, follows the
 * rubber band on its own side, and grows a little ([outwards]: -1 on the left, 1 on the right).
 */
internal fun Modifier.pressLabel(press: SliderPressState, outwards: Float, maxStretchDp: Float = SliderPress.Scrub.maxStretch,
                                 spec: com.ketvoice.app.ui.common.SliderPressSpec = SliderPress.Scrub): Modifier = graphicsLayer {
    val reduced = Motion.reduced
    val e = press.expansion
    val stretch = press.stretch
    val ownSide = if (outwards > 0f) maxOf(stretch, 0f) else minOf(stretch, 0f)
    val maxPx = maxStretchDp * density
    translationX = outwards * SliderPress.labelShiftDp(spec, e, reduced) * density + ownSide
    translationY = if (reduced) 0f else 2.dp.toPx() * e.coerceIn(0f, 1.2f)
    val s = SliderPress.labelScale(e, if (maxPx > 0f) kotlin.math.abs(ownSide) / maxPx else 0f, reduced)
    scaleX = s; scaleY = s
    transformOrigin = TransformOrigin(if (outwards > 0f) 1f else 0f, .5f)
}

@Composable
fun VolumeRail(
    value: Float,
    modifier: Modifier = Modifier,
    fillColor: Color = Color.White.copy(alpha = 0.95f),
    trackColor: Color = Color.White.copy(alpha = 0.26f),
    knobColor: Color = Color.White,
    press: SliderPressState = rememberSliderPress(),
    onChange: (Float) -> Unit
) {
    // Ket 27: the volume of iOS 27 — the rail swells under the finger and each of its sixteen steps is felt.
    val haptic = rememberHaptics()
    val latestChange by rememberUpdatedState(onChange)
    val latestValue by rememberUpdatedState(value)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val reduced = Motion.reduced
    // Where the finger is while it drags: the fill is drawn under it, exactly.
    var dragX by remember { mutableStateOf<Float?>(null) }
    fun set(next: Float) {
        if (SliderSteps.crossed(latestValue, next, VOLUME_STEPS)) haptic(Haptics.Kind.Tick)
        latestChange(next)
    }
    fun trackFor(width: Float, withStretch: Boolean = true) = with(density) {
        SliderPress.track(SliderPress.Volume, density, 2.dp.toPx(), width - 2.dp.toPx(),
            press.expansion, if (withStretch) press.stretch else 0f, reduced)
    }
    Canvas(
        modifier
            .height(30.dp)
            .sliderPress(press)
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    set((off.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                fun follow(x: Float) {
                    val track = trackFor(size.width.toFloat(), withStretch = false)
                    dragX = x
                    press.pull(track.overshoot(x), SliderPress.Volume.maxStretch * density.density)
                    set(track.fractionAt(x))
                }
                detectHorizontalDragGestures(
                    onDragStart = { off -> press.press(); follow(off.x) },
                    onDragEnd = {
                        // The last word goes to what is under the finger on the track as it now stands.
                        dragX?.let { x -> trackFor(size.width.toFloat(), withStretch = false).fractionAt(x)
                            .takeIf { kotlin.math.abs(it - latestValue) > .001f }?.let(::set) }
                        dragX = null; press.release()
                    },
                    onDragCancel = { dragX = null; press.release() }) { change, _ -> follow(change.position.x) }
            }
    ) {
        val track = trackFor(size.width)
        val shownValue = dragX?.let { trackFor(size.width, withStretch = false).fractionAt(it) } ?: value
        drawPressTrack(track, shownValue.coerceIn(0f, 1f), trackColor, fillColor, press.expansion, highlight = false)
    }
}

@Composable
fun PlayerVolume(modifier: Modifier = Modifier, compact: Boolean = false) {
    if (AppSettings.hideVolumeBar.value) return
    val volume by Player.volume.collectAsState()
    val press = rememberSliderPress()
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 0.dp else 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            // Volume natif : l'icône suit le niveau réel du média système.
            when {
                volume <= 0.001f -> Icons.Filled.VolumeOff
                volume < 0.5f -> Icons.Filled.VolumeDown
                else -> Icons.Filled.VolumeUp
            },
            null,
            tint = Color.White.copy(alpha = 0.72f),
            modifier = Modifier.size(if (compact) 18.dp else 20.dp)
                .pressLabel(press, outwards = -1f, SliderPress.Volume.maxStretch, SliderPress.Volume)
        )
        VolumeRail(
            value = volume,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            press = press
        ) {
            Player.setVolume(it)
        }
        // Pulled past the loudest end, the speaker on the right is pushed out and swells, as on iOS.
        Icon(
            Icons.Filled.VolumeUp,
            null,
            tint = Color.White.copy(alpha = 0.72f),
            modifier = Modifier.size(if (compact) 20.dp else 22.dp)
                .pressLabel(press, outwards = 1f, SliderPress.Volume.maxStretch, SliderPress.Volume)
        )
    }
}
