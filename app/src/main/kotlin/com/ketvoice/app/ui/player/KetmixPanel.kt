package com.ketvoice.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.automix.BassSwap
import com.ketvoice.app.automix.FilterSweep
import com.ketvoice.app.automix.MixKind
import com.ketvoice.app.automix.MixPlan
import com.ketvoice.app.automix.MixPointStore
import com.ketvoice.app.automix.MixShaping
import com.ketvoice.app.automix.MixStyle
import com.ketvoice.app.automix.MixWords
import com.ketvoice.app.automix.PairChoice
import com.ketvoice.app.automix.TransitionPreview
import com.ketvoice.app.automix.VolumeCurve
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.GlassCapsuleButton
import com.ketvoice.app.ui.common.GlassSlider
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.KetMenu
import com.ketvoice.app.ui.common.KetMenuButton
import com.ketvoice.app.ui.common.KetMenuItem
import com.ketvoice.app.ui.common.LiquidGlassButton
import com.ketvoice.app.ui.common.LiquidGlassSwitch
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.TrackActionFeedback
import com.ketvoice.app.ui.common.onAccent
import com.ketvoice.app.ui.motion.Haptics
import com.ketvoice.app.ui.motion.rememberHaptics
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

private suspend fun say(text: String) {
    TrackActionFeedback.snackbar.currentSnackbarData?.dismiss()
    TrackActionFeedback.snackbar.showSnackbar(text)
}

// ---- Top bar ----

/** Close, the name with « Bêta », « … » and « Enregistrer ». */
@Composable
internal fun EditorTopBar(state: EditorState, onClose: () -> Unit) {
    val theme = LocalTheme.current
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = Ket27.Metrics.margin), verticalAlignment = Alignment.CenterVertically) {
        LiquidGlassButton(onClick = onClose, modifier = Modifier.size(44.dp).testTag("ketmix-close")) {
            Icon(KetIcons.Close, AppText.t("Fermer"), tint = theme.onBackground, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        // The name over its « Bêta » mark: on a phone the bar has no room for both on one line beside « Enregistrer ».
        Column(Modifier.weight(1f)) {
            Text("Éditeur Ketmix", color = theme.onBackground, style = Ket27.Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Bêta", color = theme.secondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.padding(top = 1.dp).clip(RoundedCornerShape(6.dp)).background(theme.onBackground.copy(alpha = .09f)).padding(horizontal = 6.dp, vertical = 1.dp))
        }
        KetMenuButton(listOf(listOf(
            KetMenuItem(AppText.t("Revenir au choix de Ketmix"), KetIcons.Refresh, tag = "ketmix-reset", enabled = !state.choice.automatic) {
                state.reset(); if (AppSettings.ketmixHaptics.value) haptic(Haptics.Kind.Confirm)
            },
            KetMenuItem(AppText.t("Appliquer à toute la file d'attente"), KetIcons.Playlist, tag = "ketmix-apply-queue",
                enabled = state.pair && Player.state.value.queue.size > 2) {
                val count = applyToQueue(state)
                scope.launch { say(AppText.t("Réglages appliqués à {0} transitions").replace("{0}", count.toString())) }
            })), AppText.t("Plus d'options"), tag = "ketmix-more")
        Spacer(Modifier.width(8.dp))
        GlassCapsuleButton("Enregistrer", null, onClick = {
            state.save(); if (AppSettings.ketmixHaptics.value) haptic(Haptics.Kind.Confirm)
            scope.launch { say(AppText.t("Transition enregistrée")) }
        }, modifier = Modifier.widthIn(max = 128.dp), prominent = true, enabled = state.dirty, tag = "ketmix-save", minHeight = 40.dp)
    }
}

/** The style and the details of this transition, for every transition of the queue (each keeps its points and length). */
internal fun applyToQueue(state: EditorState): Int {
    state.save()
    val queue = Player.state.value.queue
    var count = 0
    for (i in 0 until queue.size - 1) {
        val from = queue[i].id; val to = queue[i + 1].id
        if (from == to) continue
        val kept = MixPointStore.pair(from, to)
        MixPointStore.setPair(from, to, PairChoice(state.choice.style, kept?.overlapMs ?: -1L, state.choice.shape))
        count++
    }
    return count
}

// ---- Timeline caption and transport ----

/** The legend of the curves, and the zoom. */
@Composable
internal fun TimelineCaption(state: EditorState) {
    val theme = LocalTheme.current
    val ink = TimelineInk.of(theme)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            LegendItem("Volume", ink.volume, CurveLine.Solid)
            LegendItem("Basses", ink.bass, CurveLine.Dashed)
            LegendItem("Filtre", ink.filter, CurveLine.Dotted)
            LegendItem("Chant", ink.voice, null)
        }
        ZoomButton(plus = false, enabled = state.zoom > TimelineWindow.ZOOM_MIN) { state.zoom = (state.zoom / 1.5f).coerceAtLeast(TimelineWindow.ZOOM_MIN) }
        ZoomButton(plus = true, enabled = state.zoom < TimelineWindow.ZOOM_MAX) { state.zoom = (state.zoom * 1.5f).coerceAtMost(TimelineWindow.ZOOM_MAX) }
    }
}

