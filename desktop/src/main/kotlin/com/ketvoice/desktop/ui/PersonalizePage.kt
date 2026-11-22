package com.ketvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.theme.AppFonts
import com.ketvoice.app.theme.AppShape
import com.ketvoice.app.theme.CoverLegibility
import com.ketvoice.app.theme.LyricsLook as LyricsStyle
import com.ketvoice.app.theme.Personalization
import com.ketvoice.app.theme.ThemeCodec
import com.ketvoice.app.theme.ThemeGuard
import com.ketvoice.app.theme.ThemePresets
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.player.LyricsBlur
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet

/**
 * « Personnalisation » on the PC (9.0, the phone's page): the theme's colours, presets and `KT1.` codes, the font, text
 * size, corners and list density, and the lyrics' look. Each change applies to the whole window at once and is saved
 * by the settings store a moment later; the defaults are the desktop's own look.
 */
internal fun LazyListScope.personalizeSettings() {
    item("personalize-heading") { SectionHeader("Personnalisation", Modifier.testTag("personalize-page")) }
    item("personalize-preview") { PersonalizePreview() }
    item("personalize-theme") { SubHeading("Thème"); SettingsCard { ThemeSection() } }
    item("personalize-shape") { SubHeading("Police, taille et arrondis"); SettingsCard { ShapeSection() } }
    item("personalize-lyrics") { SubHeading("Paroles"); SettingsCard { LyricsSection() } }
    item("personalize-reset") { ResetAllRow() }
}

@Composable
private fun SubHeading(title: String) {
    KText(title, Ket27Desk.Type.headline, Modifier.padding(top = 18.dp, bottom = 8.dp, start = 4.dp), weight = FontWeight.SemiBold)
}

// ---------------------------------------------------------------- preview

/** A page, two rows and a button in the chosen theme, font, size, corners and density; then three lines of lyrics. */
@Composable
private fun PersonalizePreview() {
    val ket = LocalKet.current
    Column(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(Ket27Desk.Radius.card)).background(ket.ground.copy(alpha = 1f))
        .border(.5.dp, ket.hairline, RoundedCornerShape(Ket27Desk.Radius.card)).padding(16.dp).testTag("personalize-preview"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KText("Aperçu", Ket27Desk.Type.title3)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Ket27Desk.Radius.menu)).background(ket.card)) {
            PreviewRow("Titre de démonstration", "Artiste", .85f)
            Hairline()
            PreviewRow("Une autre chanson", "Album", .45f)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(CircleShape).background(ket.accent).padding(horizontal = 18.dp, vertical = 8.dp)) {
                KText("Lire", Ket27Desk.Type.subheadline, color = CoverLegibility.onFill(ket.accent), weight = FontWeight.SemiBold)
            }
            KText("Lien en couleur d'accent", Ket27Desk.Type.subheadline, Modifier.padding(start = 14.dp), color = ket.accent)
        }
        LyricsPreview()
    }
}

@Composable
private fun PreviewRow(title: String, sub: String, tint: Float) {
    val ket = LocalKet.current
    Row(Modifier.fillMaxWidth().height(Ket27Desk.Metrics.compactRow).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(Ket27Desk.Radius.thumb)).background(ket.accent.copy(alpha = tint)))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            KText(title, Ket27Desk.Type.subheadline)
            KText(sub, Ket27Desk.Type.footnote, color = ket.secondary)
        }
        com.ketvoice.app.ui.common.Icon(KetIcons.More, null, Modifier.size(18.dp), tint = ket.secondary)
    }
}

