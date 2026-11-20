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
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.stateDescription
import com.ketvoice.app.stems.ModelPack
import com.ketvoice.app.stems.StemModel
import com.ketvoice.app.stems.StemStore
import com.ketvoice.app.stems.VoiceSeparation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ketvoice.app.ui.motion.Haptics
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.rememberHaptics
import com.ketvoice.app.ui.common.sliderPress

/** How far the voice pill may stretch past an end (dp). */
private const val PILL_STRETCH_DP = 8f

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
 * The level of the voice (6.0.0, in the manner of Apple Music Sing): a tall glass pill that fills from
 * the bottom, a microphone at its foot. Drag it up and down; tap the microphone to take the voice out or
 * bring it back. It is shown on every title (6.0.0 Beta 3): the first touch on a title that was never
 * separated separates it on the phone, and asks once to download the separation model when it is missing.
 */
@Composable
fun VoiceSlider(trackId: String?, modifier: Modifier = Modifier, width: Dp = 52.dp, height: Dp = 100.dp) {
    val haptics = rememberHaptics()
    val context = androidx.compose.ui.platform.LocalContext.current
    var level by remember { mutableFloatStateOf(AppSettings.voiceLevel.value / 100f) }
    var remembered by remember { mutableFloatStateOf(1f) }
    val shown by animateFloatAsState(level, if (Motion.reduced) spring(stiffness = 10_000f) else spring(dampingRatio = .9f, stiffness = 900f), label = "voice")
    val separation by VoiceSeparation.state.collectAsState()
    val model by StemModel.fast.state.collectAsState()
    val ready by produceState(false, trackId, separation) {
        // 7.6: a stream being separated ahead of the playhead can be turned down from its first piece on.
        value = trackId != null && withContext(Dispatchers.IO) { runCatching { StemStore.hasWhole(trackId) || StemStore.hasPartial(trackId) }.getOrDefault(false) }
    }
    val working = (separation as? VoiceSeparation.State.Working)?.takeIf { it.trackId == trackId }
    val unavailable = (separation as? VoiceSeparation.State.Unavailable)?.trackId == trackId && !ready
    val downloading = model is ModelPack.State.Downloading
    var askModel by remember { mutableStateOf(false) }
    var afterDownload by remember(trackId) { mutableStateOf(false) }
    LaunchedEffect(model, afterDownload, trackId) {
        if (afterDownload && trackId != null && model == ModelPack.State.Ready) { afterDownload = false; VoiceSeparation.request(trackId) }
    }
    /** A touch on a title not separated yet starts what it needs. */
    fun prepare() {
        val id = trackId ?: return
        if (ready || working != null) return
        when {
            unavailable -> android.widget.Toast.makeText(context,
                AppText.t("Ce son ne peut pas être séparé en streaming : téléchargez-le pour régler la voix."), android.widget.Toast.LENGTH_LONG).show()
            VoiceSeparation.modelReady -> VoiceSeparation.request(id)
            downloading -> afterDownload = true
            else -> askModel = true
        }
    }
    fun set(value: Float, tick: Boolean = true) {
        val old = level
        level = value
        VoiceLevel.level = value
        if (tick && VoiceSliderLogic.step(old) != VoiceSliderLogic.step(value)) haptics(Haptics.Kind.Tick)
    }
    fun save() { AppSettings.voiceLevel.value = Math.round(level * 100); SettingsStore.save() }
    val press = com.ketvoice.app.ui.common.rememberSliderPress()
    var raw by remember { mutableFloatStateOf(level) }
    val lensTrack = com.ketvoice.app.glass.rememberGlassBackdrop(Color.Transparent)
    val lensDensity = androidx.compose.ui.platform.LocalDensity.current.density
    val description = AppText.t("Niveau de la voix")
    val percent = working?.let { Math.round(it.fraction * 100f) }
    val preparing = AppText.t("Préparation de la voix…") + (percent?.let { " $it %" } ?: "")
    val busy = working != null || (afterDownload && downloading)
    Box(modifier.size(width, height).testTag("voice-slider")
        .graphicsLayer { alpha = if (ready || busy) 1f else if (unavailable) .45f else .8f }
        .semantics {
            contentDescription = description
            if (busy) stateDescription = preparing
            progressBarRangeInfo = ProgressBarRangeInfo(level, 0f..1f, VoiceSliderLogic.STEPS - 1)
            setProgress { target -> prepare(); set(target.coerceIn(0f, 1f), tick = false); save(); true }
        }
        .sliderPress(press, vertical = true)
        .pointerInput(trackId) {
            detectVerticalDragGestures(onDragStart = { prepare(); press.press(); raw = level },
                onDragEnd = { press.release(); save() }, onDragCancel = { press.release() }) { change, dy ->
                change.consume()
                val h = size.height.toFloat()
                // The unclamped travel feeds the rubber band of the pill past its top or its foot.
                raw = if (h > 0f) raw - dy / h else raw
                set(VoiceSliderLogic.dragged(level, dy, h))
                press.pull((raw - raw.coerceIn(0f, 1f)) * h, PILL_STRETCH_DP * density)
            }
        }
        .pointerInput(trackId) {
            detectTapGestures {
                prepare()
                if (level > .02f) remembered = level
                set(VoiceSliderLogic.tapped(level, remembered), tick = false)
                haptics(Haptics.Kind.Tick)
                save()
            }
        }, contentAlignment = Alignment.TopCenter) {
        // Held, the pill swells a little (iOS 26/27); pulled past an end it stretches that way and springs back.
        com.ketvoice.app.glass.BackdropCapture(lensTrack, Modifier.size(width, height)) {
        Canvas(Modifier.size(width, height).graphicsLayer {
            val e = if (Motion.reduced) 0f else press.expansion
            val stretch = press.stretch
            scaleX = 1f + .06f * e
            scaleY = 1f + .03f * e + (if (size.height > 0f) kotlin.math.abs(stretch) / size.height else 0f)
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, if (stretch > 0f) 1f else if (stretch < 0f) 0f else .5f)
        }) { drawVoicePill(shown) }
        }
        // 7.5: held, the level's edge turns into a small glass lens across the pill (PressLens).
        com.ketvoice.app.ui.common.PressLens(press, lensTrack, width - 6.dp, 26.dp, dark = true,
            Modifier.size(width, height)) { box ->
            val edge = 13f * lensDensity
            androidx.compose.ui.geometry.Offset(box.width / 2f, (box.height * (1f - shown)).coerceIn(edge, maxOf(edge, box.height - edge)))
        }
        // While the title is being separated (or the model arrives): a small spinner at the top of the pill.
        if (busy) androidx.compose.foundation.layout.Column(Modifier.padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val ink = if (shown > .78f) Color.Black.copy(alpha = .6f) else Color.White
            com.ketvoice.app.ui.common.ActivitySpinner(Modifier.testTag("voice-slider-preparing"), size = 18.dp, color = ink)
            // 7.6: how much is separated, under the spinner (the whole sentence is said by TalkBack).
            if (percent != null) com.ketvoice.app.ui.common.Text("$percent %", color = ink, fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp), maxLines = 1,
                modifier = Modifier.padding(top = 3.dp).testTag("voice-slider-percent"))
        }
    }
    if (askModel) com.ketvoice.app.ui.common.AppAlertDialog(
        onDismissRequest = { askModel = false },
        title = { com.ketvoice.app.ui.common.Text("Régler la voix") },
        text = { com.ketvoice.app.ui.common.Text(AppText.t("Pour régler la voix, Ketvoice télécharge une seule fois le modèle de séparation ({0} Mo). Ensuite, chaque son est séparé sur le téléphone, une seule fois.")
            .replace("{0}", (StemModel.fast.totalBytes / 1_000_000L).toString()), localize = false) },
        confirmButton = { com.ketvoice.app.ui.common.KetTextButton("Télécharger", {
            askModel = false; afterDownload = true; StemModel.fast.download()
        }, primary = true) },
        dismissButton = { com.ketvoice.app.ui.common.KetTextButton("Annuler", { askModel = false }) })
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
