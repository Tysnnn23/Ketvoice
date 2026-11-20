package com.ketvoice.app.ui.player

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ketvoice.app.automix.MixEditor
import com.ketvoice.app.automix.MixKeys
import com.ketvoice.app.automix.MixPlan
import com.ketvoice.app.automix.MixPreset
import com.ketvoice.app.automix.MixPresetStore
import com.ketvoice.app.automix.MixShare
import com.ketvoice.app.automix.StudioSnap
import com.ketvoice.app.automix.TransitionPreview
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.AppAlertDialog
import com.ketvoice.app.ui.common.GlassCapsuleButton
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.KetMenuButton
import com.ketvoice.app.ui.common.KetMenuItem
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.KetTextField
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.TrackActionFeedback
import com.ketvoice.app.ui.common.onAccent
import kotlinx.coroutines.launch

/*
 * Ketmix Studio (8.0). The owner could not add a volume keyframe in 7.6: the only way was a touch inside the
 * transition box, nothing on screen said so (the one explanation sat at the very bottom of the scrolled settings,
 * below the presets and the details, out of sight on a phone), and a short style (Cut, Echo, Rise, Trim) left a box
 * a few pixels wide. The toolbar under the top bar now always shows « + Point », undo/redo and the magnet.
 */

private suspend fun studioSay(text: String) {
    TrackActionFeedback.snackbar.currentSnackbarData?.dismiss()
    TrackActionFeedback.snackbar.showSnackbar(text)
}

/** A keyframe [relMs] into the blend of [plan], on the outgoing title's grid while the magnet is on. */
internal fun EditorState.snapKey(plan: MixPlan, relMs: Long, msPerPx: Float, pullPx: Float): Long =
    if (!magnet) relMs.coerceIn(0L, plan.overlapMs)
    else StudioSnap.key(out.analysis, plan.outMs, relMs, plan.overlapMs, MixEditor.tolerance(out.analysis, msPerPx, pullPx))

/** Whether « + Point » can place a keyframe: a pair of titles that are mixed. */
internal fun EditorState.canAddKey(plan: MixPlan): Boolean = pair && plan.mixes

/**
 * « + Point »: a keyframe where « Écouter » plays when that is inside the blend, else in its middle; its sheet opens.
 * The index of the keyframe there (new or already placed), or -1 when twelve are placed.
 */
internal fun EditorState.addKeyHere(plan: MixPlan, playheadMs: Long?): Int {
    if (!canAddKey(plan)) return -1
    val rel = playheadMs?.let { it - plan.outMs }?.takeIf { it in 0..plan.overlapMs } ?: (plan.overlapMs / 2)
    val index = addKey(snapKey(plan, rel, 0f, 0f), plan.overlapMs)
    if (index >= 0) keyOpen = index
    return index
}

/** Applies [preset] to this pair: one step of undo. */
internal fun EditorState.applyPreset(preset: MixPreset) {
    val base = choice.copy(style = preset.style, overlapMs = preset.overlapMs, shape = preset.shape, keys = emptyList())
    val plan = MixEditor.plan(out.side(), into.side(), base, intensity, defaultStyle, vocalEndMs)
    choice = base.copy(keys = if (plan.mixes) preset.keysFor(plan.overlapMs) else emptyList())
}

/** This pair's transition saved as [name]; null when the list is full. */
internal fun EditorState.saveAsPreset(name: String): MixPreset? =
    MixPresetStore.save(MixPreset.of("", name, choice.pairChoice, plan().overlapMs))

internal fun EditorState.shared(): MixShare.Shared = MixShare.Shared(
    MixShare.End(from.id, from.title, from.artist), MixShare.End(into.track.id, into.track.title, into.track.artist),
    choice.exitMs, choice.entryMs, choice.pairChoice)

/** Takes a friend's mix: all of it on the same two titles, its transition alone (style, length, details, keyframes) on others. */
internal fun EditorState.importShared(s: MixShare.Shared): Boolean {
    val same = s.from.id == from.id && s.to.id == into.track.id
    val c = s.choice
    choice = choice.copy(exitMs = if (same) s.exitMs else choice.exitMs, entryMs = if (same) s.entryMs else choice.entryMs,
        overlapMs = c.overlapMs, style = c.style, shape = c.shape, keys = c.keys)
    return same
}

