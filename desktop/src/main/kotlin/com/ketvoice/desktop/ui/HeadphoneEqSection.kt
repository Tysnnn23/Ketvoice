package com.ketvoice.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.player.HeadphoneLink
import com.ketvoice.app.player.HeadphoneProfile
import com.ketvoice.app.player.HeadphoneProfiles
import com.ketvoice.app.player.HeadphoneStore
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.desktop.audio.DesktopHeadphoneEq
import com.ketvoice.desktop.audio.DesktopOutput
import com.ketvoice.desktop.ui.theme.DeskGlyphs
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import java.io.File
import kotlin.math.ln
import kotlin.math.pow

/** What the « Sortie audio » menu and the headphone section say, kept apart so that they can be tested. */
internal object HeadphoneEqLogic {
    const val MAX_FILE_BYTES = 65_536

    /** The output menu: the system's default, then each device (the chosen one marked, also while it is unplugged). */
    fun outputItems(devices: List<String>, chosen: String?, onPick: (String?) -> Unit): List<MenuItem> {
        val absent = chosen?.takeIf { it !in devices }
        return listOf(MenuItem("Sortie par défaut du système", if (chosen == null) KetIcons.Check else null, "output-default") { onPick(null) }) +
            devices.map { name -> MenuItem(name, if (name == chosen) KetIcons.Check else null, "output:$name", translate = false) { onPick(name) } } +
            listOfNotNull(absent?.let { MenuItem("$it · ${AppText.t("Non connectée")}", KetIcons.Check, "output-absent", translate = false) { onPick(it) } })
    }

    /** The line under the device's name: whether its correction is heard, and why not (French, translated when shown). */
    fun line(status: DesktopHeadphoneEq.Status, enabled: Boolean, comparing: Boolean): String = when {
        status.key == null -> "Choisissez une sortie ci-dessus pour lier une correction"
        status.profile == null -> "Aucun profil lié à cette sortie"
        !enabled -> "Correction désactivée"
        comparing -> "Écoute sans correction"
        else -> "Correction appliquée"
    }

    fun preamp(profile: HeadphoneProfile): String = "%.1f dB".format(java.util.Locale.ROOT, profile.safePreampDb())

    /** A profile file the listener picked (64 KB at most), named after the file. */
    fun read(file: File): HeadphoneProfile {
        require(file.length() <= MAX_FILE_BYTES) { "Fichier trop volumineux" }
        val bytes = runCatching { file.readBytes() }.getOrElse { throw IllegalArgumentException("Fichier illisible") }
        return HeadphoneProfiles.parse(com.ketvoice.app.data.CustomLyrics.decode(bytes), file.name)
    }
}

/** The output the music plays to, with the menu of devices (the bar's button opens the same one). */
@Composable
internal fun OutputRow() {
    val ket = LocalKet.current
    val menu = LocalMenu.current
    val devices by DesktopOutput.devices.collectAsState()
    val chosen by DesktopOutput.chosen.collectAsState()
    var at by remember { mutableStateOf(Offset.Zero) }
    Row(Modifier.fillMaxWidth().height(44.dp)
        .onGloballyPositioned { at = it.positionInWindow() + Offset(it.size.width.toFloat() - 280f, it.size.height.toFloat()) }
        .clickable { menu.open(HeadphoneEqLogic.outputItems(devices, chosen, DesktopOutput::choose), IntOffset(at.x.toInt(), at.y.toInt())) }
        .padding(horizontal = 16.dp).testTag("output-row"), verticalAlignment = Alignment.CenterVertically) {
        Icon(DeskGlyphs.Output, null, Modifier.size(16.dp), tint = ket.accent)
        Spacer(Modifier.width(10.dp))
        KText("Sortie audio", Ket27Desk.Type.subheadline, Modifier.weight(1f))
        KText(chosen ?: AppText.t("Sortie par défaut du système"), Ket27Desk.Type.subheadline, Modifier.padding(start = 12.dp),
            color = ket.secondary, weight = FontWeight.Normal, translate = false)
        Icon(DeskGlyphs.ChevronDown, null, Modifier.padding(start = 6.dp).size(12.dp), tint = ket.tertiary)
    }
}