@Composable
private fun LegendItem(label: String, color: Color, line: CurveLine?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Swatch(color, line)
        Spacer(Modifier.width(5.dp))
        Text(label, color = LocalTheme.current.secondary, style = Ket27.Type.caption, maxLines = 1)
    }
}

/** A short sample of a curve as the timeline draws it; a bar for the voice. */
@Composable
internal fun Swatch(color: Color, line: CurveLine?, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 18.dp, height = 10.dp)) {
        val y = size.height / 2f
        if (line == null) drawLine(color, Offset(1.dp.toPx(), y), Offset(size.width - 1.dp.toPx(), y), 3.dp.toPx(), StrokeCap.Round)
        else drawLine(color, Offset(0f, y), Offset(size.width, y), if (line == CurveLine.Solid) 2.5.dp.toPx() else 2.dp.toPx(), StrokeCap.Round,
            pathEffect = curveEffect(line))
    }
}

@Composable
private fun ZoomButton(plus: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val description = AppText.t(if (plus) "Zoom avant" else "Zoom arrière")
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }.testTag(if (plus) "ketmix-zoom-in" else "ketmix-zoom-out"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(32.dp).clip(CircleShape).background(theme.onBackground.copy(alpha = .08f)).alpha(if (enabled) 1f else .35f)) {
            val c = size.width / 2f; val r = 6.dp.toPx(); val w = 1.8.dp.toPx()
            drawLine(theme.onBackground, Offset(c - r, c), Offset(c + r, c), w, StrokeCap.Round)
            if (plus) drawLine(theme.onBackground, Offset(c, c - r), Offset(c, c + r), w, StrokeCap.Round)
        }
    }
}

private val StopGlyph: ImageVector by lazy {
    ImageVector.Builder("ketmix-stop", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(8f, 6f); lineTo(16f, 6f); quadTo(18f, 6f, 18f, 8f); lineTo(18f, 16f); quadTo(18f, 18f, 16f, 18f)
            lineTo(8f, 18f); quadTo(6f, 18f, 6f, 16f); lineTo(6f, 8f); quadTo(6f, 6f, 8f, 6f); close()
        }
    }.build()
}

/** « Écouter » / « Arrêter » and « A/B »: the transition alone, with the mix or as a plain cut. */
@Composable
internal fun Transport(state: EditorState, plan: MixPlan) {
    val theme = LocalTheme.current
    val scope = rememberCoroutineScope()
    val engine = TransitionPreview.engine
    val preview by engine.state.collectAsState()
    val available = state.pair && plan.mixes && engine.available()
    fun start(plain: Boolean) {
        if (!engine.start(state.from, state.into.track, state.plan(), plain)) scope.launch { say(AppText.t("Ketmix ne peut pas jouer la transition maintenant")) }
    }
    LaunchedEffect(preview.problem) { preview.problem?.let { say(AppText.t(it)) } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassCapsuleButton(if (preview.running) "Arrêter" else "Écouter", if (preview.running) StopGlyph else KetIcons.Play,
            onClick = { if (preview.running) engine.stop() else start(state.plainCut) },
            modifier = Modifier.weight(1f), prominent = true, enabled = available || preview.running, tag = "ketmix-preview", minHeight = 48.dp)
        Segmented(listOf("Avec mix", "Sans mix"), selected = if (state.plainCut) 1 else 0, enabled = state.pair && plan.mixes, tag = "ketmix-ab") { index ->
            state.plainCut = index == 1
            if (preview.running) start(state.plainCut)
        }
    }
    Spacer(Modifier.height(6.dp))
    val hint = when {
        !state.pair -> "Ce titre seul : sa sortie et son entrée valent pour toutes ses transitions."
        !plan.mixes -> "Ces deux titres ne seront pas mixés."
        AppSettings.bitPerfect.value -> "Écoute indisponible en sortie bit-perfect"
        !engine.available() && !preview.running -> "Lancez la lecture d'un titre pour écouter la transition."
        preview.loading -> "Préparation de l'écoute…"
        preview.running && preview.plain -> "Le même moment sans mix : une coupe nette."
        else -> null
    }
    if (hint != null) Text(hint, color = theme.secondary, style = Ket27.Type.footnote)
}

