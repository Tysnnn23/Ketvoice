package com.ketvoice.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.theme.CoverLegibility
import com.ketvoice.app.theme.Hsv
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.KetTextField
import com.ketvoice.app.ui.common.Text

/** A colour as the picker shows it beside its contrast: the ground it is read on and its name. */
internal data class PickerGround(val name: String, val rgb: Int)

/** Calm swatches to start from (no neon): the iOS 27 system colours, toned for text and grounds. */
private val swatches = listOf(0x000000, 0x1C1C1E, 0x3A3A3C, 0x8E8E93, 0xF2F2F7, 0xFFFFFF,
    0xFF2D55, 0xFF9F0A, 0xFFCC00, 0x34C759, 0x00C7BE, 0x0A84FF, 0x5E5CE6, 0xBF5AF2, 0xA2845E, 0xF4EFE6)

/**
 * The colour picker of « Personnalisation » (Ket 27): a saturation/brightness field, a hue rail, calm swatches and a
 * hex field, with the contrast of the colour on each ground it will be read on ([grounds]) — AA (4.5:1) is marked;
 * below it the theme moves text just enough, which the page says.
 */
@Composable
internal fun ColorPickerSheet(title: String, initial: Int, grounds: List<PickerGround>, onPick: (Int) -> Unit, onClose: () -> Unit) {
    var hsv by remember { mutableStateOf(Hsv.of(initial)) }
    var hex by remember { mutableStateOf("#%06X".format(initial and 0xFFFFFF)) }
    fun take(next: Hsv) { hsv = next; hex = "#%06X".format(next.toRgb()) }
    SettingsSheet(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = com.ketvoice.app.ui.common.sheetMaxHeight()).verticalScroll(rememberScrollState())
            .padding(20.dp).testTag("color-picker"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingsSheetHeader(title, "Le contraste avec le fond est vérifié en direct.", onClose)
            SaturationField(hsv, ::take)
            HueRail(hsv, ::take)
            SwatchGrid(hsv.toRgb()) { take(Hsv.of(it)) }
            KetTextField(hex, { text -> hex = text.take(7); Hsv.parseHex(text)?.let { hsv = Hsv.of(it) } }, "Code hexadécimal",
                Modifier.testTag("color-picker-hex"))
            ContrastReadout(hsv.toRgb(), grounds)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SettingsAction("Annuler", onClose)
                SettingsAction("Appliquer", { onPick(hsv.toRgb()); onClose() }, primary = true, modifier = Modifier.testTag("color-picker-apply"))
            }
        }
    }
}

@Composable
private fun SaturationField(hsv: Hsv, onChange: (Hsv) -> Unit) {
    val label = AppText.t("Saturation et luminosité")
    val current by rememberUpdatedState(hsv)
    val change by rememberUpdatedState(onChange)
    val hue = Color(0xFF000000.toInt() or Hsv(hsv.h, 1f, 1f).toRgb())
    fun at(o: Offset, w: Float, h: Float) = change(current.copy(s = (o.x / w).coerceIn(0f, 1f), v = 1f - (o.y / h).coerceIn(0f, 1f)))
    Box(Modifier.fillMaxWidth().height(176.dp).clip(RoundedCornerShape(Ket27.Radius.artwork))
        .semantics { contentDescription = label; stateDescription = "${(hsv.s * 100).toInt()} % · ${(hsv.v * 100).toInt()} %" }
        .pointerInput(Unit) { detectTapGestures { at(it, size.width.toFloat(), size.height.toFloat()) } }
        .pointerInput(Unit) { detectDragGestures { change, _ -> at(change.position, size.width.toFloat(), size.height.toFloat()) } }
        .drawBehind {
            drawRect(Brush.horizontalGradient(listOf(Color.White, hue)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val c = Offset(hsv.s * size.width, (1f - hsv.v) * size.height)
            drawCircle(Color.Black.copy(alpha = .35f), 12.dp.toPx(), c, style = Stroke(4.dp.toPx()))
            drawCircle(Color.White, 11.dp.toPx(), c, style = Stroke(2.5.dp.toPx()))
        }.testTag("color-picker-field"))
}

@Composable
private fun HueRail(hsv: Hsv, onChange: (Hsv) -> Unit) {
    val label = AppText.t("Teinte")
    val current by rememberUpdatedState(hsv)
    val change by rememberUpdatedState(onChange)
    val stops = remember { (0..6).map { Color(0xFF000000.toInt() or Hsv(it * 60f, 1f, 1f).toRgb()) } }
    fun at(x: Float, w: Float) = change(current.copy(h = (x / w).coerceIn(0f, 1f) * 360f, s = current.s.coerceAtLeast(.05f),
        v = current.v.coerceAtLeast(.1f)))
    Box(Modifier.fillMaxWidth().height(30.dp).clip(CircleShape)
        .semantics { contentDescription = label; stateDescription = "${hsv.h.toInt()}°" }
        .pointerInput(Unit) { detectTapGestures { at(it.x, size.width.toFloat()) } }
        .pointerInput(Unit) { detectDragGestures { change, _ -> at(change.position.x, size.width.toFloat()) } }
        .drawBehind {
            drawRect(Brush.horizontalGradient(stops))
            val c = Offset(hsv.h / 360f * size.width, size.height / 2f)
            drawCircle(Color.Black.copy(alpha = .35f), 12.dp.toPx(), c, style = Stroke(4.dp.toPx()))
            drawCircle(Color.White, 11.dp.toPx(), c, style = Stroke(2.5.dp.toPx()))
        }.testTag("color-picker-hue"))
}

@Composable
private fun SwatchGrid(selected: Int, onPick: (Int) -> Unit) {
    val theme = LocalTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        swatches.chunked(8).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { rgb ->
                    val chosen = rgb == selected
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or rgb))
                        .border(if (chosen) 2.5.dp else .5.dp, if (chosen) theme.accent else theme.hairline, CircleShape)
                        .clickable(role = Role.Button) { onPick(rgb) }
                        .semantics { contentDescription = "#%06X".format(rgb) })
                }
            }
        }
    }
}

/** « 7,2:1 · AA » on each ground, or a warning below AA. */
@Composable
private fun ContrastReadout(rgb: Int, grounds: List<PickerGround>) {
    val theme = LocalTheme.current
    val color = Color(0xFF000000.toInt() or rgb)
    Column(Modifier.fillMaxWidth().testTag("color-picker-contrast"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        grounds.forEach { ground ->
            val bg = Color(0xFF000000.toInt() or ground.rgb)
            val ratio = CoverLegibility.contrast(color, bg)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 54.dp, height = 30.dp).clip(RoundedCornerShape(8.dp)).background(bg)
                    .border(.5.dp, theme.hairline, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    Text("Aa", localize = false, color = color, style = Ket27.Type.headline)
                }
                Text(AppText.t(ground.name) + " · " + ContrastText.ratio(ratio) + " · " + AppText.t(ContrastText.verdict(ratio)),
                    localize = false, color = if (ratio >= CoverLegibility.TEXT) theme.secondary else warningInk(),
                    style = Ket27.Type.footnote, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

/** How a contrast ratio is written: « 4,6:1 » and whether it passes AA for text. */
internal object ContrastText {
    fun ratio(r: Float): String = String.format(java.util.Locale.getDefault(), "%.1f:1", r)
    fun verdict(r: Float): String = when {
        r >= 7f -> "AAA"
        r >= CoverLegibility.TEXT -> "AA"
        else -> "Contraste faible"
    }
}