/**
 * « Égaliseur par casque » (the phone's 9.0), under the equalizer: the switch, the device playing and its correction,
 * the comparison and the curve, the listener's profiles (imported from a parametric file, searched, linked to the
 * device with a click) and the devices already linked. Ketvoice ships no measurements: the listener brings them.
 */
internal fun LazyListScope.headphoneEq() {
    item("hp-header") { SectionHeader("Égaliseur par casque") }
    item("hp-card") { HeadphoneCard() }
    item("hp-profiles-header") { SectionHeader("Profils de casque") }
    item("hp-profiles") { ProfilesCard() }
    item("hp-note") {
        KText("Format accepté : égaliseur paramétrique (Preamp et filtres PK, LSC, HSC), comme les fichiers ParametricEQ.txt. Ketvoice ne fournit pas de catalogue de mesures. Le préampli est abaissé si besoin pour qu'aucune fréquence ne sature.",
            Ket27Desk.Type.footnote, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), color = LocalKet.current.secondary, maxLines = 4)
    }
}

@Composable
private fun HeadphoneCard() {
    val ket = LocalKet.current
    val status by DesktopHeadphoneEq.status.collectAsState()
    val comparing by DesktopHeadphoneEq.compare.collectAsState()
    val enabled = AppSettings.headphoneEq.value
    SettingsCard {
        ToggleRow("Correction du casque", enabled, "headphone-eq") { AppSettings.headphoneEq.value = it; DesktopHeadphoneEq.update() }
        Hairline()
        OutputRow()
        Hairline()
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp).testTag("headphone-status"),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (status.applied) ket.accent else ket.tertiary.copy(alpha = .5f)))
            Spacer(Modifier.width(12.dp))
            val line = AppText.t(HeadphoneEqLogic.line(status, enabled, comparing))
            val profile = status.profile?.name
            KText(if (profile != null) "$line · $profile" else line, Ket27Desk.Type.footnote,
                color = if (status.applied) ket.accent else ket.secondary, translate = false, maxLines = 2)
        }
        val profile = status.profile ?: return@SettingsCard
        Hairline()
        ToggleRow("Écouter sans correction", comparing, "headphone-compare") { DesktopHeadphoneEq.compare.value = it; DesktopHeadphoneEq.update() }
        ResponseCurve(profile, active = status.applied)
    }
}

@Composable
private fun ProfilesCard() {
    val ket = LocalKet.current
    val status by DesktopHeadphoneEq.status.collectAsState()
    val profilesText = AppSettings.headphoneProfiles.value
    val linksText = AppSettings.headphoneLinks.value
    val profiles = remember(profilesText) { HeadphoneStore.profiles() }
    val links = remember(linksText) { HeadphoneStore.links() }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    SettingsCard {
        if (profiles.isNotEmpty()) { SearchRow(query) { query = it }; Hairline() }
        val shown = HeadphoneProfiles.search(profiles, query)
        when {
            profiles.isEmpty() -> Note("Aucun profil pour l'instant. Importez le profil de votre casque : il sera appliqué dès qu'il est branché.")
            shown.isEmpty() -> Note("Aucun profil ne correspond")
        }
        shown.forEach { ProfileRow(it, status); Hairline() }
        Row(Modifier.fillMaxWidth().height(44.dp).clickable { error = import() }.padding(horizontal = 16.dp).testTag("headphone-import"),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(KetIcons.Plus, null, Modifier.size(17.dp), tint = ket.accent)
            Spacer(Modifier.width(10.dp))
            KText("Importer un fichier", Ket27Desk.Type.subheadline, color = ket.accent)
        }
        error?.let { KText(AppText.t(it), Ket27Desk.Type.footnote, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            color = destructiveRed, translate = false, maxLines = 2) }
        LinkRows(links, profiles)
    }
}

/** Picks a file and imports it; the message to show when it cannot be read, else null. */
private fun import(): String? {
    val file = pickProfile() ?: return null
    return runCatching { DesktopHeadphoneEq.import(HeadphoneEqLogic.read(file)) }.exceptionOrNull()?.let { it.message ?: "Fichier illisible" }
}

