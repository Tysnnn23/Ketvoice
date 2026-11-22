package com.ketvoice.app.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.theme.AppFonts
import com.ketvoice.app.theme.AppShape
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.LyricsLook
import com.ketvoice.app.theme.Personalization
import com.ketvoice.app.theme.ThemeGuard
import com.ketvoice.app.theme.ThemePresets
import com.ketvoice.app.ui.common.AppAlertDialog
import com.ketvoice.app.ui.common.GlassSlider
import com.ketvoice.app.ui.common.GlassSegmentedControl
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.KetTextField
import com.ketvoice.app.ui.common.Text

/**
 * « Personnalisation » (owner, 2026-11-22: « rends complètement customisable l'app (pas à 100 % mais à 40-70 %) »):
 * the theme's colours and presets with share codes, the font, text size, corners and list density, and the lyrics'
 * look — each applied to the whole app as it is changed, with a live preview at the top. Ket 27 only.
 */
@Composable
internal fun PersonalizePage() {
    Column(Modifier.fillMaxWidth().padding(horizontal = Ket27.Metrics.margin).testTag("personalize-page")) {
        PersonalizePreview(Modifier.padding(top = 8.dp))
        SettingsGroupHeading("Thème")
        Column(Modifier.fillMaxWidth().settingsGroup()) { ThemeSection() }
        SettingsGroupHeading("Police, taille et arrondis")
        Column(Modifier.fillMaxWidth().settingsGroup()) { ShapeSection() }
        SettingsGroupHeading("Paroles")
        Column(Modifier.fillMaxWidth().settingsGroup()) { LyricsSection() }
        ResetAllRow()
    }
}

private fun save() = SettingsStore.save()

/** The red of a warning, made readable (AA) on the page and its groups whatever the theme. */
@Composable
internal fun warningInk(): Color {
    val theme = LocalTheme.current
    return com.ketvoice.app.theme.CoverLegibility.readableAccent(com.ketvoice.app.ui.common.DESTRUCTIVE, theme.background, theme.card)
}

// ---------------------------------------------------------------- preview

/** A page, two rows and a button in the chosen theme, font, size, corners and density; then three lines of lyrics. */
@Composable
internal fun PersonalizePreview(modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(Ket27.Radius.card)).background(theme.background)
        .border(.5.dp, theme.hairline, RoundedCornerShape(Ket27.Radius.card)).padding(14.dp).testTag("personalize-preview"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Aperçu", color = theme.onBackground, style = Ket27.Type.title3)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Ket27.Metrics.groupRadius)).background(theme.card)) {
            PreviewRow("Titre de démonstration", "Artiste", 0)
            Box(Modifier.padding(start = 66.dp).fillMaxWidth().height(.5.dp).background(theme.hairline))
            PreviewRow("Une autre chanson", "Album", 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(CircleShape).background(theme.accent).padding(horizontal = 18.dp, vertical = 9.dp)) {
                Text("Lire", color = com.ketvoice.app.theme.CoverLegibility.onFill(theme.accent), style = Ket27.Type.subheadline,
                    fontWeight = FontWeight.SemiBold)
            }
            Text("Lien en couleur d'accent", color = theme.accent, style = Ket27.Type.subheadline, modifier = Modifier.padding(start = 14.dp))
        }
        LyricsPreview()
    }
}

@Composable
private fun PreviewRow(title: String, sub: String, index: Int) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().heightIn(min = Ket27.Metrics.row).padding(horizontal = 12.dp, vertical = Ket27.Metrics.rowPadding),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(Ket27.Radius.thumb))
            .background(theme.accent.copy(alpha = if (index == 0) .85f else .45f)))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, color = theme.onBackground, style = Ket27.Type.body, maxLines = 1)
            Text(sub, color = theme.secondary, style = Ket27.Type.footnote, maxLines = 1)
        }
        Icon(KetIcons.More, null, tint = theme.secondary, modifier = Modifier.size(20.dp))
    }
}