/** Three lines as the full player sings them: the sung line half lit, the others dimmed and blurred by distance. */
@Composable
private fun LyricsPreview() {
    val ket = LocalKet.current
    val sung = LyricsStyle.sung(AppSettings.lyricsSung.value, ket.accent, ket.accent, AppSettings.lyricsSungArgb.value)
    val k = LyricsStyle.sizeScale()
    val style = TextStyle(fontFamily = LyricsStyle.font(), fontWeight = FontWeight.Black, fontSize = (22f * k).sp, lineHeight = (28f * k).sp,
        textAlign = LyricsStyle.align(false) ?: androidx.compose.ui.text.style.TextAlign.Start)
    val blur = LyricsStyle.blur()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Ket27Desk.Radius.card)).background(Color(0xFF1B1B20))
        .padding(horizontal = 16.dp, vertical = 14.dp).testTag("personalize-lyrics-preview"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        @Composable fun line(text: String) = androidx.compose.material3.Text(AppText.t(text), Modifier.fillMaxWidth()
            .then(if (blur > 0f) Modifier.blur((LyricsBlur.byDistance(1) * blur).dp) else Modifier), color = Color.White.copy(alpha = .45f), style = style)
        line("Sous la lumière du soir")
        val sample = AppText.t("On chante encore ensemble")
        val cut = sample.lastIndexOf(' ').let { if (it > 0) it + 1 else sample.length }
        androidx.compose.material3.Text(buildAnnotatedString {
            withStyle(SpanStyle(color = sung)) { append(sample.substring(0, cut)) }
            withStyle(SpanStyle(color = Color.White.copy(alpha = .40f))) { append(sample.substring(cut)) }
        }, Modifier.fillMaxWidth().offset(y = -(2.5f * LyricsStyle.motion()).dp).testTag("personalize-sung-line"), style = style)
        line("Jusqu'au bout de la nuit")
    }
}

// ---------------------------------------------------------------- theme

@Composable
private fun ThemeSection() {
    val ket = LocalKet.current
    var editDark by remember { mutableStateOf<Boolean?>(null) }
    val dark = editDark ?: ket.dark
    var editing by remember { mutableStateOf<Personalization.Role?>(null) }
    var importing by remember { mutableStateOf(false) }
    PresetStrip(dark)
    Hairline()
    Segments(listOf("Clair", "Sombre"), if (dark) 1 else 0, "personalize-side", Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { editDark = it == 1 }
    Personalization.Role.entries.forEach { role -> Hairline(); ColourRow(role, dark) { editing = role } }
    ContrastNote(dark)
    Hairline()
    ShareRow()
    Hairline()
    ActionRow("Importer un thème", "Collez un code reçu d'un ami", "personalize-import") { importing = true }
    editing?.let { role -> RolePicker(role, dark) { editing = null } }
    if (importing) ImportThemeDialog { importing = false }
}

@Composable
private fun RolePicker(role: Personalization.Role, dark: Boolean, onClose: () -> Unit) {
    val side = Personalization.colors().side(dark)
    val grounds = if (role == Personalization.Role.Text || role == Personalization.Role.Accent)
        listOf(PickerGround("Fond", side.background), PickerGround("Surfaces", side.surface)) else listOf(PickerGround("Texte", side.text))
    ColorPickerDialog(AppText.t(role.label), Personalization.colour(dark, role), grounds, onPick = { Personalization.setColour(dark, role, it) }, onClose = onClose)
}

@Composable
private fun PresetStrip(dark: Boolean) {
    val current = ThemePresets.matching(ThemeCodec.parse(AppSettings.themeColors.value), AppSettings.accentArgb.value)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp).testTag("personalize-presets"),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ThemePresets.all.forEach { preset -> PresetTile(preset, current?.id == preset.id, dark) }
    }
}

@Composable
private fun PresetTile(preset: com.ketvoice.app.theme.ThemePreset, chosen: Boolean, dark: Boolean) {
    val ket = LocalKet.current
    val side = (preset.colors ?: ThemePresets.ket27).side(dark)
    fun c(rgb: Int) = Color(0xFF000000.toInt() or rgb)
    Column(Modifier.width(84.dp).clip(RoundedCornerShape(Ket27Desk.Radius.artwork)).clickable { Personalization.applyPreset(preset) }
        .padding(4.dp).testTag("preset-${preset.id}"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width = 76.dp, height = 54.dp).clip(RoundedCornerShape(Ket27Desk.Radius.thumb)).background(c(side.background))
            .border(if (chosen) 2.dp else .5.dp, if (chosen) ket.accent else ket.hairline, RoundedCornerShape(Ket27Desk.Radius.thumb)).padding(8.dp)) {
            Box(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(5.dp)).background(c(side.surface)))
            Box(Modifier.align(Alignment.BottomStart).size(width = 28.dp, height = 5.dp).clip(CircleShape).background(c(side.text)))
            Box(Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(Color(preset.accent.toInt())))
        }
        KText(preset.name, Ket27Desk.Type.caption, Modifier.padding(top = 6.dp), color = if (chosen) ket.label else ket.secondary,
            weight = if (chosen) FontWeight.SemiBold else FontWeight.Normal, translate = preset.id != "oled")
    }
}

