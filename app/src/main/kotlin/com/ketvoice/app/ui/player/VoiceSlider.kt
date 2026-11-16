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
fun VoiceSlider(modifier: Modifier = Modifier, width: Dp = 52.dp, height: Dp = 100.dp) {
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
    val w = size.width; val h = size.height
    // A tall rounded square, as on iOS: corners of about a third of its width.
    val radius = CornerRadius(w * .34f, w * .34f)
    val shape = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, radius)) }
    clipPath(shape) {
        // Frosted glass over what lies behind, then the level in white from the bottom.
        drawRect(Color.White.copy(alpha = .16f))
        val top = h * (1f - level)
        drawRect(Color.White, topLeft = Offset(0f, top), size = Size(w, h - top))
    }
    // The microphone and its sparkles, in a dark ink that takes the colour of the glass behind it.
    val ink = Color.Black.copy(alpha = .58f)
    sparkle(Offset(w * .3f, h * .71f), w * .085f, Color.Black.copy(alpha = .2f))
    sparkle(Offset(w * .66f, h * .84f), w * .075f, Color.Black.copy(alpha = .5f))
    val pivot = Offset(w * .5f, h * .8f)
    rotateAround(pivot, 45f) {
        // Upright before the turn: the ball on top, its band, then the handle down to its end.
        val headR = w * .135f
        val headC = Offset(pivot.x, pivot.y - w * .2f)
        drawCircle(ink, headR, headC)
        // The band across the bottom of the ball, as a lighter ring.
        drawLine(Color.White.copy(alpha = .22f), Offset(headC.x - headR, headC.y + headR * .42f),
            Offset(headC.x + headR, headC.y + headR * .42f), strokeWidth = w * .045f)
        val handleTop = headC.y + headR * .78f
        val handleBottom = pivot.y + w * .36f
        val topHalf = w * .07f; val bottomHalf = w * .045f
        val handle = Path().apply {
            moveTo(pivot.x - topHalf, handleTop); lineTo(pivot.x + topHalf, handleTop)
            lineTo(pivot.x + bottomHalf, handleBottom); lineTo(pivot.x - bottomHalf, handleBottom); close()
        }
        drawPath(handle, ink)
        // A thin highlight down one side of the handle, and the cap at its end.
        drawLine(Color.White.copy(alpha = .16f), Offset(pivot.x + topHalf * .45f, handleTop + w * .03f),
            Offset(pivot.x + bottomHalf * .45f, handleBottom - w * .04f), strokeWidth = w * .016f)
        drawRoundRect(ink, topLeft = Offset(pivot.x - bottomHalf * .8f, handleBottom - w * .01f),
            size = Size(bottomHalf * 1.6f, w * .06f), cornerRadius = CornerRadius(w * .02f))
    }
}

/** A four-pointed star with curved sides. */
private fun DrawScope.sparkle(c: Offset, r: Float, color: Color) {
    val k = r * .18f
    val star = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x + k, c.y - k, c.x + r, c.y)
        quadraticTo(c.x + k, c.y + k, c.x, c.y + r)
        quadraticTo(c.x - k, c.y + k, c.x - r, c.y)
        quadraticTo(c.x - k, c.y - k, c.x, c.y - r)
        close()
    }
    drawPath(star, color)
}

private inline fun DrawScope.rotateAround(pivot: Offset, degrees: Float, block: DrawScope.() -> Unit) {
    drawContext.transform.rotate(degrees, pivot)
    block()
    drawContext.transform.rotate(-degrees, pivot)
}