/** The system's file chooser (Swing, in the look of Windows), on text files. */
private fun pickProfile(): File? = runCatching {
    runCatching { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()) }
    val chooser = javax.swing.JFileChooser().apply {
        dialogTitle = AppText.t("Choisir un profil de casque")
        fileFilter = javax.swing.filechooser.FileNameExtensionFilter("ParametricEQ (.txt)", "txt")
    }
    if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}.getOrNull()

@Composable
private fun Note(text: String) {
    KText(text, Ket27Desk.Type.footnote, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = LocalKet.current.secondary, maxLines = 3)
}

@Composable
private fun SearchRow(query: String, onChange: (String) -> Unit) {
    val ket = LocalKet.current
    Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(KetIcons.Search, null, Modifier.size(15.dp), tint = ket.secondary)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) KText("Rechercher un casque ou une marque…", Ket27Desk.Type.subheadline, color = ket.tertiary)
            BasicTextField(query, { onChange(it.take(80)) }, Modifier.fillMaxWidth().textEntry().testTag("headphone-search"), singleLine = true,
                textStyle = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.subheadline).copy(color = ket.label),
                cursorBrush = SolidColor(ket.accent))
        }
    }
}

@Composable
private fun ProfileRow(profile: HeadphoneProfile, status: DesktopHeadphoneEq.Status) {
    val ket = LocalKet.current
    val linked = status.profile?.id == profile.id
    val linkable = status.key != null
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(if (linked) ket.accent.copy(alpha = .10f) else androidx.compose.ui.graphics.Color.Transparent)
        .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp).testTag("headphone-profile"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            KText(profile.name, Ket27Desk.Type.subheadline, translate = false)
            KText("${profile.filters.size} ${AppText.t("filtres")} · ${AppText.t("préampli")} ${HeadphoneEqLogic.preamp(profile)}",
                Ket27Desk.Type.caption, color = ket.secondary, translate = false)
        }
        if (linkable) KetTextLink(if (linked) "Lié" else "Lier", "headphone-link") {
            DesktopHeadphoneEq.link(status.device, if (linked) null else profile)
        }
        KetTextLink("Supprimer", "headphone-remove") { DesktopHeadphoneEq.remove(profile.id) }
    }
}

@Composable
private fun LinkRows(links: Map<String, HeadphoneLink>, profiles: List<HeadphoneProfile>) {
    if (links.isEmpty()) return
    val ket = LocalKet.current
    Hairline()
    KText("Sorties liées", Ket27Desk.Type.footnote, Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp), color = ket.secondary, weight = FontWeight.SemiBold)
    links.forEach { (key, link) ->
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 16.dp, end = 8.dp).testTag("headphone-linked"), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                KText(link.name, Ket27Desk.Type.subheadline, translate = false)
                KText(profiles.firstOrNull { it.id == link.profileId }?.name ?: "—", Ket27Desk.Type.caption, color = ket.secondary, translate = false)
            }
            KetTextLink("Délier", "headphone-unlink") { DesktopHeadphoneEq.unlink(key) }
        }
    }
}

/** The correction's curve from 20 Hz to 20 kHz (±12 dB), preamp included: what the headphones are given. */
@Composable
private fun ResponseCurve(profile: HeadphoneProfile, active: Boolean) {
    val ket = LocalKet.current
    val points = remember(profile) {
        val preamp = profile.safePreampDb()
        List(96) { i -> val hz = 20.0 * 1000.0.pow(i / 95.0); (profile.responseDb(hz) + preamp).toFloat() }
    }
    Canvas(Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 16.dp, vertical = 10.dp).testTag("headphone-curve")) {
        val mid = size.height / 2f
        drawLine(ket.hairline, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        for (hz in intArrayOf(100, 1_000, 10_000)) {
            val x = (ln(hz / 20.0) / ln(1000.0)).toFloat() * size.width
            drawLine(ket.hairline.copy(alpha = .5f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        }
        val path = Path()
        points.forEachIndexed { i, db ->
            val x = i / 95f * size.width
            val y = mid - (db.coerceIn(-12f, 12f) / 12f) * mid
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, ket.accent.copy(alpha = if (active) 1f else .35f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}
