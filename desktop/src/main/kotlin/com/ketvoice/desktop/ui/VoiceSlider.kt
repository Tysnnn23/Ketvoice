package com.ketvoice.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Track
import com.ketvoice.app.stems.ModelPack
import com.ketvoice.app.stems.StemModel
import com.ketvoice.app.stems.VoiceLevel
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.desktop.stems.DesktopVoice
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet

/** What the voice slider does with a drag, a click and the wheel, kept apart so that it can be tested. */
internal object DeskVoiceLogic {
    fun dragged(level: Float, dy: Float, height: Float): Float = if (height <= 0f) level else (level - dy / height).coerceIn(0f, 1f)
    /** A click on the microphone: all of the voice, or none of it (back to where the listener was). */
    fun tapped(level: Float, remembered: Float): Float = if (level > .02f) 0f else remembered.takeIf { it > .02f } ?: 1f
    /** One notch of the wheel moves a tenth (up raises it). */
    fun wheel(level: Float, notches: Float): Float = (level - notches * .1f).coerceIn(0f, 1f)
}

/**
 * The level of the voice beside the lyrics (the phone's `VoiceSlider`, in the manner of Apple Music Sing): a tall
 * pill that fills from the bottom, a microphone at its foot. Drag it, scroll over it, or click the microphone to
 * take the voice out or bring it back. The first touch on a title never separated separates it on this computer,
 * and asks once to download the separation model when it is missing.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DeskVoiceSlider(track: Track, onDark: Boolean, modifier: Modifier = Modifier, width: Dp = 40.dp, height: Dp = 112.dp) {
    val ket = LocalKet.current
    var level by remember { mutableFloatStateOf(AppSettings.voiceLevel.value / 100f) }
    var remembered by remember { mutableFloatStateOf(1f) }
    val shown by animateFloatAsState(level, if (Motion.reduced) snap() else spring(dampingRatio = .9f, stiffness = 900f), label = "voice")
    val separation by DesktopVoice.state.collectAsState()
    val model by StemModel.fast.state.collectAsState()
    val ready = remember(track.id, separation) { DesktopVoice.ready(track.id) }
    val working = (separation as? DesktopVoice.State.Working)?.takeIf { it.trackId == track.id }
    var askModel by remember { mutableStateOf(false) }
    var afterDownload by remember(track.id) { mutableStateOf(false) }
    LaunchedEffect(model, afterDownload, track.id) {
        if (afterDownload && model == ModelPack.State.Ready) { afterDownload = false; DesktopVoice.request(track) }
    }
    fun prepare() {
        if (ready) { DesktopVoice.request(track); return }
        when {
            working != null -> {}
            StemModel.anyReady -> DesktopVoice.request(track)
            model is ModelPack.State.Downloading -> afterDownload = true
            else -> askModel = true
        }
    }
    fun set(value: Float) { level = value; VoiceLevel.level = value; AppSettings.voiceLevel.value = Math.round(value * 100) }
    val busy = working != null || (afterDownload && model is ModelPack.State.Downloading)
    val percent = working?.fraction?.let { Math.round(it * 100f) }
        ?: (model as? ModelPack.State.Downloading)?.takeIf { afterDownload }?.let { Math.round(it.fraction * 100f) }
    val description = AppText.t("Niveau de la voix")
    val preparing = AppText.t("Préparation de la voix…") + (percent?.let { " $it %" } ?: "")
    val trackColor = if (onDark) Color.White.copy(alpha = .16f) else ket.fill
    val fill = if (onDark) Color.White else ket.label
    val ink = if (onDark) Color.Black.copy(alpha = .58f) else Color.White.copy(alpha = .82f)
    val inkOff = if (onDark) Color.White.copy(alpha = .7f) else ket.secondary
    Box(modifier.size(width, height).testTag("voice-slider")
        .semantics {
            contentDescription = description
            if (busy) stateDescription = preparing
            progressBarRangeInfo = ProgressBarRangeInfo(level, 0f..1f, 9)
            setProgress { target -> prepare(); set(target.coerceIn(0f, 1f)); true }
        }
        .pointerInput(track.id) {
            detectVerticalDragGestures(onDragStart = { prepare() }) { change, dy ->
                change.consume(); set(DeskVoiceLogic.dragged(level, dy, size.height.toFloat()))
            }
        }
        .pointerInput(track.id) {
            detectTapGestures {
                prepare()
                if (level > .02f) remembered = level
                set(DeskVoiceLogic.tapped(level, remembered))
            }
        }
        .onPointerEvent(PointerEventType.Scroll) { e ->
            val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
            if (dy != 0f) { prepare(); set(DeskVoiceLogic.wheel(level, dy)) }
        }, contentAlignment = Alignment.TopCenter) {
        Canvas(Modifier.size(width, height)) { drawVoicePill(shown, trackColor, fill, if (shown > .2f) ink else inkOff) }
        if (busy) Column(Modifier.padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val onFill = shown > .82f
            Spinner(Modifier.size(16.dp))
            if (percent != null) KText("$percent %", Ket27Desk.Type.caption2, Modifier.padding(top = 3.dp).testTag("voice-slider-percent"),
                color = if (onFill) ink else inkOff, translate = false)
        }
    }
    if (askModel) ConfirmDialog(AppText.t("Régler la voix"),
        AppText.t("Pour régler la voix, Ketvoice télécharge une seule fois le modèle de séparation ({0} Mo). Ensuite, chaque titre est séparé sur cet ordinateur, une seule fois.")
            .replace("{0}", (StemModel.fast.totalBytes / 1_000_000L).toString()),
        "Télécharger", { afterDownload = true; StemModel.fast.download() }, { askModel = false }, destructive = false)
}

private fun DrawScope.drawVoicePill(level: Float, track: Color, fill: Color, ink: Color) {
    val w = size.width; val h = size.height
    val shape = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(w * .34f, w * .34f))) }
    clipPath(shape) {
        drawRect(track)
        val top = h * (1f - level)
        drawRect(fill, topLeft = Offset(0f, top), size = Size(w, h - top))
    }
    // The microphone at the foot, turned a little, as on the phone.
    val pivot = Offset(w * .5f, h * .8f)
    rotate(45f, pivot) {
        val headR = w * .135f
        val head = Offset(pivot.x, pivot.y - w * .2f)
        drawCircle(ink, headR, head)
        val top = head.y + headR * .78f; val bottom = pivot.y + w * .34f
        val a = w * .07f; val b = w * .045f
        drawPath(Path().apply { moveTo(pivot.x - a, top); lineTo(pivot.x + a, top); lineTo(pivot.x + b, bottom); lineTo(pivot.x - b, bottom); close() }, ink)
    }
}