/** Three lines as the player sings them: the sung line half lit, the others dimmed and (when on) blurred by distance. */
@Composable
private fun LyricsPreview() {
    val sung = com.ketvoice.app.theme.LyricsLook.sung(AppSettings.lyricsSung.value, LocalTheme.current.accent,
        LocalTheme.current.accent, AppSettings.lyricsSungArgb.value)
    val scale = LyricsLook.sizeScale()
    val align = LyricsLook.align(false)
    val blur = LyricsLook.blur()
    val font = LyricsLook.font()
    val lift = (2.5f * LyricsLook.motion()).dp
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Ket27.Radius.card)).background(Color(0xFF1B1B20))
        .padding(horizontal = 16.dp, vertical = 14.dp).testTag("personalize-lyrics-preview"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        @Composable fun line(text: String, distance: Int) = Text(AppText.t(text), localize = false, color = Color.White.copy(alpha = .45f),
            fontFamily = font, fontWeight = FontWeight.Black, fontSize = (22f * scale).sp, lineHeight = (28f * scale).sp, textAlign = align,
            modifier = Modifier.fillMaxWidth().then(if (blur > 0f && android.os.Build.VERSION.SDK_INT >= 31)
                Modifier.blur((com.ketvoice.app.ui.player.LyricsBlur.byDistance(distance) * blur).dp) else Modifier))
        line("Sous la lumière du soir", 1)
        // The sung line, lit up to its last word (which is still to come).
        val sample = AppText.t("On chante encore ensemble")
        val cut = sample.lastIndexOf(' ').let { if (it > 0) it + 1 else sample.length }
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = sung)) { append(sample.substring(0, cut)) }
            withStyle(SpanStyle(color = Color.White.copy(alpha = .40f))) { append(sample.substring(cut)) }
        }, fontFamily = font, fontWeight = FontWeight.Black, fontSize = (22f * scale).sp, lineHeight = (28f * scale).sp, textAlign = align,
            modifier = Modifier.fillMaxWidth().offset(y = -lift))
        line("Jusqu'au bout de la nuit", 1)
    }
}

// ---------------------------------------------------------------- theme

@Composable
private fun ThemeSection() {
    var editDark by remember { mutableStateOf<Boolean?>(null) }
    val dark = editDark ?: LocalTheme.current.isDark
    var editing by remember { mutableStateOf<Personalization.Role?>(null) }
    var importing by remember { mutableStateOf(false) }
    PresetStrip()
    GlassSegmentedControl(listOf(AppText.t("Clair"), AppText.t("Sombre")), if (dark) 1 else 0, { editDark = it == 1 },
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp), tag = "personalize-side")
    Personalization.Role.entries.forEach { role -> ColourRow(role, dark) { editing = role } }
    ContrastNote(dark)
    SRowLike(KetIcons.Share, "Partager le thème", "Un code court à envoyer ; il commence par KT1.", "personalize-share") {
        shareTheme(it)
    }
    SRowLike(KetIcons.Download, "Importer un thème", "Collez un code reçu d'un ami", "personalize-import") { importing = true }
    editing?.let { role ->
        val colors = Personalization.colors().side(dark)
        val grounds = if (role == Personalization.Role.Text || role == Personalization.Role.Accent)
            listOf(PickerGround("Fond", colors.background), PickerGround("Surfaces", colors.surface))
            else listOf(PickerGround("Texte", colors.text))
        ColorPickerSheet(AppText.t(role.label), Personalization.colour(dark, role), grounds,
            onPick = { Personalization.setColour(dark, role, it); save() }, onClose = { editing = null })
    }
    if (importing) ImportThemeDialog { importing = false }
}