private fun shareMix(context: Context, state: EditorState) {
    val text = AppText.t("Mon mix Ketmix de « {0} » vers « {1} ». Dans Ketvoice, ouvrez Ketmix Studio sur ces deux titres, puis … › Importer un mix :")
        .replace("{0}", state.from.title).replace("{1}", state.into.track.title) + "\n" + MixShare.encode(state.shared())
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** What the « … » menu of the top bar adds for the Studio. */
internal fun studioMenu(state: EditorState, context: Context): List<KetMenuItem> = listOf(
    KetMenuItem(AppText.t("Mes préréglages"), KetIcons.Sliders, tag = "ketmix-presets") { state.presetsOpen = true },
    KetMenuItem(AppText.t("Partager ce mix"), KetIcons.Share, tag = "ketmix-share", enabled = state.pair) { shareMix(context, state) },
    KetMenuItem(AppText.t("Importer un mix"), KetIcons.Download, tag = "ketmix-import", enabled = state.pair) { state.importOpen = true })

// ---- Toolbar ----

private fun strokeGlyph(name: String, build: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round, pathBuilder = build)
    }.build()

private val UndoGlyph: ImageVector by lazy { strokeGlyph("ketmix-undo") {
    moveTo(9f, 6.5f); lineTo(5f, 10.5f); lineTo(9f, 14.5f)
    moveTo(5f, 10.5f); lineTo(14.5f, 10.5f); curveTo(17.3f, 10.5f, 19.5f, 12.7f, 19.5f, 15.2f); curveTo(19.5f, 17.7f, 17.3f, 19.5f, 14.5f, 19.5f); lineTo(11f, 19.5f)
} }
private val RedoGlyph: ImageVector by lazy { strokeGlyph("ketmix-redo") {
    moveTo(15f, 6.5f); lineTo(19f, 10.5f); lineTo(15f, 14.5f)
    moveTo(19f, 10.5f); lineTo(9.5f, 10.5f); curveTo(6.7f, 10.5f, 4.5f, 12.7f, 4.5f, 15.2f); curveTo(4.5f, 17.7f, 6.7f, 19.5f, 9.5f, 19.5f); lineTo(13f, 19.5f)
} }
private val MagnetGlyph: ImageVector by lazy { strokeGlyph("ketmix-magnet") {
    moveTo(6f, 4.5f); lineTo(6f, 12f); curveTo(6f, 15.9f, 8.7f, 19f, 12f, 19f); curveTo(15.3f, 19f, 18f, 15.9f, 18f, 12f); lineTo(18f, 4.5f)
    lineTo(14.5f, 4.5f); lineTo(14.5f, 12f); curveTo(14.5f, 13.7f, 13.4f, 15.3f, 12f, 15.3f); curveTo(10.6f, 15.3f, 9.5f, 13.7f, 9.5f, 12f)
    lineTo(9.5f, 4.5f); close()
    moveTo(6f, 8.2f); lineTo(9.5f, 8.2f); moveTo(14.5f, 8.2f); lineTo(18f, 8.2f)
} }

@Composable
private fun ToolButton(glyph: ImageVector, label: String, enabled: Boolean, tag: String, on: Boolean? = null, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val description = AppText.t(label)
    val state = on?.let { AppText.t(if (it) "Activé" else "Désactivé") }
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, role = if (on == null) Role.Button else Role.Switch, onClick = onClick)
        .semantics { contentDescription = description; if (state != null) stateDescription = state }.testTag(tag), contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(if (on == true) theme.accent else theme.onBackground.copy(alpha = .08f))
            .alpha(if (enabled) 1f else .35f), contentAlignment = Alignment.Center) {
            Icon(glyph, null, tint = if (on == true) onAccent(theme.accent) else theme.onBackground, modifier = Modifier.size(19.dp))
        }
    }
}

