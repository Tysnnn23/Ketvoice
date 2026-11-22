package com.ketvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ketvoice.app.data.AppText
import com.ketvoice.app.theme.CoverLegibility
import com.ketvoice.app.theme.Hsv
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet

/** A ground a picked colour is read on, and its name (French, translated when shown). */
data class PickerGround(val name: String, val rgb: Int)

/** How a contrast ratio is written (« 4.6:1 ») and whether it passes AA for text. */
object ContrastText {
    fun ratio(r: Float): String = String.format(java.util.Locale.ROOT, "%.1f:1", r)
    fun verdict(r: Float): String = when {
        r >= 7f -> "AAA"
        r >= CoverLegibility.TEXT -> "AA"
        else -> "Contraste faible"
    }
}

/** Calm swatches to start from (the phone's): iOS 27's system colours, toned for text and grounds. */
private val swatches = listOf(0x000000, 0x1C1C1E, 0x3A3A3C, 0x8E8E93, 0xF2F2F7, 0xFFFFFF,
    0xFF2D55, 0xFF9F0A, 0xFFCC00, 0x34C759, 0x00C7BE, 0x0A84FF, 0x5E5CE6, 0xBF5AF2, 0xA2845E, 0xF4EFE6)

private fun opaque(rgb: Int) = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))

/**
 * The colour picker of « Personnalisation », as on the phone: a saturation/brightness field, a hue rail, swatches and a
 * hex field, with the contrast of the colour on each ground it will be read on ([grounds]). Escape or a click outside
 * closes without changing anything.
 */
@Composable
fun ColorPickerDialog(title: String, initial: Int, grounds: List<PickerGround>, onPick: (Int) -> Unit, onClose: () -> Unit) {
    val ket = LocalKet.current
    var hsv by remember { mutableStateOf(Hsv.of(initial)) }
    var hex by remember { mutableStateOf("#%06X".format(initial and 0xFFFFFF)) }
    fun take(next: Hsv) { hsv = next; hex = "#%06X".format(next.toRgb()) }
    Popup(alignment = Alignment.Center, onDismissRequest = onClose, properties = PopupProperties(focusable = true),
        onKeyEvent = { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onClose(); true } else false }) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (ket.dark) .45f else .25f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClose), contentAlignment = Alignment.Center) {
            Column(Modifier.width(360.dp).shadow(24.dp, RoundedCornerShape(Ket27Desk.Radius.menu)).clip(RoundedCornerShape(Ket27Desk.Radius.menu))
                .background(ket.raised).clickable(remember { MutableInteractionSource() }, null) {}.padding(20.dp).testTag("color-picker"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KText(title, Ket27Desk.Type.headline, weight = FontWeight.SemiBold, translate = false)
                KText("Le contraste avec le fond est vérifié en direct.", Ket27Desk.Type.footnote, color = ket.secondary, maxLines = 2)
                SaturationField(hsv, ::take)
                HueRail(hsv, ::take)
                SwatchGrid(hsv.toRgb()) { take(Hsv.of(it)) }
                HexField(hex) { text -> hex = text.take(7); Hsv.parseHex(text)?.let { hsv = Hsv.of(it) } }
                ContrastReadout(hsv.toRgb(), grounds)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    PickerButton("Annuler", ket.label, "color-picker-cancel", onClose)
                    PickerButton("Appliquer", ket.accent, "color-picker-apply") { onPick(hsv.toRgb()); onClose() }
                }
            }
        }
    }
}

@Composable
private fun SaturationField(hsv: Hsv, onChange: (Hsv) -> Unit) {
    val current by rememberUpdatedState(hsv)
    val change by rememberUpdatedState(onChange)
    val hue = opaque(Hsv(hsv.h, 1f, 1f).toRgb())
    fun at(o: Offset, w: Float, h: Float) = change(current.copy(s = (o.x / w).coerceIn(0f, 1f), v = 1f - (o.y / h).coerceIn(0f, 1f)))
    Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(Ket27Desk.Radius.thumb))
        .pointerInput(Unit) { detectTapGestures { at(it, size.width.toFloat(), size.height.toFloat()) } }
        .pointerInput(Unit) { detectDragGestures { change, _ -> at(change.position, size.width.toFloat(), size.height.toFloat()) } }
        .drawBehind {
            drawRect(Brush.horizontalGradient(listOf(Color.White, hue)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val c = Offset(hsv.s * size.width, (1f - hsv.v) * size.height)
            drawCircle(Color.Black.copy(alpha = .35f), 10.dp.toPx(), c, style = Stroke(3.dp.toPx()))
            drawCircle(Color.White, 9.dp.toPx(), c, style = Stroke(2.dp.toPx()))
        }.testTag("color-picker-field"))
}

