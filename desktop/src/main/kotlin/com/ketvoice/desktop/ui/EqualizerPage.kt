package com.ketvoice.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.player.EqualizerProfile
import com.ketvoice.desktop.audio.DesktopEqualizer
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlin.math.roundToInt

/** How a band's slider turns a drag into decibels (−12…+12, whole steps), kept apart so that it can be tested. */
internal object EqLogic {
    const val RANGE = 12
    /** The gain at [y] pixels from the top of a slider [height] pixels tall. */
    fun gainAt(y: Float, height: Float): Int =
        if (height <= 0f) 0 else ((.5f - y / height) * 2f * RANGE).roundToInt().coerceIn(-RANGE, RANGE)
    fun label(hz: Int): String = if (hz >= 1000) "${hz / 1000}k" else "$hz"
    /** The preset these gains are, or null (« Personnalisé »). */
    fun preset(gains: List<Int>): String? = EqualizerProfile.presets.entries.firstOrNull { it.value == EqualizerProfile.normalize(gains) }?.key
}

/**
 * Settings › Égaliseur: the phone's ten bands (31 Hz to 16 kHz) as a graphic equalizer, in the manner of Apple
 * Music on the Mac: a switch, the presets of [EqualizerProfile], one vertical slider per band. It plays through
 * [DesktopEqualizer] (the engine's ten peaking filters) as the sliders move.
 */
@Composable
fun EqualizerPage() {
    val ket = LocalKet.current
    val enabled = AppSettings.equalizerEnabled.value
    val gains = EqualizerProfile.normalize(AppSettings.equalizerGains.value)
    fun setGains(g: List<Int>) { AppSettings.equalizerGains.value = g; DesktopEqualizer.apply() }
    Page("Égaliseur", "equalizer") {
        item {
            SettingsCard {
                ToggleRow("Activer l'égaliseur", enabled, "equalizer") { AppSettings.equalizerEnabled.value = it; DesktopEqualizer.apply() }
            }
        }
        item { SectionHeader("Préréglages") }
        item {
            Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else .5f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val current = EqLogic.preset(gains)
                for ((name, values) in EqualizerProfile.presets) {
                    val selected = name == current
                    Box(Modifier.height(30.dp).clip(RoundedCornerShape(15.dp))
                        .background(if (selected) ket.accent.copy(alpha = .16f) else ket.fill)
                        .clickable(enabled = enabled) { setGains(values) }.padding(horizontal = 14.dp).testTag("eq-preset:$name"),
                        contentAlignment = Alignment.Center) {
                        KText(name, Ket27Desk.Type.footnote, color = if (selected) ket.accent else ket.label,
                            weight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
                if (current == null) Box(Modifier.height(30.dp).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                    KText("Personnalisé", Ket27Desk.Type.footnote, color = ket.secondary, weight = FontWeight.Normal)
                }
            }
        }
        item { Gap(18.dp) }
        item {
            SettingsCard {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, top = 18.dp, bottom = 12.dp).alpha(if (enabled) 1f else .5f),
                    verticalAlignment = Alignment.Top) {
                    // The scale, centred on the top, the zero and the bottom of the sliders' travel (16 dp of gain label,
                    // 6 dp of padding, then 9 dp to the travel's end; caption lines are 16 dp tall).
                    Column(Modifier.padding(top = 23.dp).width(34.dp).height((SLIDER_H - 18 + 16).dp), verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End) {
                        for (db in listOf("+12", "0", "−12")) KText(db, Ket27Desk.Type.caption, color = ket.tertiary, translate = false, weight = FontWeight.Normal)
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                        EqualizerProfile.frequencies.forEachIndexed { i, hz ->
                            BandSlider(hz, gains[i], enabled) { g -> if (g != gains[i]) setGains(gains.toMutableList().apply { set(i, g) }) }
                        }
                    }
                }
                Hairline()
                Row(Modifier.fillMaxWidth().height(44.dp).clickable(enabled = enabled) { setGains(List(10) { 0 }) }
                    .padding(horizontal = 16.dp).testTag("eq-reset"), verticalAlignment = Alignment.CenterVertically) {
                    KText("Réinitialiser", Ket27Desk.Type.subheadline, color = ket.accent, weight = FontWeight.Normal)
                }
            }
        }
        item { Gap(18.dp) }
        headphoneEq()
    }
}

private const val SLIDER_H = 168

/** One band: its gain on top, a vertical travel with the zero line, a round thumb, its frequency under it. */
@Composable
private fun BandSlider(hz: Int, gain: Int, enabled: Boolean, onGain: (Int) -> Unit) {
    val ket = LocalKet.current
    val label = EqLogic.label(hz) + " Hz"
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(40.dp)) {
        KText((if (gain > 0) "+" else "") + gain, Ket27Desk.Type.caption, color = if (gain != 0) ket.label else ket.tertiary,
            translate = false, weight = FontWeight.Medium)
        Canvas(Modifier.padding(vertical = 6.dp).size(28.dp, SLIDER_H.dp).testTag("eq-band:$hz")
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(gain.toFloat(), -12f..12f, 23)
                if (enabled) setProgress { onGain(it.roundToInt().coerceIn(-12, 12)); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures { change, _ -> change.consume(); onGain(EqLogic.gainAt(change.position.y, size.height.toFloat())) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onDoubleTap = { onGain(0) }) { onGain(EqLogic.gainAt(it.y, size.height.toFloat())) }
            }) {
            val x = size.width / 2f
            val pad = 9.dp.toPx()
            val travel = size.height - 2 * pad
            fun y(db: Int) = pad + travel * (.5f - db / (2f * EqLogic.RANGE))
            val line = 4.dp.toPx()
            drawLine(ket.fill, Offset(x, pad), Offset(x, size.height - pad), line, StrokeCap.Round)
            drawLine(ket.hairline, Offset(x - 8.dp.toPx(), y(0)), Offset(x + 8.dp.toPx(), y(0)), 1.dp.toPx())
            if (gain != 0) drawLine(ket.accent, Offset(x, y(0)), Offset(x, y(gain)), line, StrokeCap.Round)
            val r = 9.dp.toPx()
            drawCircle(Color.Black.copy(alpha = .18f), r + 1.dp.toPx(), Offset(x, y(gain) + 1.dp.toPx()))
            drawCircle(Color.White, r, Offset(x, y(gain)))
        }
        KText(EqLogic.label(hz), Ket27Desk.Type.caption, color = ket.secondary, translate = false, weight = FontWeight.Normal)
    }
}
