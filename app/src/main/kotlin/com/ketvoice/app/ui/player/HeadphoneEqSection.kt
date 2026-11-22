package com.ketvoice.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.player.AudioOutput
import com.ketvoice.app.player.AudioOutputs
import com.ketvoice.app.player.HeadphoneEq
import com.ketvoice.app.player.HeadphoneProfile
import com.ketvoice.app.player.HeadphoneProfiles
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.AppAlertDialog
import com.ketvoice.app.ui.common.CompactSearchField
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.KetTextField
import com.ketvoice.app.ui.common.LiquidGlassSwitch
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.pow

/** What the screen needs to draw the section; built from the settings and the outputs, or by a test. */
internal data class HeadphoneEqView(val output: AudioOutput?, val status: HeadphoneEq.Status, val profiles: List<HeadphoneProfile>,
                                    val links: Map<String, HeadphoneEq.Link>, val enabled: Boolean, val comparing: Boolean)

/** The line under the output's name: whether its correction is heard, and why not. */
internal fun headphoneStatusLine(view: HeadphoneEqView): String = when {
    view.output == null -> "Aucune sortie audio détectée"
    view.status.key == null -> "Cette sortie n'est pas un casque : aucune correction"
    view.status.profile == null -> "Aucun profil lié à cette sortie"
    AppSettings.bitPerfect.value -> "Indisponible en sortie bit-perfect"
    !view.enabled -> "Correction désactivée"
    view.comparing -> "Écoute sans correction"
    else -> "Correction appliquée"
}

/**
 * « Égaliseur par casque » (9.0), in the equalizer sheet: the switch, the output playing and its correction, the
 * comparison, the listener's profiles (searched by name, linked to the output with a touch), imports and links.
 */
@Composable
internal fun HeadphoneEqSection() {
    val context = LocalContext.current
    val outputs by remember(context) { AudioOutputs.watch(context) }.collectAsState(initial = remember(context) { AudioOutputs.read(context) })
    val chosen by AudioOutputs.chosen.collectAsState()
    val comparing by HeadphoneEq.compare.collectAsState()
    val profilesText = AppSettings.headphoneProfiles.value
    val linksText = AppSettings.headphoneLinks.value
    val profiles = remember(profilesText) { HeadphoneEq.profiles() }
    val links = remember(linksText) { HeadphoneEq.links() }
    val output = AudioOutputs.active(outputs, chosen)
    val enabled = AppSettings.headphoneEq.value
    val view = HeadphoneEqView(output, HeadphoneEq.resolve(output, enabled, comparing, profiles, links), profiles, links, enabled, comparing)
    // The audio chain follows the outputs from the service; a change made here reaches it at once.
    LaunchedEffect(view.status) { HeadphoneEq.follow(outputs, chosen) }
    HeadphoneEqContent(view)
}

private fun changed() { HeadphoneEq.changed(); SettingsStore.save() }

@Composable
internal fun HeadphoneEqContent(view: HeadphoneEqView) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var pasting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun imported(profile: HeadphoneProfile) {
        val kept = HeadphoneEq.add(profile)
        // A first profile goes to the headphones playing, when nothing is linked to them yet.
        val output = view.output
        if (output != null && view.status.key != null && view.status.profile == null) HeadphoneEq.link(output, kept)
        error = null; changed()
    }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { readProfile(context, uri) } }
            result.onSuccess(::imported).onFailure { error = it.message ?: "Fichier illisible" }
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 18.dp).testTag("headphone-eq")) {
        Text("Égaliseur par casque", color = theme.onBackground, style = Ket27.Type.title3, fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() })
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Correction du casque", color = theme.onBackground, modifier = Modifier.weight(1f))
            val label = AppText.t("Correction du casque")
            LiquidGlassSwitch(view.enabled, { AppSettings.headphoneEq.value = it; changed() },
                modifier = Modifier.semantics { contentDescription = label })
        }
        OutputCard(view)
        if (view.status.profile != null) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Écouter sans correction", color = theme.onBackground)
                    Text("Pour entendre la différence ; rien n'est enregistré.", color = theme.secondary, style = Ket27.Type.footnote)
                }
                val label = AppText.t("Écouter sans correction")
                LiquidGlassSwitch(view.comparing, { HeadphoneEq.compare.value = it },
                    modifier = Modifier.semantics { contentDescription = label }.testTag("headphone-compare"))
            }
            ResponseCurve(view.status.profile, active = view.status.applied)
        }
        Spacer(Modifier.height(10.dp))
        CompactSearchField(query, { query = it }, onSearch = {}, placeholder = "Rechercher un casque ou une marque…")
        val shown = HeadphoneProfiles.search(view.profiles, query)
        if (view.profiles.isEmpty()) Text("Aucun profil pour l'instant. Importez le profil de votre casque : il sera appliqué dès qu'il est branché.",
            color = theme.secondary, style = Ket27.Type.footnote, modifier = Modifier.padding(vertical = 10.dp))
        else if (shown.isEmpty()) Text("Aucun profil ne correspond", color = theme.secondary, style = Ket27.Type.footnote,
            modifier = Modifier.padding(vertical = 10.dp))
        shown.forEach { profile -> ProfileRow(profile, view) }
        error?.let { Text(AppText.t(it), localize = false, color = com.ketvoice.app.ui.common.DESTRUCTIVE, style = Ket27.Type.footnote) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            KetTextButton("Importer un fichier", { picker.launch(arrayOf("text/*", "application/octet-stream")) }, Modifier.heightIn(min = 48.dp))
            KetTextButton("Coller un profil", { pasting = true }, Modifier.heightIn(min = 48.dp))
        }
        LinkList(view)
        Text("Format accepté : égaliseur paramétrique (Preamp et filtres PK, LSC, HSC), comme les fichiers ParametricEQ.txt. Ketvoice ne fournit pas de catalogue de mesures. Le préampli est abaissé si besoin pour qu'aucune fréquence ne sature.",
            color = theme.secondary, style = Ket27.Type.footnote, modifier = Modifier.padding(top = 8.dp))
    }
    if (pasting) PasteProfileDialog(onDismiss = { pasting = false }) { name, text ->
        runCatching { HeadphoneProfiles.parse(text, name) }.onSuccess { imported(it); pasting = false }.onFailure { error = it.message }
    }
}