@Composable
private fun ColourRow(role: Personalization.Role, dark: Boolean, onClick: () -> Unit) {
    val ket = LocalKet.current
    val rgb = Personalization.colour(dark, role)
    Row(Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onClick).padding(horizontal = 16.dp).testTag("personalize-colour-${role.name}"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or rgb)).border(.5.dp, ket.hairline, CircleShape))
        KText(role.label, Ket27Desk.Type.subheadline, Modifier.weight(1f).padding(start = 12.dp))
        KText("#%06X".format(rgb), Ket27Desk.Type.subheadline, color = ket.secondary, translate = false, weight = FontWeight.Normal)
    }
}

/** The guard's verdict on the side being edited: the ratios, and when it had to move a colour, that it did. */
@Composable
private fun ContrastNote(dark: Boolean) {
    val ket = LocalKet.current
    if (!Personalization.custom()) return
    val report = ThemeGuard.report(Personalization.colors().side(dark), AppSettings.accentArgb.value)
    Hairline()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("personalize-contrast")) {
        KText(AppText.t("Texte") + " " + ContrastText.ratio(report.text) + " · " + AppText.t("Accent") + " " + ContrastText.ratio(report.accent),
            Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
        if (report.adjusted) KText("Contraste insuffisant : la couleur est ajustée automatiquement pour rester lisible (WCAG AA).",
            Ket27Desk.Type.footnote, Modifier.padding(top = 4.dp).testTag("personalize-adjusted"), color = warningInk(), maxLines = 3)
    }
}

/** « Partager le thème »: the code goes to the clipboard, with a line saying how to import it. */
@Composable
private fun ShareRow() {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(2_500); copied = false } }
    ActionRow("Partager le thème", if (copied) "Code copié" else "Un code court à envoyer ; il commence par KT1.", "personalize-share") {
        clipboard.setText(AnnotatedString(shareText()))
        copied = true
    }
}

/** What « Partager le thème » copies: the phone's message and the code. */
internal fun shareText(): String =
    AppText.t("Mon thème Ketvoice. Dans Ketvoice, ouvrez Réglages › Personnalisation › Importer un thème :") + "\n" + Personalization.shareCode()

@Composable
private fun ActionRow(title: String, sub: String, tag: String, onClick: () -> Unit) {
    val ket = LocalKet.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            KText(title, Ket27Desk.Type.subheadline)
            KText(sub, Ket27Desk.Type.footnote, Modifier.testTag("$tag-sub"), color = ket.secondary, maxLines = 2)
        }
        com.ketvoice.app.ui.common.Icon(KetIcons.ChevronRight, null, Modifier.size(14.dp), tint = ket.tertiary)
    }
}

@Composable
private fun ImportThemeDialog(onClose: () -> Unit) {
    val ket = LocalKet.current
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Popup(alignment = Alignment.Center, onDismissRequest = onClose,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
        Column(Modifier.width(380.dp).clip(RoundedCornerShape(Ket27Desk.Radius.menu)).background(ket.raised)
            .border(.5.dp, ket.hairline, RoundedCornerShape(Ket27Desk.Radius.menu)).padding(20.dp).testTag("personalize-import-dialog"),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KText("Importer un thème", Ket27Desk.Type.headline, weight = FontWeight.SemiBold)
            KText("Collez le code d'un thème reçu d'un ami. Ses couleurs et son accent remplacent les vôtres.", Ket27Desk.Type.footnote,
                color = ket.secondary, maxLines = 3)
            Box(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(Ket27Desk.Radius.row)).background(ket.fill).padding(10.dp)) {
                if (code.isEmpty()) KText("Code du thème", Ket27Desk.Type.subheadline, color = ket.tertiary)
                BasicTextField(code, { code = it.take(400); wrong = false }, Modifier.fillMaxWidth().textEntry().testTag("personalize-import-code"),
                    textStyle = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.subheadline).copy(color = ket.label),
                    cursorBrush = SolidColor(ket.accent))
            }
            if (wrong) KText("Ce code n'est pas un thème Ketvoice.", Ket27Desk.Type.footnote, Modifier.testTag("personalize-import-wrong"), color = warningInk())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                KetTextLink("Annuler", "personalize-import-cancel", onClose)
                KetTextLink("Importer", "personalize-import-ok") { if (Personalization.import(code)) onClose() else wrong = true }
            }
        }
    }
}

