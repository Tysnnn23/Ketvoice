package com.ketvoice.desktop.social

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.social.ProfileRules
import com.ketvoice.app.together.Names
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.desktop.ui.KText
import com.ketvoice.desktop.ui.textEntry
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet

/** The two symbols the social features add, on KetIcons' 24 grid and stroke. */
internal object SocialGlyphs {
    private fun glyph(name: String, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name = "Social.$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = KetIcons.STROKE, strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round, pathBuilder = draw).build()

    /** « Ensemble »: two people side by side. */
    val Together: ImageVector by lazy { glyph("Together") {
        moveTo(9f, 11f); arcTo(3f, 3f, 0f, true, true, 9f, 5f); arcTo(3f, 3f, 0f, true, true, 9f, 11f)
        moveTo(3.5f, 19f); curveTo(3.5f, 15.8f, 6f, 13.5f, 9f, 13.5f); curveTo(12f, 13.5f, 14.5f, 15.8f, 14.5f, 19f)
        moveTo(16f, 5.3f); arcTo(2.6f, 2.6f, 0f, true, true, 16f, 10.5f)
        moveTo(16.5f, 13.6f); curveTo(18.8f, 14f, 20.5f, 16.1f, 20.5f, 19f)
    } }

    /** « Activité des amis »: a person and a sound wave. */
    val Friends: ImageVector by lazy { glyph("Friends") {
        moveTo(9f, 11f); arcTo(3.2f, 3.2f, 0f, true, true, 9f, 4.6f); arcTo(3.2f, 3.2f, 0f, true, true, 9f, 11f)
        moveTo(3f, 19.5f); curveTo(3f, 16f, 5.7f, 13.6f, 9f, 13.6f); curveTo(10.6f, 13.6f, 12f, 14.1f, 13f, 15f)
        moveTo(16f, 13f); lineTo(16f, 19f); moveTo(19f, 10.5f); lineTo(19f, 21f); moveTo(22f, 14f); lineTo(22f, 18f)
    } }
}

/** A round profile picture: the photo when it may be shown, else the initials on the profile's accent. */
@Composable
internal fun ProfileAvatar(handle: String?, name: String, accent: String?, hasPhoto: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val ket = LocalKet.current
    var image by remember(handle, hasPhoto) { mutableStateOf<ImageBitmap?>(handle?.let { Pictures.cached(it, "avatar") }) }
    if (handle != null && hasPhoto) LaunchedEffect(handle) { image = Pictures.load(handle, "avatar") }
    val tint = ProfileRules.accentArgb(accent)?.let { Color(it) } ?: ket.accent
    Box(modifier.size(size).clip(CircleShape).background(tint.copy(alpha = .85f)), contentAlignment = Alignment.Center) {
        val shown = image
        if (shown != null) Image(shown, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else KText(Names.initials(name.ifBlank { handle.orEmpty() }), Ket27Desk.Type.headline.copy(fontSize = Ket27Desk.Type.headline.fontSize * (size.value / 48f)),
            color = Color.White, weight = FontWeight.SemiBold, translate = false)
    }
}

/** A small action in a row (« Rejoindre », « Accepter »…). */
@Composable
internal fun SmallPill(label: String, tag: String, filled: Boolean = false, onClick: () -> Unit) {
    val ket = LocalKet.current
    Box(Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(if (filled) ket.accent else ket.fill).clickable(onClick = onClick)
        .padding(horizontal = 12.dp).testTag(tag), contentAlignment = Alignment.Center) {
        KText(label, Ket27Desk.Type.footnote, color = if (filled) Color.White else ket.accent, weight = FontWeight.SemiBold)
    }
}

/** A one-line field on a card, with its hint, that sends on Enter. */
@Composable
internal fun FieldLine(value: String, hint: String, tag: String, max: Int = 200, onDone: (() -> Unit)? = null, onChange: (String) -> Unit) {
    val ket = LocalKet.current
    Box(Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(10.dp)).background(ket.fill).padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) KText(hint, Ket27Desk.Type.subheadline, color = ket.tertiary)
        BasicTextField(value, { onChange(it.take(max)) }, Modifier.fillMaxWidth().textEntry().testTag(tag), singleLine = true,
            textStyle = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.subheadline).copy(color = ket.label),
            cursorBrush = SolidColor(ket.accent),
            keyboardOptions = KeyboardOptions(imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }))
    }
}

/** A short message under an action (French, translated). */
@Composable
internal fun NoticeLine(text: String?, modifier: Modifier = Modifier) {
    if (text.isNullOrBlank()) return
    val ket = LocalKet.current
    Row(modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        KText(text, Ket27Desk.Type.footnote, color = ket.secondary, maxLines = 3)
    }
}

/** A card of the right panel: rounded, on the panel's card colour. */
@Composable
internal fun PanelCard(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val ket = LocalKet.current
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth().clip(RoundedCornerShape(Ket27Desk.Radius.menu)).background(ket.card).padding(14.dp),
        content = content)
}

internal fun Modifier.clickableIf(on: Boolean, action: () -> Unit): Modifier = if (on) clickable(onClick = action) else this