/** A file the listener picked, read as a profile (64 KB at most), named after the file. */
private fun readProfile(context: android.content.Context, uri: android.net.Uri): HeadphoneProfile {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: "Casque"
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            out.write(buffer, 0, n); require(out.size() <= 65_536) { "Fichier trop volumineux" }
        }
        out.toByteArray()
    } ?: throw IllegalArgumentException("Fichier illisible")
    return HeadphoneProfiles.parse(com.ketvoice.app.data.CustomLyrics.decode(bytes), name)
}

@Composable
private fun OutputCard(view: HeadphoneEqView) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).background(theme.onBackground.copy(alpha = .06f))
        .padding(14.dp).testTag("headphone-output"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (view.status.applied) theme.accent else theme.secondary.copy(alpha = .4f)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(view.output?.let(::outputTitle) ?: "Sortie audio", color = theme.onBackground, style = Ket27.Type.headline,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = AppText.t(headphoneStatusLine(view))
            val profile = view.status.profile?.name
            Text(if (profile != null && view.status.key != null) "$line · $profile" else line, localize = false,
                color = if (view.status.applied) theme.accent else theme.secondary, style = Ket27.Type.footnote,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ProfileRow(profile: HeadphoneProfile, view: HeadphoneEqView) {
    val theme = LocalTheme.current
    val output = view.output
    val linkable = output != null && view.status.key != null
    val linked = view.status.profile?.id == profile.id
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(if (linked) theme.accent.copy(alpha = .12f) else androidx.compose.ui.graphics.Color.Transparent)
        .clickable(enabled = linkable, role = Role.Button) { if (output != null) { HeadphoneEq.link(output, if (linked) null else profile); changed() } }
        .heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 8.dp).testTag("headphone-profile"),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(profile.name, localize = false, color = theme.onBackground, style = Ket27.Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val preamp = "%.1f dB".format(java.util.Locale.ROOT, profile.safePreampDb())
            Text("${profile.filters.size} ${AppText.t("filtres")} · ${AppText.t("préampli")} $preamp",
                localize = false, color = theme.secondary, style = Ket27.Type.footnote)
        }
        if (linked) Text("Lié", color = theme.accent, style = Ket27.Type.footnote, fontWeight = FontWeight.SemiBold)
        else if (linkable) Text("Lier", color = theme.accent, style = Ket27.Type.footnote)
        KetTextButton("Supprimer", { HeadphoneEq.remove(profile.id); changed() }, destructive = true)
    }
}

@Composable
private fun LinkList(view: HeadphoneEqView) {
    if (view.links.isEmpty()) return
    val theme = LocalTheme.current
    Text("Sorties liées", color = theme.secondary, style = Ket27.Type.footnote, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp).semantics { heading() })
    view.links.forEach { (key, link) ->
        val profile = view.profiles.firstOrNull { it.id == link.profileId }?.name ?: "—"
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(link.name, color = theme.onBackground, style = Ket27.Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(profile, localize = false, color = theme.secondary, style = Ket27.Type.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            KetTextButton("Délier", { HeadphoneEq.unlink(key); changed() })
        }
    }
}

@Composable
private fun PasteProfileDialog(onDismiss: () -> Unit, onDone: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    AppAlertDialog(onDismissRequest = onDismiss,
        confirmButton = { KetTextButton("Ajouter", { onDone(name.ifBlank { "Casque" }, text) }, primary = true, enabled = text.isNotBlank()) },
        dismissButton = { KetTextButton("Annuler", onDismiss) },
        title = { Text("Coller un profil") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KetTextField(name, { name = it.take(HeadphoneProfiles.MAX_NAME) }, "Modèle du casque")
                KetTextField(text, { text = it.take(16_000) }, "Préampli et filtres", singleLine = false,
                    modifier = Modifier.heightIn(min = 120.dp, max = 220.dp))
            }
        })
}

/** The correction's curve from 20 Hz to 20 kHz (±12 dB), preamp included: what the headphones are given. */
@Composable
private fun ResponseCurve(profile: HeadphoneProfile, active: Boolean) {
    val theme = LocalTheme.current
    val points = remember(profile) {
        val preamp = profile.safePreampDb()
        List(96) { i -> val hz = 20.0 * 1000.0.pow(i / 95.0); (profile.responseDb(hz) + preamp).toFloat() }
    }
    Canvas(Modifier.fillMaxWidth().height(96.dp).padding(vertical = 8.dp).testTag("headphone-curve")) {
        val mid = size.height / 2f
        drawLine(theme.onBackground.copy(alpha = .14f), Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        for (hz in intArrayOf(100, 1_000, 10_000)) {
            val x = (ln(hz / 20.0) / ln(1000.0)).toFloat() * size.width
            drawLine(theme.onBackground.copy(alpha = .06f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        }
        val path = Path()
        points.forEachIndexed { i, db ->
            val x = i / 95f * size.width
            val y = mid - (db.coerceIn(-12f, 12f) / 12f) * mid
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, theme.accent.copy(alpha = if (active) 1f else .35f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}