/** Two choices side by side in one capsule. */
@Composable
private fun Segmented(labels: List<String>, selected: Int, enabled: Boolean, tag: String, onSelect: (Int) -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.height(48.dp).clip(RoundedCornerShape(999.dp)).background(theme.onBackground.copy(alpha = .07f)).padding(4.dp)
        .alpha(if (enabled) 1f else .45f).testTag(tag)) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            Box(Modifier.fillMaxHeight().clip(RoundedCornerShape(999.dp)).background(if (on) theme.card else Color.Transparent)
                .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(index) }.testTag("$tag-$index").padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center) {
                Text(label, color = if (on) theme.onBackground else theme.secondary, fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

// ---- Presets, details ----

/** A transition style as the presets row shows it. [style] null is « Auto ». */
internal class Preset(val style: MixStyle?, val label: String)

/**
 * The one place a [MixStyle] gets its chip: a style added to the enum shows at once (under its name until it is
 * given its words and its glyph here, in one line).
 */
internal fun presetOf(style: MixStyle): Preset = when (style) {
    MixStyle.Auto -> Preset(null, "Auto")
    MixStyle.Tempo -> Preset(style, "Calé sur le tempo")
    MixStyle.Echo -> Preset(style, "Écho")
    MixStyle.Filters -> Preset(style, "Filtres")
    MixStyle.Fade -> Preset(style, "Fondu")
    MixStyle.Rise -> Preset(style, "Montée")
    else -> Preset(style, style.name)
}

internal fun presets(): List<Preset> = MixStyle.values().map(::presetOf)

/** The small symbol of a preset, drawn in one stroke. */
private fun DrawScope.presetGlyph(style: MixStyle?, color: Color) {
    val w = size.width; val h = size.height; val stroke = 1.8.dp.toPx()
    fun line(x0: Float, y0: Float, x1: Float, y1: Float) = drawLine(color, Offset(w * x0, h * y0), Offset(w * x1, h * y1), stroke, StrokeCap.Round)
    when (style) {
        null, MixStyle.Auto -> drawPath(Path().apply {
            moveTo(w * .5f, h * .05f); quadraticBezierTo(w * .5f, h * .5f, w * .95f, h * .5f); quadraticBezierTo(w * .5f, h * .5f, w * .5f, h * .95f)
            quadraticBezierTo(w * .5f, h * .5f, w * .05f, h * .5f); quadraticBezierTo(w * .5f, h * .5f, w * .5f, h * .05f); close()
        }, color)
        MixStyle.Tempo -> for (i in 0..3) { val x = .14f + i * .24f; line(x, .1f, x, .4f); line(x, .6f, x, .9f) }
        MixStyle.Echo -> floatArrayOf(.8f, .55f, .35f, .2f).forEachIndexed { i, r -> val x = .14f + i * .24f; line(x, .5f - r / 2, x, .5f + r / 2) }
        MixStyle.Filters -> drawPath(Path().apply { moveTo(w * .05f, h * .3f); lineTo(w * .45f, h * .3f); quadraticBezierTo(w * .7f, h * .3f, w * .95f, h * .85f) },
            color, style = Stroke(stroke, cap = StrokeCap.Round))
        MixStyle.Fade -> { line(.08f, .2f, .92f, .8f); line(.08f, .8f, .92f, .2f) }
        MixStyle.Rise -> { drawPath(Path().apply { moveTo(w * .06f, h * .85f); quadraticBezierTo(w * .6f, h * .8f, w * .8f, h * .15f) },
            color, style = Stroke(stroke, cap = StrokeCap.Round)); line(.8f, .15f, .8f, .85f) }
        else -> drawCircle(color, w * .32f, style = Stroke(stroke))
    }
}

@Composable
private fun PresetChip(preset: Preset, on: Boolean, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val content = if (on) onAccent(theme.accent) else theme.onBackground
    Column(Modifier.widthIn(min = 76.dp).height(68.dp).clip(RoundedCornerShape(18.dp))
        .background(if (on) theme.accent else theme.onBackground.copy(alpha = .07f))
        .clickable(role = Role.RadioButton, onClick = onClick).testTag("ketmix-style-" + (preset.style?.name ?: "Auto"))
        .padding(horizontal = 12.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Canvas(Modifier.size(20.dp)) { presetGlyph(preset.style, content) }
        Text(preset.label, color = content, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Presets(state: EditorState, wide: Boolean) {
    val haptic = rememberHaptics()
    val chosen = state.choice.style ?: state.defaultStyle
    val chips: @Composable () -> Unit = {
        for (preset in presets()) PresetChip(preset, on = (preset.style ?: MixStyle.Auto) == chosen) {
            state.commitStyle(preset.style); if (AppSettings.ketmixHaptics.value) haptic(Haptics.Kind.Tick)
        }
    }
    if (wide) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
}

private class Option<T>(val value: T, val label: String)

private val volumeOptions = listOf(Option(VolumeCurve.Auto, "Automatique"), Option(VolumeCurve.Smooth, "Doux"),
    Option(VolumeCurve.Linear, "Linéaire"), Option(VolumeCurve.Fast, "Rapide"))
private val bassOptions = listOf(Option(BassSwap.Auto, "Automatique"), Option(BassSwap.Short, "Échange court"), Option(BassSwap.Medium, "Échange moyen"),
    Option(BassSwap.Long, "Échange long"), Option(BassSwap.Off, "Aucun échange"))
private val filterOptions = listOf(Option(FilterSweep.Auto, "Automatique"), Option(FilterSweep.Off, "Aucun"),
    Option(FilterSweep.HighPass, "Passe-haut"), Option(FilterSweep.LowPass, "Passe-bas"))

/** One detail of the blend: its colour on the timeline, its name, and its value in a menu. */
@Composable
private fun <T> DetailRow(label: String, color: Color, line: CurveLine, options: List<Option<T>>, value: T, enabled: Boolean, tag: String, onPick: (T) -> Unit) {
    val theme = LocalTheme.current
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.value == value } ?: options.first()
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, role = Role.DropdownList) { open = true }
            .testTag(tag).padding(horizontal = 14.dp).alpha(if (enabled) 1f else .45f), verticalAlignment = Alignment.CenterVertically) {
            Swatch(color, line)
            Spacer(Modifier.width(12.dp))
            Text(label, color = theme.onBackground, style = Ket27.Type.body, modifier = Modifier.weight(1f))
            Text(current.label, color = theme.secondary, style = Ket27.Type.body, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Icon(KetIcons.ChevronRight, null, tint = theme.secondary, modifier = Modifier.size(14.dp).rotate(90f))
        }
        KetMenu(open, { open = false }, listOf(options.map { option ->
            KetMenuItem(AppText.t(option.label), checked = option.value == value, tag = "$tag-${option.value}") { onPick(option.value) }
        }))
    }
}

@Composable
private fun Hairline() = Box(Modifier.fillMaxWidth().padding(start = 44.dp).height(1.dp).background(LocalTheme.current.hairline))

/** « Volume », « Basses », « Filtre » and « Calage du tempo », as one group. */
@Composable
private fun Details(state: EditorState, plan: MixPlan) {
    val theme = LocalTheme.current
    val ink = TimelineInk.of(theme)
    val shape = state.choice.shape
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(theme.onBackground.copy(alpha = if (theme.isDark) .06f else .045f))) {
        DetailRow("Volume", ink.volume, CurveLine.Solid, volumeOptions, shape.volume, MixShaping.hasVolume(plan), "ketmix-volume") {
            state.commitShape(shape.copy(volume = it))
        }
        Hairline()
        DetailRow("Basses", ink.bass, CurveLine.Dashed, bassOptions, shape.bass, MixShaping.hasBass(plan), "ketmix-bass") {
            state.commitShape(shape.copy(bass = it))
        }
        Hairline()
        DetailRow("Filtre", ink.filter, CurveLine.Dotted, filterOptions, shape.filter, MixShaping.hasFilter(plan), "ketmix-filter") {
            state.commitShape(shape.copy(filter = it))
        }
        Hairline()
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 14.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text("Calage du tempo", color = theme.onBackground, style = Ket27.Type.body)
                Text("Le titre entrant change un peu de vitesse pour tomber sur les temps.", color = theme.secondary, style = Ket27.Type.footnote)
            }
            LiquidGlassSwitch(shape.tempo, { state.commitShape(shape.copy(tempo = it)) }, Modifier.testTag("ketmix-tempo"))
        }
    }
    if (plan.mixes && !MixShaping.hasVolume(plan))
        Text("Ce style coupe sur le temps : volume, basses et filtre suivent la coupe.", color = theme.secondary, style = Ket27.Type.footnote,
            modifier = Modifier.padding(top = 6.dp))
}