/** Undo, redo, the magnet and « + Point », with the first-use hint under them. */
@Composable
internal fun StudioToolbar(state: EditorState, plan: MixPlan, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val scope = rememberCoroutineScope()
    val preview by TransitionPreview.engine.state.collectAsState()
    var hintSeen by remember { mutableStateOf(MixPresetStore.hintSeen) }
    val canAdd = state.canAddKey(plan)
    Column(modifier.fillMaxWidth().testTag("ketmix-studio-toolbar")) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(UndoGlyph, "Annuler la modification", state.canUndo, "ketmix-undo") { state.undo() }
            ToolButton(RedoGlyph, "Rétablir la modification", state.canRedo, "ketmix-redo") { state.redo() }
            ToolButton(MagnetGlyph, "Caler sur les temps", true, "ketmix-magnet", on = state.magnet) {
                state.magnet = !state.magnet; MixPresetStore.magnet = state.magnet
                scope.launch { studioSay(AppText.t(if (state.magnet) "Calage sur les temps activé" else "Placement libre")) }
            }
            Spacer(Modifier.weight(1f))
            GlassCapsuleButton("Point", KetIcons.Plus, onClick = {
                val index = state.addKeyHere(plan, preview.positionMs.takeIf { preview.running && !preview.loading })
                if (index < 0) scope.launch { studioSay(AppText.t("{0} points au maximum").replace("{0}", MixKeys.MAX.toString())) }
                hintSeen = true; MixPresetStore.hintSeen = true
            }, modifier = Modifier.widthIn(min = 120.dp, max = 168.dp).semantics { contentDescription = AppText.t("Ajouter un point de volume") },
                prominent = true, enabled = canAdd, tag = "ketmix-add-key", minHeight = 40.dp)
        }
        val hint = when {
            !state.pair -> "Pour régler le volume pendant une transition, ouvrez Ketmix Studio sur un titre suivi d'un autre."
            canAdd && !hintSeen && state.choice.keys.isEmpty() ->
                "Touchez « Point » pour régler la voix, les instruments et la musique des deux titres à un moment de la transition."
            else -> null
        }
        if (hint != null) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp))
            .background(theme.onBackground.copy(alpha = .06f)).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).testTag("ketmix-key-hint"),
            verticalAlignment = Alignment.CenterVertically) {
            Text(hint, color = theme.secondary, style = Ket27.Type.footnote, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
            if (state.pair) KetTextButton("OK", onClick = { hintSeen = true; MixPresetStore.hintSeen = true })
        }
    }
}

// ---- « Mes préréglages » ----

private fun summary(p: MixPreset): String {
    val parts = ArrayList<String>()
    parts += p.style?.let { AppText.t(presetOf(it).label) } ?: AppText.t("Auto")
    if (p.overlapMs > 0) parts += "%.1f s".format(java.util.Locale.ROOT, p.overlapMs / 1000.0)
    if (p.keys.size == 1) parts += AppText.t("1 point de volume")
    else if (p.keys.isNotEmpty()) parts += AppText.t("{0} points de volume").replace("{0}", p.keys.size.toString())
    return parts.joinToString(" · ")
}