@Composable
private fun PresetStrip() {
    val theme = LocalTheme.current
    val current = ThemePresets.matching(com.ketvoice.app.theme.ThemeCodec.parse(AppSettings.themeColors.value), AppSettings.accentArgb.value)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp)
        .testTag("personalize-presets"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ThemePresets.all.forEach { preset ->
            val chosen = current?.id == preset.id
            val side = (preset.colors ?: ThemePresets.ket27).side(theme.isDark)
            Column(Modifier.width(84.dp).clip(RoundedCornerShape(Ket27.Radius.artwork))
                .selectable(chosen, role = Role.RadioButton) { Personalization.applyPreset(preset); save() }
                .padding(4.dp).testTag("preset-${preset.id}"), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(width = 76.dp, height = 56.dp).clip(RoundedCornerShape(Ket27.Radius.thumb))
                    .background(Color(0xFF000000.toInt() or side.background))
                    .border(if (chosen) 2.dp else .5.dp, if (chosen) theme.accent else theme.hairline, RoundedCornerShape(Ket27.Radius.thumb))
                    .padding(8.dp)) {
                    Box(Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF000000.toInt() or side.surface)))
                    Box(Modifier.align(Alignment.BottomStart).size(width = 28.dp, height = 5.dp).clip(CircleShape)
                        .background(Color(0xFF000000.toInt() or side.text)))
                    Box(Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(Color(preset.accent.toInt())))
                }
                Text(preset.name, color = if (chosen) theme.onBackground else theme.secondary, style = Ket27.Type.caption,
                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun ColourRow(role: Personalization.Role, dark: Boolean, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val rgb = Personalization.colour(dark, role)
    Row(Modifier.fillMaxWidth().rowPress(onClick = onClick).heightIn(min = Ket27.Metrics.row)
        .padding(horizontal = 16.dp, vertical = Ket27.Metrics.rowPadding).testTag("personalize-colour-${role.name}"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or rgb)).border(.5.dp, theme.hairline, CircleShape))
        Text(role.label, color = theme.onBackground, style = Ket27.Type.body, modifier = Modifier.weight(1f).padding(start = 12.dp))
        Text("#%06X".format(rgb), localize = false, color = theme.secondary, style = Ket27.Type.subheadline)
        Icon(KetIcons.ChevronRight, null, tint = theme.secondary.copy(alpha = .8f), modifier = Modifier.padding(start = 8.dp).size(17.dp))
    }
    SettingsCellDivider()
}

/** The guard's verdict on the side being edited: the ratios, and when it had to move a colour, that it did. */
@Composable
private fun ContrastNote(dark: Boolean) {
    val theme = LocalTheme.current
    if (!Personalization.custom()) return
    val report = ThemeGuard.report(Personalization.colors().side(dark), AppSettings.accentArgb.value)
    val line = AppText.t("Texte") + " " + ContrastText.ratio(report.text) + " · " + AppText.t("Accent") + " " + ContrastText.ratio(report.accent)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("personalize-contrast")) {
        Text(line, localize = false, color = theme.secondary, style = Ket27.Type.footnote)
        if (report.adjusted) Text("Contraste insuffisant : la couleur est ajustée automatiquement pour rester lisible (WCAG AA).",
            color = warningInk(), style = Ket27.Type.footnote, modifier = Modifier.padding(top = 4.dp).testTag("personalize-adjusted"))
    }
    SettingsCellDivider()
}

@Composable
private fun SRowLike(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, tag: String, onClick: (Context) -> Unit) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().settingsTarget(title).rowPress { onClick(context) }.heightIn(min = Ket27.Metrics.row)
        .padding(horizontal = 16.dp, vertical = Ket27.Metrics.rowPadding).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Ket27Tile(icon, title.length)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp)) {
            Text(title, color = theme.onBackground, style = Ket27.Type.body)
            Text(sub, color = theme.secondary, style = Ket27.Type.footnote, modifier = Modifier.padding(top = 3.dp))
        }
        Icon(KetIcons.ChevronRight, null, tint = theme.secondary.copy(alpha = .8f), modifier = Modifier.size(17.dp))
    }
    SettingsCellDivider()
}