// ---------------------------------------------------------------- font, size, corners, density

@Composable
private fun ShapeSection() {
    FontStrip("Police", AppSettings.appFont.value, follow = false, tag = "personalize-fonts") { AppSettings.appFont.value = it }
    Hairline()
    // The text size is applied on release: while the pointer moves, the page would grow under it.
    var text by remember { mutableIntStateOf(AppSettings.textScale.value) }
    LaunchedEffect(AppSettings.textScale.value) { text = AppSettings.textScale.value }
    PercentSlider("Taille du texte", text, AppShape.TEXT_MIN..AppShape.TEXT_MAX, 5, "personalize-text-size",
        onChange = { text = it }, onDone = { AppSettings.textScale.value = text })
    Hairline()
    PercentSlider("Arrondis", AppSettings.cornerScale.value, AppShape.CORNER_MIN..AppShape.CORNER_MAX, 25, "personalize-corners",
        valueLabel = cornerLabel(AppSettings.cornerScale.value), onChange = { AppSettings.cornerScale.value = it })
    Hairline()
    LabeledSegments("Densité des listes", AppShape.densityLabels, AppSettings.listDensity.value, "personalize-density") { AppSettings.listDensity.value = it }
}

internal fun cornerLabel(percent: Int) = when {
    percent == 0 -> "Carrés"
    percent < 100 -> "Discrets"
    percent == 100 -> "Ket 27"
    percent < 160 -> "Arrondis"
    else -> "Très arrondis"
}

/** The fonts, each name written in its own face; [follow] adds « Comme l'app » first (the lyrics' choice). */
@Composable
private fun FontStrip(title: String, selected: String, follow: Boolean, tag: String, onPick: (String) -> Unit) {
    KText(title, Ket27Desk.Type.subheadline, Modifier.padding(start = 16.dp, top = 12.dp))
    val choices = (if (follow) listOf("" to AppText.t("Comme l'app")) else emptyList()) + AppFonts.all.map { it.id to it.name }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp).testTag(tag), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { (id, name) -> FontTile(id, name, id == selected, if (id.isEmpty()) Ket27Desk.font else AppFonts.family(id), "$tag-${id.ifEmpty { "app" }}") { onPick(id) } }
    }
}

@Composable
private fun FontTile(id: String, name: String, chosen: Boolean, family: FontFamily, tag: String, onClick: () -> Unit) {
    val ket = LocalKet.current
    Column(Modifier.widthIn(min = 76.dp).clip(RoundedCornerShape(Ket27Desk.Radius.thumb)).background(ket.label.copy(alpha = if (chosen) .10f else .05f))
        .border(if (chosen) 2.dp else 0.dp, if (chosen) ket.accent else Color.Transparent, RoundedCornerShape(Ket27Desk.Radius.thumb))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp).testTag(tag), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.Text("Aa", color = ket.label, style = TextStyle(fontFamily = family, fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
        androidx.compose.material3.Text(name, color = if (chosen) ket.label else ket.secondary, maxLines = 1,
            style = Ket27Desk.Type.caption.copy(fontFamily = family))
    }
}

/** A titled slider in whole percent over [range], landing on multiples of [step]; [onDone] when the pointer lets go. */
@Composable
private fun PercentSlider(title: String, value: Int, range: IntRange, step: Int, tag: String, valueLabel: String? = null, zeroLabel: String? = null,
                          onChange: (Int) -> Unit, onDone: () -> Unit = {}) {
    val ket = LocalKet.current
    val shown = if (value == 0 && zeroLabel != null) AppText.t(zeroLabel) else "$value %" + (valueLabel?.let { " · " + AppText.t(it) } ?: "")
    val span = (range.last - range.first).toFloat()
    fun at(f: Float) = (range.first + Math.round(f * span / step) * step).coerceIn(range.first, range.last)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KText(title, Ket27Desk.Type.subheadline, Modifier.weight(1f))
            KText(shown, Ket27Desk.Type.subheadline, Modifier.testTag("$tag-value"), color = ket.secondary, translate = false, weight = FontWeight.Normal)
        }
        ThinSlider((value - range.first) / span, { onChange(at(it)) }, Modifier.fillMaxWidth().padding(top = 6.dp), onDone = { onChange(at(it)); onDone() }, tag = tag,
            color = ket.accent)
    }
}