@Composable
internal fun PresetsSheet(state: EditorState) {
    val theme = LocalTheme.current
    val scope = rememberCoroutineScope()
    val revision by MixPresetStore.revision.collectAsState()
    val presets = remember(revision) { MixPresetStore.all() }
    var naming by remember { mutableStateOf<MixPreset?>(null) }
    var creating by remember { mutableStateOf(false) }
    com.ketvoice.app.ui.common.SpringSheet(onDismiss = { state.presetsOpen = false },
        insets = Modifier.statusBarsPadding().navigationBarsPadding()) { maxHeight ->
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight).padding(8.dp).clip(RoundedCornerShape(Ket27.Metrics.alertRadius))
            .background(theme.card).padding(bottom = 12.dp).testTag("ketmix-presets-sheet")) {
            com.ketvoice.app.ui.common.SheetGrabber(Modifier.align(Alignment.CenterHorizontally))
            Column(Modifier.padding(horizontal = Ket27.Metrics.margin)) {
                Text("Mes préréglages", color = theme.onBackground, style = Ket27.Type.title3, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp).semantics { heading() })
                Text("Le style, la longueur, les détails et les points de volume d'une transition, à reprendre sur d'autres titres.",
                    color = theme.secondary, style = Ket27.Type.footnote, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                GlassCapsuleButton("Enregistrer cette transition", KetIcons.Plus, onClick = { creating = true },
                    modifier = Modifier.fillMaxWidth(), prominent = true, enabled = state.pair, tag = "ketmix-preset-new", minHeight = 48.dp)
            }
            Spacer(Modifier.height(8.dp))
            if (presets.isEmpty()) Text("Aucun préréglage pour l'instant.", color = theme.secondary, style = Ket27.Type.subheadline,
                modifier = Modifier.padding(horizontal = Ket27.Metrics.margin, vertical = 14.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (p in presets) Row(Modifier.fillMaxWidth().heightIn(min = 60.dp)
                    .clickable(enabled = state.pair, role = Role.Button) {
                        state.applyPreset(p); state.presetsOpen = false
                        scope.launch { studioSay(AppText.t("Préréglage « {0} » appliqué").replace("{0}", p.name)) }
                    }.padding(start = Ket27.Metrics.margin, end = 10.dp).testTag("ketmix-preset-" + p.id), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(p.name, localize = false, color = theme.onBackground, style = Ket27.Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(summary(p), localize = false, color = theme.secondary, style = Ket27.Type.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    KetMenuButton(listOf(listOf(
                        KetMenuItem(AppText.t("Renommer"), null, tag = "ketmix-preset-rename") { naming = p },
                        KetMenuItem(AppText.t("Supprimer"), KetIcons.Trash, destructive = true, tag = "ketmix-preset-delete") { MixPresetStore.delete(p.id) })),
                        AppText.t("Plus d'options"), tag = "ketmix-preset-more-" + p.id)
                }
            }
        }
    }
    if (creating || naming != null) NameDialog(initial = naming?.name ?: "", renaming = naming != null,
        onDismiss = { creating = false; naming = null }) { name ->
        val target = naming
        creating = false; naming = null
        if (target != null) MixPresetStore.rename(target.id, name)
        else if (state.saveAsPreset(name) == null) scope.launch { studioSay(AppText.t("{0} préréglages au maximum").replace("{0}", MixPresetStore.LIMIT.toString())) }
        else scope.launch { studioSay(AppText.t("Préréglage enregistré")) }
    }
}

@Composable
private fun NameDialog(initial: String, renaming: Boolean, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val ok = MixPreset.cleanName(name).isNotEmpty()
    AppAlertDialog(onDismissRequest = onDismiss,
        confirmButton = { KetTextButton("Enregistrer", onClick = { if (ok) onDone(name) }, primary = true, enabled = ok, modifier = Modifier.testTag("ketmix-preset-name-ok")) },
        dismissButton = { KetTextButton("Annuler", onClick = onDismiss) },
        title = { Text(if (renaming) "Renommer le préréglage" else "Nouveau préréglage") },
        text = { KetTextField(name, { name = it.take(MixPreset.NAME_MAX) }, "Nom", Modifier.testTag("ketmix-preset-name")) })
}

// ---- « Importer un mix » ----

@Composable
internal fun ImportMixDialog(state: EditorState) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val theme = LocalTheme.current
    AppAlertDialog(onDismissRequest = { state.importOpen = false },
        confirmButton = { KetTextButton("Importer", onClick = {
            val shared = MixShare.find(code)
            if (shared == null) wrong = true
            else {
                val same = state.importShared(shared); state.importOpen = false
                scope.launch { studioSay(AppText.t(if (same) "Mix importé" else "Mix importé sur ces titres ; leurs points d'entrée et de sortie sont gardés")) }
            }
        }, primary = true, enabled = code.isNotBlank(), modifier = Modifier.testTag("ketmix-import-ok")) },
        dismissButton = { KetTextButton("Annuler", onClick = { state.importOpen = false }) },
        title = { Text("Importer un mix") },
        text = {
            Column {
                Text("Collez le code d'un mix reçu d'un ami. Il s'applique à cette transition.", color = theme.secondary, style = Ket27.Type.footnote,
                    modifier = Modifier.padding(bottom = 10.dp))
                KetTextField(code, { code = it; wrong = false }, "Code du mix", Modifier.testTag("ketmix-import-code"), singleLine = false)
                if (wrong) Text("Ce code n'est pas un mix Ketmix.", color = com.ketvoice.app.ui.common.DESTRUCTIVE, style = Ket27.Type.footnote, modifier = Modifier.padding(top = 6.dp))
            }
        })
}