private fun shareTheme(context: Context) {
    val text = AppText.t("Mon thème Ketvoice. Dans Ketvoice, ouvrez Réglages › Personnalisation › Importer un thème :") + "\n" +
        Personalization.shareCode()
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
internal fun ImportThemeDialog(onClose: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val theme = LocalTheme.current
    AppAlertDialog(onDismissRequest = onClose,
        confirmButton = { KetTextButton("Importer", onClick = {
            if (Personalization.import(code)) { save(); onClose() } else wrong = true
        }, primary = true, enabled = code.isNotBlank(), modifier = Modifier.testTag("personalize-import-ok")) },
        dismissButton = { KetTextButton("Annuler", onClick = onClose) },
        title = { Text("Importer un thème") },
        text = {
            Column {
                Text("Collez le code d'un thème reçu d'un ami. Ses couleurs et son accent remplacent les vôtres.", color = theme.secondary,
                    style = Ket27.Type.footnote, modifier = Modifier.padding(bottom = 10.dp))
                KetTextField(code, { code = it.take(400); wrong = false }, "Code du thème", Modifier.testTag("personalize-import-code"), singleLine = false)
                if (wrong) Text("Ce code n'est pas un thème Ketvoice.", color = warningInk(), style = Ket27.Type.footnote,
                    modifier = Modifier.padding(top = 6.dp).testTag("personalize-import-wrong"))
            }
        })
}

// ---------------------------------------------------------------- font, size, corners, density

@Composable
private fun ShapeSection() {
    FontStrip("Police", AppSettings.appFont.value, follow = false, tag = "personalize-fonts") { AppSettings.appFont.value = it; save() }
    // The text size is applied on release: while the finger moves, the page would grow under it.
    var text by remember { mutableIntStateOf(AppSettings.textScale.value) }
    LaunchedEffect(AppSettings.textScale.value) { text = AppSettings.textScale.value }
    PercentSlider("Taille du texte", text, AppShape.TEXT_MIN, AppShape.TEXT_MAX, 9, "personalize-text-size",
        onChange = { text = it }, onDone = { AppSettings.textScale.value = text; save() })
    PercentSlider("Arrondis", AppSettings.cornerScale.value, AppShape.CORNER_MIN, AppShape.CORNER_MAX, 8, "personalize-corners",
        valueLabel = cornerLabel(AppSettings.cornerScale.value),
        onChange = { AppSettings.cornerScale.value = it }, onDone = ::save)
    LabeledSegments("Densité des listes", AppShape.densityLabels, AppSettings.listDensity.value, "personalize-density") {
        AppSettings.listDensity.value = it; save()
    }
}

private fun cornerLabel(percent: Int) = when {
    percent == 0 -> "Carrés"
    percent < 100 -> "Discrets"
    percent == 100 -> "Ket 27"
    percent < 160 -> "Arrondis"
    else -> "Très arrondis"
}

/** The fonts, each name written in its own face; [follow] adds « Comme l'app » first (the lyrics' choice). */
@Composable
private fun FontStrip(title: String, selected: String, follow: Boolean, tag: String, onPick: (String) -> Unit) {
    val theme = LocalTheme.current
    Text(title, color = theme.onBackground, style = Ket27.Type.body, modifier = Modifier.settingsTarget(title).padding(start = 16.dp, end = 16.dp, top = 12.dp))
    val choices = (if (follow) listOf("" to AppText.t("Comme l'app")) else emptyList()) + AppFonts.all.map { it.id to it.name }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp).testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { (id, name) ->
            val chosen = id == selected
            val family = if (id.isEmpty()) Ket27.font else AppFonts.family(id)
            Column(Modifier.widthIn(min = 76.dp).clip(RoundedCornerShape(Ket27.Radius.thumb))
                .background(theme.onBackground.copy(alpha = if (chosen) .10f else .05f))
                .border(if (chosen) 2.dp else 0.dp, if (chosen) theme.accent else Color.Transparent, RoundedCornerShape(Ket27.Radius.thumb))
                .selectable(chosen, role = Role.RadioButton) { onPick(id) }
                .padding(horizontal = 12.dp, vertical = 10.dp).testTag("$tag-${id.ifEmpty { "app" }}"),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Aa", localize = false, color = theme.onBackground, fontFamily = family, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text(name, localize = false, color = if (chosen) theme.onBackground else theme.secondary, fontFamily = family,
                    style = Ket27.Type.caption, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    SettingsCellDivider()
}

/** A titled slider in whole percent between [min] and [max], landing on [steps] stops. */
@Composable
private fun PercentSlider(title: String, value: Int, min: Int, max: Int, steps: Int, tag: String, valueLabel: String? = null,
                          zeroLabel: String? = null, enabled: Boolean = true, onChange: (Int) -> Unit, onDone: () -> Unit) {
    val theme = LocalTheme.current
    val shown = if (value == 0 && zeroLabel != null) AppText.t(zeroLabel) else "$value %" + (valueLabel?.let { " · " + AppText.t(it) } ?: "")
    val name = AppText.t(title)
    Column(Modifier.fillMaxWidth().settingsTarget(title).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = theme.onBackground, style = Ket27.Type.body, modifier = Modifier.weight(1f))
            Text(shown, localize = false, color = theme.secondary, style = Ket27.Type.body, modifier = Modifier.testTag("$tag-value"))
        }
        GlassSlider(value.toFloat(), { onChange(kotlin.math.round(it).toInt().coerceIn(min, max)) },
            Modifier.padding(top = 6.dp).testTag(tag).semantics { contentDescription = name; stateDescription = shown },
            valueRange = min.toFloat()..max.toFloat(), enabled = enabled, snapSteps = steps, onValueChangeFinished = onDone)
    }
    SettingsCellDivider()
}