@Composable
private fun LabeledSegments(title: String, labels: List<String>, selected: Int, tag: String, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        KText(title, Ket27Desk.Type.subheadline, Modifier.weight(1f))
        Segments(labels, selected, tag, onSelect = onSelect)
    }
}

/** A segmented control in the Ket 27 manner: the chosen segment raised on a quiet track. */
@Composable
private fun Segments(labels: List<String>, selected: Int, tag: String, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val ket = LocalKet.current
    Row(modifier.clip(RoundedCornerShape(Ket27Desk.Radius.row)).background(ket.fill).padding(2.dp).testTag(tag)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(Modifier.clip(RoundedCornerShape(Ket27Desk.Radius.row)).background(if (on) ket.raised else Color.Transparent)
                .clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 5.dp).testTag("$tag:$i"), contentAlignment = Alignment.Center) {
                KText(label, Ket27Desk.Type.footnote, color = if (on) ket.label else ket.secondary, weight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

// ---------------------------------------------------------------- lyrics

@Composable
private fun LyricsSection() {
    var picking by remember { mutableStateOf(false) }
    FontStrip("Police des paroles", AppSettings.lyricsFont.value, follow = true, tag = "personalize-lyrics-fonts") { AppSettings.lyricsFont.value = it }
    Hairline()
    PercentSlider("Taille du texte des paroles", AppSettings.lyricsScale.value, LyricsStyle.SIZE_MIN..LyricsStyle.SIZE_MAX, 5, "personalize-lyrics-size",
        onChange = { AppSettings.lyricsScale.value = it })
    Hairline()
    LabeledSegments("Alignement", listOf("Gauche", "Centre"), AppSettings.lyricsAlign.value, "personalize-lyrics-align") { AppSettings.lyricsAlign.value = it }
    Hairline()
    LabeledSegments("Couleur des mots chantés", LyricsStyle.sungLabels, AppSettings.lyricsSung.value, "personalize-lyrics-sung") {
        AppSettings.lyricsSung.value = it
        if (it == LyricsStyle.SUNG_CUSTOM) picking = true
    }
    if (AppSettings.lyricsSung.value == LyricsStyle.SUNG_CUSTOM) {
        Hairline()
        ActionRow("Couleur personnalisée", "#%06X".format(AppSettings.lyricsSungArgb.value and 0xFFFFFF), "personalize-lyrics-custom") { picking = true }
    }
    Hairline()
    PercentSlider("Intensité du flou", if (AppSettings.lyricsBlur.value) AppSettings.lyricsBlurLevel.value else 0, 0..100, 10, "personalize-lyrics-blur",
        zeroLabel = "Désactivé", onChange = { AppSettings.lyricsBlurLevel.value = it; AppSettings.lyricsBlur.value = it > 0 })
    Hairline()
    PercentSlider("Mouvement des mots", AppSettings.lyricsMotion.value, 0..100, 10, "personalize-lyrics-motion",
        zeroLabel = "Immobiles", onChange = { AppSettings.lyricsMotion.value = it })
    if (picking) ColorPickerDialog(AppText.t("Couleur des mots chantés"), (AppSettings.lyricsSungArgb.value and 0xFFFFFF).toInt(),
        listOf(PickerGround("Fond des paroles", 0x26262A)), onPick = { AppSettings.lyricsSungArgb.value = 0xFF000000L or it.toLong() },
        onClose = { picking = false })
}

@Composable
private fun ResetAllRow() {
    var asking by remember { mutableStateOf(false) }
    if (!Personalization.changed() && AppSettings.accentArgb.value == ThemePresets.DEFAULT_ACCENT) return
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center) {
        Box(Modifier.clip(RoundedCornerShape(Ket27Desk.Radius.row)).clickable { asking = true }.padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("personalize-reset")) {
            KText("Tout réinitialiser", Ket27Desk.Type.subheadline, color = destructiveRed, weight = FontWeight.SemiBold)
        }
    }
    if (asking) ConfirmDialog(AppText.t("Tout réinitialiser ?"),
        AppText.t("Couleurs, accent, police, tailles, arrondis et paroles reprennent l'apparence de Ketvoice."),
        "Réinitialiser", onConfirm = { Personalization.resetAll() }, onDismiss = { asking = false })
}