/** The words of a transition's kind, for the summary. */
private fun kindWord(plan: MixPlan): String = if (!plan.mixes) AppText.t("Pas de mix") else AppText.t(MixWords.label(plan)).replaceFirstChar { it.uppercase() }

/** What will be played, and whether it is the listener's choice. */
@Composable
private fun Summary(state: EditorState, plan: MixPlan) {
    val theme = LocalTheme.current
    val listener = plan.reason == "listener" || !state.choice.shape.isDefault
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(kindWord(plan), localize = false, color = theme.onBackground, style = Ket27.Type.headline, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (listener) theme.accent.copy(alpha = .16f) else theme.onBackground.copy(alpha = .08f))
            .padding(horizontal = 9.dp, vertical = 3.dp)) {
            Text(if (listener) "Votre choix" else "Automatique", color = if (listener) theme.accent else theme.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    val measures = MixWords.measures(plan)
    val blend = if (plan.mixes) AppText.t("Mix de {0} s").replace("{0}", "%.1f".format(java.util.Locale.ROOT, plan.overlapMs / 1000f)) else ""
    Text(listOf(blend, measures).filter { it.isNotBlank() }.joinToString(" · "), localize = false, color = theme.secondary, style = Ket27.Type.footnote)
    val sung = state.vocalEndMs
    if (plan.mixes && sung != null && state.effective.exitMs >= 0 && plan.outMs < sung) {
        Spacer(Modifier.height(6.dp))
        Text("Votre sortie coupe le chant : Ketmix la respecte et baisse la voix sortante.", color = theme.secondary, style = Ket27.Type.footnote)
    }
}

/** How long both are heard, for a finger that wants it exact (the box's edges do the same). */
@Composable
private fun Overlap(state: EditorState, plan: MixPlan) {
    val theme = LocalTheme.current
    val fixed = plan.kind == MixKind.Echo || plan.kind == MixKind.Rise || plan.kind == MixKind.Trim
    val overlapSeconds = ((state.dragOverlap ?: plan.overlapMs.takeIf { plan.mixes } ?: state.choice.overlapMs.coerceAtLeast(8_000L)) / 1000f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Chevauchement", color = theme.onBackground, style = Ket27.Type.body, modifier = Modifier.weight(1f))
        val beats = if (plan.kind == MixKind.Beatmatch && plan.beatMs > 0f) AppText.t("{0} temps").replace("{0}", Math.round(plan.overlapMs / plan.beatMs).toString()) + " · " else ""
        Text(beats + "%.1f s".format(java.util.Locale.ROOT, overlapSeconds), localize = false, color = theme.secondary, style = Ket27.Type.body)
    }
    GlassSlider(overlapSeconds.coerceIn(1f, 30f), onValueChange = { state.dragOverlap = (it * 1000f).roundToLong() },
        valueRange = 1f..30f, enabled = !fixed && plan.mixes,
        onValueChangeFinished = { state.dragOverlap?.let(state::commitOverlap); state.dragOverlap = null },
        modifier = Modifier.fillMaxWidth().testTag("ketmix-overlap"))
    if (fixed) Text("Pour ce style, la longueur suit le tempo du titre.", color = theme.secondary, style = Ket27.Type.footnote)
}

@Composable
private fun Heading(text: String) {
    Text(text, color = LocalTheme.current.onBackground, style = Ket27.Type.headline, modifier = Modifier.padding(bottom = 10.dp))
}

/** Everything under (or beside) the timeline. */
@Composable
internal fun Settings(state: EditorState, plan: MixPlan, wide: Boolean) {
    val theme = LocalTheme.current
    Summary(state, plan)
    if (state.pair) {
        Spacer(Modifier.height(22.dp))
        Heading("Préréglages")
        Presets(state, wide)
        Spacer(Modifier.height(22.dp))
        Heading("Détails")
        Details(state, plan)
        Spacer(Modifier.height(22.dp))
        Overlap(state, plan)
    }
    Spacer(Modifier.height(16.dp))
    Text("Glissez la boîte pour déplacer la transition, ses bords pour changer sa durée, une onde pour déplacer son titre seul. Tout se cale sur les temps et les phrases ; maintenez avant de glisser pour placer librement. Pincez pour zoomer.",
        color = theme.secondary, style = Ket27.Type.footnote)
}