@Composable
private fun LabeledSegments(title: String, labels: List<String>, selected: Int, tag: String, onSelect: (Int) -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxWidth().settingsTarget(title).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, color = theme.onBackground, style = Ket27.Type.body, modifier = Modifier.padding(bottom = 8.dp))
        GlassSegmentedControl(labels.map { AppText.t(it) }, selected, onSelect, tag = tag)
    }
    SettingsCellDivider()
}

// ---------------------------------------------------------------- lyrics

@Composable
private fun LyricsSection() {
    var picking by remember { mutableStateOf(false) }
    FontStrip("Police des paroles", AppSettings.lyricsFont.value, follow = true, tag = "personalize-lyrics-fonts") { AppSettings.lyricsFont.value = it; save() }
    PercentSlider("Taille du texte des paroles", AppSettings.lyricsScale.value, LyricsLook.SIZE_MIN, LyricsLook.SIZE_MAX, 12,
        "personalize-lyrics-size", onChange = { AppSettings.lyricsScale.value = it }, onDone = ::save)
    LabeledSegments("Alignement", listOf("Gauche", "Centre"), AppSettings.lyricsAlign.value, "personalize-lyrics-align") {
        AppSettings.lyricsAlign.value = it; save()
    }
    LabeledSegments("Couleur des mots chantés", LyricsLook.sungLabels, AppSettings.lyricsSung.value, "personalize-lyrics-sung") {
        AppSettings.lyricsSung.value = it; save()
        if (it == LyricsLook.SUNG_CUSTOM) picking = true
    }
    if (AppSettings.lyricsSung.value == LyricsLook.SUNG_CUSTOM) SRowLike(KetIcons.Appearance, "Couleur personnalisée",
        "#%06X".format(AppSettings.lyricsSungArgb.value and 0xFFFFFF), "personalize-lyrics-custom") { picking = true }
    val blurOn = AppSettings.lyricsBlur.value
    PercentSlider("Intensité du flou", if (blurOn) AppSettings.lyricsBlurLevel.value else 0, 0, 100, 10, "personalize-lyrics-blur",
        zeroLabel = "Désactivé", enabled = com.ketvoice.app.ui.player.LyricsBlur.deviceSupported(),
        onChange = { AppSettings.lyricsBlurLevel.value = it; AppSettings.lyricsBlur.value = it > 0 }, onDone = ::save)
    PercentSlider("Mouvement des mots", AppSettings.lyricsMotion.value, 0, 100, 10, "personalize-lyrics-motion",
        zeroLabel = "Immobiles", onChange = { AppSettings.lyricsMotion.value = it }, onDone = ::save)
    if (picking) ColorPickerSheet(AppText.t("Couleur des mots chantés"), (AppSettings.lyricsSungArgb.value and 0xFFFFFF).toInt(),
        listOf(PickerGround("Fond des paroles", 0x26262A)),
        onPick = { AppSettings.lyricsSungArgb.value = 0xFF000000L or it.toLong(); save() }, onClose = { picking = false })
}

@Composable
private fun ResetAllRow() {
    var asking by remember { mutableStateOf(false) }
    if (!Personalization.changed() && AppSettings.accentArgb.value == ThemePresets.DEFAULT_ACCENT) return
    KetTextButton("Tout réinitialiser", { asking = true }, Modifier.padding(top = 18.dp).fillMaxWidth().testTag("personalize-reset"), destructive = true)
    if (asking) AppAlertDialog(onDismissRequest = { asking = false },
        confirmButton = { KetTextButton("Réinitialiser", { Personalization.resetAll(); save(); asking = false }, destructive = true,
            modifier = Modifier.testTag("personalize-reset-ok")) },
        dismissButton = { KetTextButton("Annuler", { asking = false }) },
        title = { Text("Tout réinitialiser ?") },
        text = { Text("Couleurs, accent, police, tailles, arrondis et paroles reprennent l'apparence de Ketvoice.") })
}