@Composable
private fun HueRail(hsv: Hsv, onChange: (Hsv) -> Unit) {
    val current by rememberUpdatedState(hsv)
    val change by rememberUpdatedState(onChange)
    val stops = remember { (0..6).map { opaque(Hsv(it * 60f, 1f, 1f).toRgb()) } }
    fun at(x: Float, w: Float) = change(current.copy(h = (x / w).coerceIn(0f, 1f) * 360f, s = current.s.coerceAtLeast(.05f), v = current.v.coerceAtLeast(.1f)))
    Box(Modifier.fillMaxWidth().height(22.dp).clip(CircleShape)
        .pointerInput(Unit) { detectTapGestures { at(it.x, size.width.toFloat()) } }
        .pointerInput(Unit) { detectDragGestures { change, _ -> at(change.position.x, size.width.toFloat()) } }
        .drawBehind {
            drawRect(Brush.horizontalGradient(stops))
            val c = Offset(hsv.h / 360f * size.width, size.height / 2f)
            drawCircle(Color.Black.copy(alpha = .35f), 10.dp.toPx(), c, style = Stroke(3.dp.toPx()))
            drawCircle(Color.White, 9.dp.toPx(), c, style = Stroke(2.dp.toPx()))
        }.testTag("color-picker-hue"))
}

@Composable
private fun SwatchGrid(selected: Int, onPick: (Int) -> Unit) {
    val ket = LocalKet.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        swatches.chunked(8).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { rgb ->
                    val chosen = rgb == selected
                    Box(Modifier.size(28.dp).clip(CircleShape).background(opaque(rgb))
                        .border(if (chosen) 2.5.dp else .5.dp, if (chosen) ket.accent else ket.hairline, CircleShape)
                        .clickable { onPick(rgb) }.testTag("swatch:%06X".format(rgb)))
                }
            }
        }
    }
}

@Composable
private fun HexField(hex: String, onChange: (String) -> Unit) {
    val ket = LocalKet.current
    Row(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(Ket27Desk.Radius.row)).background(ket.fill).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        KText("Code hexadécimal", Ket27Desk.Type.footnote, Modifier.width(140.dp), color = ket.secondary)
        BasicTextField(hex, onChange, Modifier.weight(1f).textEntry().testTag("color-picker-hex"), singleLine = true,
            textStyle = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.subheadline).copy(color = ket.label),
            cursorBrush = SolidColor(ket.accent))
    }
}

/** « 7.2:1 · AA » on each ground, in the warning colour below AA. */
@Composable
private fun ContrastReadout(rgb: Int, grounds: List<PickerGround>) {
    val ket = LocalKet.current
    val color = opaque(rgb)
    Column(Modifier.fillMaxWidth().testTag("color-picker-contrast"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        grounds.forEach { ground ->
            val bg = opaque(ground.rgb)
            val ratio = CoverLegibility.contrast(color, bg)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 48.dp, height = 26.dp).clip(RoundedCornerShape(6.dp)).background(bg)
                    .border(.5.dp, ket.hairline, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    KText("Aa", Ket27Desk.Type.headline, color = color, translate = false)
                }
                Spacer(Modifier.width(10.dp))
                KText(AppText.t(ground.name) + " · " + ContrastText.ratio(ratio) + " · " + AppText.t(ContrastText.verdict(ratio)), Ket27Desk.Type.footnote,
                    color = if (ratio >= CoverLegibility.TEXT) ket.secondary else warningInk(), translate = false)
            }
        }
    }
}

/** The red of a warning, made readable (AA) on the window's ground and cards whatever the theme. */
@Composable
internal fun warningInk(): Color {
    val ket = LocalKet.current
    return CoverLegibility.readableAccent(destructiveRed, ket.ground.copy(alpha = 1f), ket.raised)
}

@Composable
private fun PickerButton(label: String, color: Color, tag: String, onClick: () -> Unit) {
    val ket = LocalKet.current
    Box(Modifier.height(32.dp).clip(RoundedCornerShape(Ket27Desk.Radius.row)).background(ket.fill).clickable(onClick = onClick)
        .padding(horizontal = 16.dp).testTag(tag), contentAlignment = Alignment.Center) {
        KText(label, Ket27Desk.Type.subheadline, color = color, weight = FontWeight.SemiBold)
    }
}
