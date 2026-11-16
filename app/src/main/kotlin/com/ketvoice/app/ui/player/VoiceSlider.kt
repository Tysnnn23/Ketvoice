package com.ketvoice.app.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.stems.VoiceLevel
import com.ketvoice.app.ui.motion.Haptics
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.rememberHaptics

/** What the voice slider does with a drag and a tap (6.0.0), kept apart so that it can be tested. */
internal object VoiceSliderLogic {
    /** Steps that tick under the finger. */
    const val STEPS = 10

    /** The level after dragging by [dy] pixels on a slider [height] pixels tall (up raises it). */
    fun dragged(level: Float, dy: Float, height: Float): Float = if (height <= 0f) level else (level - dy / height).coerceIn(0f, 1f)

    /** The step a level falls on, for the ticks. */
    fun step(level: Float): Int = Math.round(level * STEPS)

    /** A tap on the microphone: all of the voice, or none of it (the way back to where the listener was). */
    fun tapped(level: Float, remembered: Float): Float = if (level > .02f) 0f else remembered.takeIf { it > .02f } ?: 1f
}

/**
 * The level of the voice in a title separated whole (6.0.0, in the manner of Apple Music Sing): a tall
 * glass pill that fills from the bottom, a microphone at its foot. Drag it up and down; tap the
 * microphone to take the voice out or bring it back.
 */
@Composable
fun VoiceSlider(modifier: Modifier = Modifier, width: Dp = 46.dp, height: Dp = 132.dp) {
    val haptics = rememberHaptics()
    var level by remember { mutableFloatStateOf(AppSettings.voiceLevel.value / 100f) }
    var remembered by remember { mutableFloatStateOf(1f) }
    val shown by animateFloatAsState(level, if (Motion.reduced) spring(stiffness = 10_000f) else spring(dampingRatio = .9f, stiffness = 900f), label = "voice")
    fun set(value: Float, tick: Boolean = true) {
        val old = level
        level = value
        VoiceLevel.level = value
        if (tick && VoiceSliderLogic.step(old) != VoiceSliderLogic.step(value)) haptics(Haptics.Kind.Tick)
    }
    fun save() { AppSettings.voiceLevel.value = Math.round(level * 100); SettingsStore.save() }
    val description = AppText.t("Niveau de la voix")
    Box(modifier.size(width, height).testTag("voice-slider")
        .semantics {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(level, 0f..1f, VoiceSliderLogic.STEPS - 1)
            setProgress { target -> set(target.coerceIn(0f, 1f), tick = false); save(); true }
        }
        .pointerInput(Unit) {
            detectVerticalDragGestures(onDragEnd = { save() }) { change, dy ->
                change.consume()
                set(VoiceSliderLogic.dragged(level, dy, size.height.toFloat()))
            }
        }
        .pointerInput(Unit) {
            detectTapGestures {
                if (level > .02f) remembered = level
                set(VoiceSliderLogic.tapped(level, remembered), tick = false)
                haptics(Haptics.Kind.Tick)
                save()
            }
        }) {
        Canvas(Modifier.size(width, height)) { drawVoicePill(shown) }
    }
}

private fun DrawScope.drawVoicePill(level: Float) {
    val radius = CornerRadius(size.width / 2, size.width / 2)
    val pill = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, radius)) }
    // Frosted glass, its level of white rising from the bottom, a thin rim.
    drawRoundRect(Color.White.copy(alpha = .18f), cornerRadius = radius)
    clipPath(pill) {
        val top = size.height * (1f - level)
        drawRect(Color.White.copy(alpha = .92f), topLeft = Offset(0f, top), size = Size(size.width, size.height - top))
    }
    drawRoundRect(Color.White.copy(alpha = .28f), cornerRadius = radius, style = Stroke(1.dp.toPx()))
    // The microphone at the foot, dark on the white fill, white on the glass.
    val c = Offset(size.width / 2, size.height - size.width * .62f)
    val u = size.width / 46f
    // Dark once the white fill reaches the microphone's head, white on the glass.
    val onFill = size.height * (1f - level) < c.y - 4f * u
    val ink = if (onFill) Color(0xFF2B2B30) else Color.White
    // Head, tilted like the reference: a capsule from the lower left to the upper right.
    val head = Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(c.x - 3.2f * u, c.y - 12f * u, c.x + 3.2f * u, c.y - 1f * u, CornerRadius(3.2f * u)))
    }
    rotateAround(c, 38f) {
        drawPath(head, ink)
        drawLine(ink, Offset(c.x, c.y - 1f * u), Offset(c.x, c.y + 9f * u), strokeWidth = 2.2f * u)
    }
    // Two small sparkles: the voice that can be taken out.
    fun sparkle(at: Offset, r: Float) {
        drawLine(ink.copy(alpha = .8f), Offset(at.x - r, at.y), Offset(at.x + r, at.y), strokeWidth = 1.2f * u)
        drawLine(ink.copy(alpha = .8f), Offset(at.x, at.y - r), Offset(at.x, at.y + r), strokeWidth = 1.2f * u)
    }
    sparkle(Offset(c.x - 9f * u, c.y - 6f * u), 2.4f * u)
    sparkle(Offset(c.x + 8f * u, c.y + 4f * u), 2f * u)
}

private inline fun DrawScope.rotateAround(pivot: Offset, degrees: Float, block: DrawScope.() -> Unit) {
    drawContext.transform.rotate(degrees, pivot)
    block()
    drawContext.transform.rotate(-degrees, pivot)
}
