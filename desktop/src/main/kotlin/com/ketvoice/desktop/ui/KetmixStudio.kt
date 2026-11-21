package com.ketvoice.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ketvoice.app.automix.BassSwap
import com.ketvoice.app.automix.FilterSweep
import com.ketvoice.app.automix.GridSnap
import com.ketvoice.app.automix.Levels
import com.ketvoice.app.automix.MixEditor
import com.ketvoice.app.automix.MixKeys
import com.ketvoice.app.automix.MixPlan
import com.ketvoice.app.automix.MixPreset
import com.ketvoice.app.automix.MixPresetStore
import com.ketvoice.app.automix.MixShaping
import com.ketvoice.app.automix.MixShare
import com.ketvoice.app.automix.MixStyle
import com.ketvoice.app.automix.VolumeCurve
import com.ketvoice.app.automix.Wave
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Track
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.desktop.audio.AudioSource
import com.ketvoice.desktop.ketmix.KetmixStudioDesk
import com.ketvoice.desktop.ketmix.StudioPreview
import com.ketvoice.desktop.ketmix.StudioSide
import com.ketvoice.desktop.ketmix.StudioState
import com.ketvoice.desktop.ui.theme.DeskGlyphs
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Cursor

/*
 * Ketmix Studio on the PC (Windows 1.1, owner: « Ketmix Studio sur PC, avec la souris »): the phone's full-screen
 * editor laid out for a mouse. One timeline on the outgoing title's clock — its ending above, the incoming title's
 * start below, both waves read from their sources — with the transition as a box: drag it to move the exit, drag its
 * right edge to change the length, drag the lower wave to move the entry. Under it the volume keyframes: double-click
 * to add one (or « + Point »), drag to move, click to set the voice, instruments and music of both titles. Undo/redo
 * (Ctrl+Z, Ctrl+Y), the magnet on the beat grid, presets shared with the phone (`ketmix-presets-v1`), `KM1.` codes, and
 * « Écouter », which plays the transition through the desktop mixer. Everything comes from the shared automix code.
 */

/** The Studio over the whole window while [KetmixStudioDesk.request] is set. */
@Composable
fun KetmixStudioHost(resolve: suspend (Track) -> AudioSource? = KetmixStudioDesk.resolve) {
    val request = KetmixStudioDesk.request.value ?: return
    val state = remember(request) {
        KetmixStudioDesk.prepared?.takeIf { it.from.id == request.from.id && it.to.id == request.to.id } ?: StudioState(request.from, request.to)
    }
    LaunchedEffect(state) { withContext(Dispatchers.IO) { state.load(resolve) } }
    KetmixStudioScreen(state, onClose = { KetmixStudioDesk.close() })
}

/** Keys while the Studio is open (the window's own shortcuts wait). True when handled. */
internal object StudioKeys {
    var target: StudioState? = null
    var listen: () -> Unit = {}
    var close: () -> Unit = {}

    fun handle(e: KeyEvent): Boolean {
        val s = target ?: return false
        if (e.type != KeyEventType.KeyDown || TextEntry.active) return false
        return when {
            e.isCtrlPressed && (e.key == Key.Y || (e.key == Key.Z && e.isShiftPressed)) -> { s.redo(); true }
            e.isCtrlPressed && e.key == Key.Z -> { s.undo(); true }
            e.isCtrlPressed && e.key == Key.S -> { s.save(); true }
            e.key == Key.Spacebar -> { listen(); true }
            (e.key == Key.Delete || e.key == Key.Backspace) && s.selectedKey >= 0 -> { s.removeKey(s.selectedKey); true }
            e.key == Key.Escape -> { if (s.selectedKey >= 0) s.selectedKey = -1 else close(); true }
            else -> false
        }
    }
}

private enum class StudioDialog { None, Presets, Name, Import, Close }

@Composable
internal fun KetmixStudioScreen(state: StudioState, onClose: () -> Unit) {
    val ket = LocalKet.current
    val preview by StudioPreview.state.collectAsState()
    var dialog by remember { mutableStateOf(StudioDialog.None) }
    var renaming by remember { mutableStateOf<MixPreset?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val plan = state.plan()
    fun say(text: String) { note = AppText.t(text) }
    fun listen() {
        if (preview is StudioPreview.State.Playing || preview == StudioPreview.State.Preparing) { StudioPreview.stop(); return }
        val o = state.out.source; val i = state.into.source
        if (o == null || i == null || !plan.mixes) { say("Ketmix ne peut pas jouer la transition maintenant"); return }
        StudioPreview.start(plan, o, i)
    }
    fun close() { if (state.dirty) dialog = StudioDialog.Close else onClose() }
    StudioKeys.target = state; StudioKeys.listen = ::listen; StudioKeys.close = ::close
    androidx.compose.runtime.DisposableEffect(state) { onDispose { if (StudioKeys.target === state) StudioKeys.target = null } }
    LaunchedEffect(note) { if (note != null) { delay(3_500); note = null } }
    LaunchedEffect(preview) { if (preview == StudioPreview.State.Failed) say("Ketmix ne peut pas jouer la transition maintenant") }

    Column(Modifier.fillMaxSize().background(ket.ground).clickable(remember { MutableInteractionSource() }, null) {}.testTag("ketmix-studio")) {
        TopBar(state, plan, preview, onClose = ::close, onListen = ::listen, onPresets = { dialog = StudioDialog.Presets },
            onSave = { state.save(); say("Transition enregistrée") }, onMore = { action ->
                when (action) {
                    "share" -> { copy(state.shareText()); say("Code du mix copié dans le presse-papiers") }
                    "import" -> dialog = StudioDialog.Import
                    "reset" -> state.reset()
                }
            })
        Box(Modifier.fillMaxWidth().height(.5.dp).background(ket.hairline))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp)) {
            if (!plan.mixes && !state.out.loading && !state.into.loading) {
                KText("Ces deux titres ne seront pas mixés.", Ket27Desk.Type.footnote, Modifier.padding(bottom = 10.dp).testTag("studio-no-mix"), color = ket.secondary)
            }
            Timeline(state, plan, (preview as? StudioPreview.State.Playing)?.clockMs)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Panel(Modifier.weight(1.15f), "Transition") { TransitionPanel(state, plan) }
                Panel(Modifier.weight(1f), "Points de volume") { KeysPanel(state, plan) }
            }
        }
        Box(Modifier.fillMaxWidth().height(.5.dp).background(ket.hairline))
        Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            KText(note ?: footer(plan), Ket27Desk.Type.footnote, Modifier.weight(1f).testTag("studio-note"), color = ket.secondary, translate = false)
            KText("Ctrl+Z · Ctrl+Y · Espace · Suppr", Ket27Desk.Type.caption, color = ket.tertiary)
        }
    }

    when (dialog) {
        StudioDialog.Presets -> PresetsDialog(state, onDismiss = { dialog = StudioDialog.None },
            onApplied = { say(AppText.t("Préréglage « {0} » appliqué").replace("{0}", it.name)) },
            onNew = { renaming = null; dialog = StudioDialog.Name }, onRename = { renaming = it; dialog = StudioDialog.Name })
        StudioDialog.Name -> NameDialog(renaming, onDismiss = { dialog = StudioDialog.Presets }) { name ->
            val target = renaming
            if (target != null) MixPresetStore.rename(target.id, name)
            else if (state.saveAsPreset(name) == null) say(AppText.t("{0} préréglages au maximum").replace("{0}", MixPresetStore.LIMIT.toString()))
            else say("Préréglage enregistré")
            dialog = StudioDialog.Presets
        }
        StudioDialog.Import -> ImportDialog(onDismiss = { dialog = StudioDialog.None }) { shared ->
            say(if (state.importShared(shared)) "Mix importé" else "Mix importé sur ces titres ; leurs points d'entrée et de sortie sont gardés")
            dialog = StudioDialog.None
        }
        StudioDialog.Close -> StudioAlert("Enregistrer cette transition ?", "Vos réglages seront perdus si vous fermez sans enregistrer.",
            onDismiss = { dialog = StudioDialog.None }) {
            DialogAction("Ne pas enregistrer", destructiveRed, "studio-discard") { dialog = StudioDialog.None; onClose() }
            DialogAction("Enregistrer", ket.accent, "studio-save-close", filled = true) { state.save(); dialog = StudioDialog.None; onClose() }
        }
        StudioDialog.None -> {}
    }
}

private fun footer(plan: MixPlan): String = if (!plan.mixes) AppText.t("Pas de mix")
    else AppText.t(com.ketvoice.app.automix.MixWords.label(plan)).replaceFirstChar { it.uppercase() } + " · " +
        AppText.t("Mix de {0} s").replace("{0}", "%.1f".format(java.util.Locale.ROOT, plan.overlapMs / 1000f))

private fun copy(text: String) {
    runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }
}

// ---- Top bar ----

private fun strokeGlyph(name: String, build: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round, pathBuilder = build)
    }.build()

// The phone Studio's glyphs (KetmixStudio.kt), drawn the same way.
private val UndoGlyph: ImageVector by lazy { strokeGlyph("studio-undo") {
    moveTo(9f, 6.5f); lineTo(5f, 10.5f); lineTo(9f, 14.5f)
    moveTo(5f, 10.5f); lineTo(14.5f, 10.5f); curveTo(17.3f, 10.5f, 19.5f, 12.7f, 19.5f, 15.2f); curveTo(19.5f, 17.7f, 17.3f, 19.5f, 14.5f, 19.5f); lineTo(11f, 19.5f)
} }
private val RedoGlyph: ImageVector by lazy { strokeGlyph("studio-redo") {
    moveTo(15f, 6.5f); lineTo(19f, 10.5f); lineTo(15f, 14.5f)
    moveTo(19f, 10.5f); lineTo(9.5f, 10.5f); curveTo(6.7f, 10.5f, 4.5f, 12.7f, 4.5f, 15.2f); curveTo(4.5f, 17.7f, 6.7f, 19.5f, 9.5f, 19.5f); lineTo(13f, 19.5f)
} }
private val MagnetGlyph: ImageVector by lazy { strokeGlyph("studio-magnet") {
    moveTo(6f, 4.5f); lineTo(6f, 12f); curveTo(6f, 15.9f, 8.7f, 19f, 12f, 19f); curveTo(15.3f, 19f, 18f, 15.9f, 18f, 12f); lineTo(18f, 4.5f)
    lineTo(14.5f, 4.5f); lineTo(14.5f, 12f); curveTo(14.5f, 13.7f, 13.4f, 15.3f, 12f, 15.3f); curveTo(10.6f, 15.3f, 9.5f, 13.7f, 9.5f, 12f)
    lineTo(9.5f, 4.5f); close()
    moveTo(6f, 8.2f); lineTo(9.5f, 8.2f); moveTo(14.5f, 8.2f); lineTo(18f, 8.2f)
} }
private val StopGlyph: ImageVector by lazy {
    ImageVector.Builder("studio-stop", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) { moveTo(7f, 7f); lineTo(17f, 7f); lineTo(17f, 17f); lineTo(7f, 17f); close() }
    }.build()
}

@Composable
private fun TopBar(state: StudioState, plan: MixPlan, preview: StudioPreview.State, onClose: () -> Unit, onListen: () -> Unit,
                   onPresets: () -> Unit, onSave: () -> Unit, onMore: (String) -> Unit) {
    val ket = LocalKet.current
    val menu = LocalMenu.current
    val origin = remember { arrayOf(Offset.Zero) }
    Row(Modifier.fillMaxWidth().height(60.dp).background(ket.material).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        KetIconButton(KetIcons.Close, "Fermer", onClose, Modifier.testTag("studio-close"), glyph = 14.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            KText("Ketmix Studio", Ket27Desk.Type.headline, weight = FontWeight.SemiBold, translate = false)
            KText("${state.from.title}  →  ${state.to.title}", Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
        }
        KetIconButton(UndoGlyph, "Annuler la modification", state::undo, Modifier.testTag("studio-undo"), enabled = state.canUndo)
        KetIconButton(RedoGlyph, "Rétablir la modification", state::redo, Modifier.testTag("studio-redo"), enabled = state.canRedo)
        KetIconButton(MagnetGlyph, if (state.magnet) "Calage sur les temps activé" else "Placement libre", {
            state.magnet = !state.magnet; MixPresetStore.magnet = state.magnet
        }, Modifier.testTag("studio-magnet"), active = state.magnet)
        Box(Modifier.padding(horizontal = 10.dp).width(.5.dp).height(22.dp).background(ket.hairline))
        CapsuleButton("+ Point", null, { state.addKey() }, filled = false, accent = if (state.canAddKey(plan)) ket.accent else ket.tertiary,
            modifier = Modifier.testTag("studio-add-key"))
        Spacer(Modifier.width(8.dp))
        CapsuleButton("Préréglages", KetIcons.Sliders, onPresets, filled = false, accent = ket.accent, modifier = Modifier.testTag("studio-presets"))
        Spacer(Modifier.width(4.dp))
        Box(Modifier.onGloballyPositioned { origin[0] = it.positionInWindow() + Offset(it.size.width - 240f, it.size.height.toFloat()) }) {
            KetIconButton(KetIcons.More, "Plus d'options", {
                menu.open(listOf(
                    MenuItem("Partager ce mix", KetIcons.Share, "studio-share") { onMore("share") },
                    MenuItem("Importer un mix", KetIcons.Download, "studio-import") { onMore("import") },
                    MenuItem("-"),
                    MenuItem("Revenir au choix de Ketmix", KetIcons.Refresh, "studio-reset") { onMore("reset") },
                ), IntOffset(origin[0].x.toInt(), origin[0].y.toInt()))
            }, Modifier.testTag("studio-more"))
        }
        Box(Modifier.padding(horizontal = 10.dp).width(.5.dp).height(22.dp).background(ket.hairline))
        val playing = preview is StudioPreview.State.Playing || preview == StudioPreview.State.Preparing
        CapsuleButton(if (playing) "Arrêter" else "Écouter", if (playing) StopGlyph else KetIcons.Play, onListen, filled = false,
            accent = ket.accent, modifier = Modifier.testTag("studio-listen"))
        Spacer(Modifier.width(8.dp))
        CapsuleButton("Enregistrer", null, { if (state.dirty) onSave() }, filled = true,
            accent = if (state.dirty) ket.accent else ket.accent.copy(alpha = .4f), modifier = Modifier.testTag("studio-save"))
    }
}

// ---- Timeline ----

private val LaneHeight = 104.dp
private val KeysHeight = 84.dp
private val RulerHeight = 22.dp
private val LabelWidth = 92.dp

/**
 * The two waves on the outgoing title's clock, the transition box over them and the keyframe lane under them.
 * The view keeps its place while the listener drags (only the box and the incoming wave move), and follows a box
 * dragged out of it once the drag ends.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun Timeline(state: StudioState, plan: MixPlan, playheadMs: Long?) {
    val ket = LocalKet.current
    val measurer = rememberTextMeasurer()
    var zoom by remember { mutableStateOf(1f) }
    var origin by remember(state) { mutableStateOf<Long?>(null) }
    var dragging by remember { mutableStateOf(false) }
    val view = MixEditor.view(plan, wide = true)
    val span = (view.spanMs * zoom).toLong().coerceIn(6_000L, 120_000L)
    val wanted = plan.outMs - (view.beforeMs * zoom).toLong()
    val start = origin?.takeIf { dragging || (plan.outMs in it..(it + span) && plan.outMs + plan.overlapMs <= it + span) } ?: wanted
    if (!dragging && origin != start) origin = start
    val current by rememberUpdatedState(plan)
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(Ket27Desk.Radius.menu)).background(ket.card)
        .onPointerEvent(PointerEventType.Scroll) { e ->
            if (!e.keyboardModifiers.isCtrlPressed) return@onPointerEvent
            val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
            zoom = (zoom * if (dy > 0) 1.15f else 1 / 1.15f).coerceIn(.35f, 3f)
            e.changes.forEach { it.consume() }
        }.testTag("studio-timeline")) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val labelPx = with(density) { LabelWidth.toPx() }
        val lanePx = (constraints.maxWidth - labelPx).coerceAtLeast(1f)
        val msPerPx = span / lanePx
        val scale by rememberUpdatedState(msPerPx)
        fun x(ms: Long): Float = (ms - start) / msPerPx
        val inToOut = { t: Long -> plan.outMs + ((t - plan.inMs) / plan.rate.toDouble()).toLong() }
        Column(Modifier.fillMaxWidth()) {
            // Ruler
            Row(Modifier.fillMaxWidth().height(RulerHeight)) {
                Spacer(Modifier.width(LabelWidth))
                Canvas(Modifier.weight(1f).fillMaxHeight()) {
                    val step = if (span > 60_000) 10_000L else if (span > 24_000) 5_000L else 2_000L
                    var t = (start / step + 1) * step
                    while (t < start + span) {
                        val px = x(t)
                        drawLine(ket.hairline, Offset(px, size.height - 5.dp.toPx()), Offset(px, size.height), 1f)
                        val label = measurer.measure(clock(t), Ket27Desk.Type.caption2.copy(color = ket.tertiary, fontWeight = FontWeight.Medium))
                        drawText(label, topLeft = Offset(px + 3.dp.toPx(), 3.dp.toPx()))
                        t += step
                    }
                }
            }
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    LaneRow("Sortant", state.out) {
                        Canvas(Modifier.fillMaxSize().testTag("studio-wave-out")) {
                            grid(state.out, { it }, ::x, ket.hairline, ket.tertiary)
                            wave(state.out.wave, { ms -> ms }, ::x, ket.label, fadeAfter = x(plan.outMs + plan.overlapMs))
                            if (plan.mixes) gainCurve(x(plan.outMs), x(plan.outMs + plan.overlapMs), state.choice.shape.volume, falling = true, ket.label)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(.5.dp).background(ket.hairline))
                    LaneRow("Entrant", state.into) {
                        Canvas(Modifier.fillMaxSize().pointerHoverIcon(PointerIcon(Cursor(Cursor.W_RESIZE_CURSOR)))
                            .pointerInput(state) {
                                var from = 0L; var acc = 0f
                                detectDragGestures(onDragStart = { dragging = true; from = current.inMs; acc = 0f },
                                    onDragEnd = { dragging = false }, onDragCancel = { dragging = false }) { change, d ->
                                    change.consume(); acc += d.x
                                    state.moveEntry(from - (acc * scale * current.rate).toLong(), scale)
                                }
                            }.testTag("studio-wave-in")) {
                            grid(state.into, inToOut, ::x, ket.hairline, ket.tertiary)
                            wave(state.into.wave, inToOut, ::x, ket.accent, fadeBefore = x(plan.outMs))
                            if (plan.mixes) gainCurve(x(plan.outMs), x(plan.outMs + plan.overlapMs), state.choice.shape.volume, falling = false, ket.accent)
                        }
                    }
                }
                // The transition box, over both waves.
                if (plan.mixes || plan.overlapMs > 0) {
                    val left = with(density) { (labelPx + x(plan.outMs)).toDp() }
                    val width = with(density) { (plan.overlapMs / msPerPx).coerceAtLeast(6f).toDp() }
                    Box(Modifier.offset(x = left).width(width).height(LaneHeight * 2 + .5.dp)
                        .clip(RoundedCornerShape(8.dp)).background(ket.accent.copy(alpha = if (ket.dark) .14f else .09f))
                        .border(1.5.dp, ket.accent.copy(alpha = .85f), RoundedCornerShape(8.dp))
                        .pointerHoverIcon(PointerIcon(Cursor(Cursor.MOVE_CURSOR)))
                        .pointerInput(state) {
                            var from = 0L; var acc = 0f
                            detectDragGestures(onDragStart = { dragging = true; from = current.outMs; acc = 0f },
                                onDragEnd = { dragging = false }, onDragCancel = { dragging = false }) { change, d ->
                                change.consume(); acc += d.x
                                state.moveExit(from + (acc * scale).toLong(), scale)
                            }
                        }.testTag("studio-box")) {
                        if (width >= 84.dp) KText(AppText.t("Mix de {0} s").replace("{0}", "%.1f".format(java.util.Locale.ROOT, plan.overlapMs / 1000f)),
                            Ket27Desk.Type.caption2, Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 6.dp), color = ket.accent, translate = false)
                        // The right edge: the length of the blend.
                        Box(Modifier.align(Alignment.CenterEnd).width(10.dp).fillMaxHeight()
                            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                            .pointerInput(state) {
                                var from = 0L; var acc = 0f
                                detectDragGestures(onDragStart = { dragging = true; from = current.overlapMs; acc = 0f },
                                    onDragEnd = { dragging = false }, onDragCancel = { dragging = false }) { change, d ->
                                    change.consume(); acc += d.x
                                    state.resize(from + (acc * scale).toLong())
                                }
                            }.testTag("studio-box-edge")) {
                            Box(Modifier.align(Alignment.Center).width(3.dp).height(28.dp).clip(RoundedCornerShape(2.dp)).background(ket.accent))
                        }
                    }
                }
                if (playheadMs != null) {
                    val at = with(density) { (labelPx + x(playheadMs)).toDp() }
                    Box(Modifier.offset(x = at).width(1.5.dp).height(LaneHeight * 2 + .5.dp).background(ket.label).testTag("studio-playhead"))
                }
            }
            Box(Modifier.fillMaxWidth().height(.5.dp).background(ket.hairline))
            KeysLane(state, plan, msPerPx, start, ::x) { dragging = it }
        }
    }
}

@Composable
private fun LaneRow(label: String, side: StudioSide, content: @Composable () -> Unit) {
    val ket = LocalKet.current
    Row(Modifier.fillMaxWidth().height(LaneHeight)) {
        Column(Modifier.width(LabelWidth).fillMaxHeight().padding(start = 14.dp, end = 8.dp), verticalArrangement = Arrangement.Center) {
            KText(label, Ket27Desk.Type.caption2, color = ket.secondary)
            KText(side.track.title, Ket27Desk.Type.caption, color = ket.label, translate = false)
            KText(side.track.artist, Ket27Desk.Type.caption, color = ket.tertiary, translate = false)
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            content()
            when {
                side.failed -> KText("Ce titre n'a pas pu être lu", Ket27Desk.Type.footnote, Modifier.align(Alignment.Center), color = ket.secondary)
                side.wave == null -> Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    if (side.loading) { Spinner(Modifier.size(14.dp)); Spacer(Modifier.width(8.dp)) }
                    KText("Onde en attente", Ket27Desk.Type.footnote, color = ket.secondary)
                }
            }
        }
    }
}

/** The wave of [wave]: one bar every two pixels, its peak faint and its level full. [toOut] maps the title's time to the timeline. */
private fun DrawScope.wave(wave: Wave?, toOut: (Long) -> Long, x: (Long) -> Float, color: Color, fadeBefore: Float = -1f, fadeAfter: Float = Float.MAX_VALUE) {
    wave ?: return
    val mid = size.height / 2
    val half = size.height * .42f
    // From a timeline pixel to the title's own time: the inverse of toOut, linear.
    val a = toOut(0L); val b = toOut(100_000L)
    val outPerTitle = (b - a) / 100_000.0
    val x0 = x(0L); val x1 = x(1_000L)
    val pxPerMs = (x1 - x0) / 1000.0
    var px = 0f
    while (px < size.width) {
        val outMs = ((px - x0) / pxPerMs).toLong()
        val titleMs = ((outMs - a) / outPerTitle).toLong()
        val i = wave.columnAt(titleMs)
        if (i >= 0) {
            val dim = px < fadeBefore || px > fadeAfter
            val peak = wave.peakAt(i).coerceIn(0f, 1f) * half
            val rms = (wave.level(i) * 1.6f).coerceIn(0f, 1f) * half
            drawLine(color.copy(alpha = if (dim) .10f else .22f), Offset(px, mid - peak), Offset(px, mid + peak), 1.4f)
            drawLine(color.copy(alpha = if (dim) .25f else .72f), Offset(px, mid - rms), Offset(px, mid + rms), 1.4f)
        }
        px += 2f
    }
}

/** Bars and phrases of a side's beat grid: what the magnet holds to. */
private fun DrawScope.grid(side: StudioSide, toOut: (Long) -> Long, x: (Long) -> Float, line: Color, strong: Color) {
    val analysis = side.analysis ?: return
    val from = if (side.tail) (side.planDurationMs - MixEditor.TAIL_MS).coerceAtLeast(0L) else 0L
    val until = if (side.tail) side.planDurationMs else minOf(side.planDurationMs, MixEditor.HEAD_MS)
    for ((ms, kind) in GridSnap.beats(analysis, from, until)) {
        if (kind == GridSnap.Kind.Beat) continue
        val px = x(toOut(ms))
        if (px < 0 || px > size.width) continue
        val phrase = kind == GridSnap.Kind.Phrase
        drawLine(if (phrase) strong.copy(alpha = .55f) else line, Offset(px, 0f), Offset(px, if (phrase) size.height else 8.dp.toPx()), 1f)
    }
}

/** The volume the planner gives one title over the blend ([MixShaping.rise]), as a thin line over its wave. */
private fun DrawScope.gainCurve(from: Float, to: Float, curve: VolumeCurve, falling: Boolean, color: Color) {
    if (to - from < 2f) return
    val top = size.height * .1f; val bottom = size.height * .9f
    val path = Path()
    val steps = 32
    for (s in 0..steps) {
        val f = s / steps.toFloat()
        val g = if (falling) MixShaping.fall(f, curve) else MixShaping.rise(f, curve)
        val p = Offset(from + (to - from) * f, bottom - (bottom - top) * g)
        if (s == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    drawPath(path, color.copy(alpha = .8f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
}

/**
 * The keyframes under the waves: the music level of both titles from [MixKeys], a dot per keyframe. Double-click
 * adds one, a drag moves it along the blend (on the beat while the magnet is on), a click opens its levels.
 */
@Composable
private fun KeysLane(state: StudioState, plan: MixPlan, msPerPx: Float, start: Long, x: (Long) -> Float, onDragging: (Boolean) -> Unit) {
    val ket = LocalKet.current
    val current by rememberUpdatedState(plan)
    val scale by rememberUpdatedState(msPerPx)
    val origin by rememberUpdatedState(start)
    val keys = state.choice.keys
    Row(Modifier.fillMaxWidth().height(KeysHeight)) {
        Column(Modifier.width(LabelWidth).fillMaxHeight().padding(start = 14.dp, end = 8.dp), verticalArrangement = Arrangement.Center) {
            KText("Points de volume", Ket27Desk.Type.caption2, color = ket.secondary, maxLines = 2)
        }
        Canvas(Modifier.weight(1f).fillMaxHeight()
            .pointerInput(state) {
                detectTapGestures(
                    onDoubleTap = { o -> val p = current; if (p.mixes) state.addKey((o.x * scale).toLong() + origin - p.outMs, scale) },
                    onTap = { o ->
                        val p = current
                        val rel = (o.x * scale).toLong() + origin - p.outMs
                        state.selectedKey = MixKeys.near(state.choice.keys, rel, (10 * scale).toLong())
                    })
            }
            .pointerInput(state) {
                var index = -1; var from = 0L; var acc = 0f
                detectDragGestures(onDragStart = { o ->
                    val p = current
                    val rel = (o.x * scale).toLong() + origin - p.outMs
                    index = MixKeys.near(state.choice.keys, rel, (10 * scale).toLong())
                    from = state.choice.keys.getOrNull(index)?.atMs ?: 0L; acc = 0f
                    if (index >= 0) { state.selectedKey = index; onDragging(true) }
                }, onDragEnd = { index = -1; onDragging(false) }, onDragCancel = { index = -1; onDragging(false) }) { change, d ->
                    if (index < 0) return@detectDragGestures
                    change.consume(); acc += d.x
                    state.moveKey(index, from + (acc * scale).toLong(), scale)
                }
            }.testTag("studio-keys")) {
            if (!plan.mixes) return@Canvas
            val x0 = x(plan.outMs); val x1 = x(plan.outMs + plan.overlapMs)
            val top = 12.dp.toPx(); val bottom = size.height - 12.dp.toPx()
            drawRoundRect(ket.fill, Offset(x0, top - 4.dp.toPx()), Size((x1 - x0).coerceAtLeast(1f), bottom - top + 8.dp.toPx()), CornerRadius(6.dp.toPx()))
            fun level(outgoing: Boolean, color: Color) {
                val path = Path()
                val steps = 48
                for (s in 0..steps) {
                    val rel = plan.overlapMs * s / steps
                    val v = MixKeys.valueAt(keys, plan.overlapMs, outgoing, rel) { it.music } / 100f
                    val p = Offset(x(plan.outMs + rel), bottom - (bottom - top) * v.coerceIn(0f, 1f))
                    if (s == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, color, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
            }
            level(true, ket.label.copy(alpha = .7f))
            level(false, ket.accent)
            keys.forEachIndexed { i, k ->
                val px = x(plan.outMs + k.atMs)
                val selected = i == state.selectedKey
                drawLine(ket.hairline, Offset(px, top - 4.dp.toPx()), Offset(px, bottom + 4.dp.toPx()), 1f)
                for ((v, c) in listOf(k.out.music to ket.label, k.into.music to ket.accent)) {
                    val y = bottom - (bottom - top) * (v / 100f)
                    drawCircle(c, if (selected) 6.dp.toPx() else 4.5.dp.toPx(), Offset(px, y))
                    drawCircle(ket.card, if (selected) 3.dp.toPx() else 2.dp.toPx(), Offset(px, y))
                }
            }
        }
    }
}

private fun clock(ms: Long): String { val s = ms.coerceAtLeast(0L) / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

// ---- Panels under the timeline ----

@Composable
private fun Panel(modifier: Modifier, title: String, content: @Composable ColumnScope.() -> Unit) {
    val ket = LocalKet.current
    Column(modifier) {
        KText(title, Ket27Desk.Type.footnote, Modifier.padding(start = 4.dp, bottom = 8.dp), color = ket.secondary, weight = FontWeight.SemiBold)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Ket27Desk.Radius.menu)).background(ket.card), content = content)
    }
}

private val styleLabels = listOf("Automatique", "Calé sur le tempo", "Écho", "Filtres", "Fondu", "Montée", "Coupe", "Sur le drop", "Relais des voix")
private val volumeLabels = listOf("Automatique", "Doux", "Linéaire", "Rapide")
private val bassLabels = listOf("Automatique", "Échange court", "Échange moyen", "Échange long", "Aucun échange")
private val filterLabels = listOf("Automatique", "Aucun", "Passe-haut", "Passe-bas")

@Composable
private fun TransitionPanel(state: StudioState, plan: MixPlan) {
    val c = state.choice
    val style = c.style?.takeIf { it != MixStyle.Auto }?.ordinal ?: 0
    DetailRow("Style de transition", style, styleLabels, "studio-style") { i -> state.style(if (i == 0) null else MixStyle.values()[i]) }
    Hairline()
    DetailRow("Volume", c.shape.volume.ordinal, volumeLabels, "studio-volume", enabled = MixShaping.hasVolume(plan)) {
        state.shape(c.shape.copy(volume = VolumeCurve.values()[it]))
    }
    Hairline()
    DetailRow("Basses", c.shape.bass.ordinal, bassLabels, "studio-bass", enabled = MixShaping.hasBass(plan)) {
        state.shape(c.shape.copy(bass = BassSwap.values()[it]))
    }
    Hairline()
    DetailRow("Filtre", c.shape.filter.ordinal, filterLabels, "studio-filter", enabled = MixShaping.hasFilter(plan)) {
        state.shape(c.shape.copy(filter = FilterSweep.values()[it]))
    }
    Hairline()
    ToggleRow("Calage du tempo", c.shape.tempo, "studio-tempo") { state.shape(c.shape.copy(tempo = it)) }
}

@Composable
private fun DetailRow(label: String, selected: Int, options: List<String>, tag: String, enabled: Boolean = true, onPick: (Int) -> Unit) {
    val ket = LocalKet.current
    val menu = LocalMenu.current
    val origin = remember { arrayOf(Offset.Zero) }
    Row(Modifier.fillMaxWidth().height(40.dp)
        .onGloballyPositioned { origin[0] = it.positionInWindow() + Offset(it.size.width.toFloat() - 240f, it.size.height.toFloat()) }
        .clickable(enabled) {
            menu.open(options.mapIndexed { i, o -> MenuItem(o, if (i == selected) KetIcons.Check else null, tag = "$tag:$i") { onPick(i) } },
                IntOffset(origin[0].x.toInt(), origin[0].y.toInt()))
        }.padding(horizontal = 16.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        KText(label, Ket27Desk.Type.subheadline, Modifier.weight(1f), color = if (enabled) ket.label else ket.tertiary)
        KText(options.getOrElse(selected) { options.first() }, Ket27Desk.Type.subheadline, color = if (enabled) ket.secondary else ket.tertiary, weight = FontWeight.Normal)
        Icon(DeskGlyphs.ChevronDown, null, Modifier.padding(start = 6.dp).size(12.dp), tint = ket.tertiary)
    }
}

@Composable
private fun KeysPanel(state: StudioState, plan: MixPlan) {
    val ket = LocalKet.current
    val index = state.selectedKey
    val key = state.choice.keys.getOrNull(index)
    if (key == null) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            val n = state.choice.keys.size
            KText(when (n) { 0 -> AppText.t("Ajouter un point de volume"); 1 -> AppText.t("1 point de volume")
                else -> AppText.t("{0} points de volume").replace("{0}", n.toString()) }, Ket27Desk.Type.subheadline, translate = false)
            Spacer(Modifier.height(4.dp))
            KText(if (plan.mixes) "Double-cliquez sous les ondes pour placer un point, faites-le glisser pour le déplacer."
                else "Ces deux titres ne seront pas mixés.", Ket27Desk.Type.footnote, color = ket.secondary, maxLines = 3)
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        KText(AppText.t("Point de volume") + " · " + MixEditor.clock(plan.outMs + key.atMs), Ket27Desk.Type.subheadline, Modifier.weight(1f),
            weight = FontWeight.SemiBold, translate = false)
        KetIconButton(KetIcons.Trash, "Supprimer le point", { state.removeKey(index) }, Modifier.testTag("studio-key-delete"), glyph = 15.dp)
    }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        LevelColumn(Modifier.weight(1f), "Sortant", key.out, ket.label, "out") { state.setLevels(index, outgoing = it) }
        LevelColumn(Modifier.weight(1f), "Entrant", key.into, ket.accent, "in") { state.setLevels(index, incoming = it) }
    }
    if (!com.ketvoice.app.stems.StemModel.anyReady) {
        KText("Séparez les deux titres pour régler leur voix et leurs instruments.", Ket27Desk.Type.caption,
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), color = ket.tertiary, maxLines = 2)
    }
}

@Composable
private fun LevelColumn(modifier: Modifier, title: String, levels: Levels, color: Color, tag: String, onChange: (Levels) -> Unit) {
    val ket = LocalKet.current
    Column(modifier) {
        KText(title, Ket27Desk.Type.caption2, Modifier.padding(bottom = 4.dp), color = ket.secondary)
        for ((label, value, set) in listOf<Triple<String, Int, (Int) -> Levels>>(
            Triple("Voix", levels.voice) { v -> levels.copy(voice = v) },
            Triple("Instruments", levels.instruments) { v -> levels.copy(instruments = v) },
            Triple("Musique", levels.music) { v -> levels.copy(music = v) })) {
            Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                KText(label, Ket27Desk.Type.footnote, Modifier.width(86.dp))
                ThinSlider(value / 100f, { onChange(set((it * 100).toInt())) }, Modifier.weight(1f), tag = "studio-level-$tag-$label", color = color)
                KText("$value %", Ket27Desk.Type.caption, Modifier.width(46.dp).padding(start = 8.dp), color = ket.secondary, translate = false)
            }
        }
    }
}

// ---- Dialogs ----

@Composable
private fun StudioAlert(title: String, message: String?, onDismiss: () -> Unit, width: androidx.compose.ui.unit.Dp = 380.dp,
                        content: (@Composable ColumnScope.() -> Unit)? = null, actions: @Composable () -> Unit) {
    val ket = LocalKet.current
    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true),
        onKeyEvent = { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onDismiss(); true } else false }) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (ket.dark) .45f else .25f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss), contentAlignment = Alignment.Center) {
            Column(Modifier.width(width).shadow(24.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(ket.raised)
                .clickable(remember { MutableInteractionSource() }, null) {}.padding(22.dp).testTag("studio-dialog")) {
                KText(title, Ket27Desk.Type.headline, weight = FontWeight.SemiBold, maxLines = 2)
                if (message != null) {
                    Spacer(Modifier.height(4.dp))
                    KText(message, Ket27Desk.Type.footnote, color = ket.secondary, maxLines = 4)
                }
                if (content != null) { Spacer(Modifier.height(14.dp)); content() }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    CapsuleButton("Annuler", null, onDismiss, filled = false, accent = ket.accent, modifier = Modifier.testTag("studio-dialog-cancel"))
                    actions()
                }
            }
        }
    }
}

@Composable
private fun DialogAction(label: String, color: Color, tag: String, filled: Boolean = false, onClick: () -> Unit) =
    CapsuleButton(label, null, onClick, filled = filled, accent = color, modifier = Modifier.testTag(tag))

@Composable
private fun PresetsDialog(state: StudioState, onDismiss: () -> Unit, onApplied: (MixPreset) -> Unit, onNew: () -> Unit, onRename: (MixPreset) -> Unit) {
    val ket = LocalKet.current
    val revision by MixPresetStore.revision.collectAsState()
    val presets = remember(revision) { MixPresetStore.all() }
    var deleting by remember { mutableStateOf<MixPreset?>(null) }
    StudioAlert("Mes préréglages", "Le style, la longueur, les détails et les points de volume d'une transition, à reprendre sur d'autres titres.",
        onDismiss, width = 460.dp, content = {
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(10.dp)).background(ket.card)
                .verticalScroll(rememberScrollState()).testTag("studio-preset-list")) {
                if (presets.isEmpty()) KText("Aucun préréglage pour l'instant.", Ket27Desk.Type.footnote, Modifier.padding(16.dp), color = ket.secondary)
                presets.forEachIndexed { i, p ->
                    if (i > 0) Hairline()
                    val (source, hovered) = rememberHover()
                    Row(Modifier.fillMaxWidth().height(48.dp).background(if (hovered) ket.hover else Color.Transparent)
                        .hoverable(source).clickable { state.applyPreset(p); onApplied(p); onDismiss() }
                        .padding(start = 14.dp, end = 4.dp).testTag("studio-preset:${p.name}"), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            KText(p.name, Ket27Desk.Type.subheadline, translate = false)
                            KText(summary(p), Ket27Desk.Type.caption, color = ket.secondary, translate = false)
                        }
                        KetIconButton(KetIcons.Settings, "Renommer", { onRename(p) }, Modifier.testTag("studio-preset-rename"), glyph = 14.dp)
                        KetIconButton(KetIcons.Trash, "Supprimer", { deleting = p }, Modifier.testTag("studio-preset-delete"), glyph = 14.dp)
                    }
                }
            }
        }) {
        DialogAction("Enregistrer cette transition", ket.accent, "studio-preset-new", filled = true, onClick = onNew)
    }
    deleting?.let { p ->
        ConfirmDialog(p.name, AppText.t("Supprimer"), "Supprimer", { MixPresetStore.delete(p.id) }, { deleting = null })
    }
}

private fun summary(p: MixPreset): String {
    val style = AppText.t(styleLabels[p.style?.takeIf { it != MixStyle.Auto }?.ordinal ?: 0])
    val length = if (p.overlapMs > 0) " · " + AppText.t("Mix de {0} s").replace("{0}", "%.1f".format(java.util.Locale.ROOT, p.overlapMs / 1000f)) else ""
    val keys = when (p.keys.size) { 0 -> ""; 1 -> " · " + AppText.t("1 point de volume")
        else -> " · " + AppText.t("{0} points de volume").replace("{0}", p.keys.size.toString()) }
    return style + length + keys
}

@Composable
private fun TextBox(value: String, onChange: (String) -> Unit, placeholder: String, tag: String, singleLine: Boolean = true) {
    val ket = LocalKet.current
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxWidth().heightIn(min = 34.dp).clip(RoundedCornerShape(8.dp)).background(ket.fill).padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) KText(placeholder, Ket27Desk.Type.subheadline, color = ket.tertiary)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().focusRequester(focus).textEntry().testTag(tag), singleLine = singleLine,
            maxLines = if (singleLine) 1 else 4, cursorBrush = SolidColor(ket.accent),
            textStyle = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.subheadline).copy(color = ket.label))
    }
}

@Composable
private fun NameDialog(renaming: MixPreset?, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val ket = LocalKet.current
    var name by remember { mutableStateOf(renaming?.name.orEmpty()) }
    StudioAlert(if (renaming != null) "Renommer le préréglage" else "Nouveau préréglage", null, onDismiss, content = {
        TextBox(name, { name = it.take(MixPreset.NAME_MAX) }, "Nom", "studio-preset-name")
    }) {
        DialogAction("Enregistrer", if (MixPreset.cleanName(name).isEmpty()) ket.accent.copy(alpha = .4f) else ket.accent, "studio-preset-name-ok",
            filled = true) { if (MixPreset.cleanName(name).isNotEmpty()) onDone(name) }
    }
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImport: (MixShare.Shared) -> Unit) {
    val ket = LocalKet.current
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    StudioAlert("Importer un mix", "Collez le code d'un mix reçu d'un ami. Il s'applique à cette transition.", onDismiss, width = 460.dp, content = {
        TextBox(code, { code = it.take(6_000); wrong = false }, "Code du mix", "studio-import-code", singleLine = false)
        if (wrong) KText("Ce code n'est pas un mix Ketmix.", Ket27Desk.Type.footnote, Modifier.padding(top = 8.dp).testTag("studio-import-wrong"), color = destructiveRed)
    }) {
        DialogAction("Importer", ket.accent, "studio-import-ok", filled = true) {
            val shared = MixShare.find(code)
            if (shared == null) wrong = true else onImport(shared)
        }
    }
}

// ---- Settings › Séparation des voix: « Voix prêtes à l'avance » ----

/** The switch and, under it, what the library preparation is doing. */
@Composable
internal fun LibraryVoicesRow() {
    val ket = LocalKet.current
    val progress by com.ketvoice.app.stems.StemLibrary.progress.collectAsState()
    ToggleRow("Voix prêtes à l'avance", com.ketvoice.app.data.AppSettings.ketmixLibrary.value, "stems-library") {
        com.ketvoice.app.data.AppSettings.ketmixLibrary.value = it
        if (it) com.ketvoice.app.stems.StemLibrary.wake(delayMs = 2_000L)
    }
    KText(if (progress.running) AppText.t("Préparation en cours : {0} sur {1}").replace("{0}", progress.done.toString()).replace("{1}", progress.total.toString())
        else AppText.t("Sur secteur, les titres téléchargés sont séparés à l'avance (300 au plus, le modèle rapide d'abord) : leur voix et leurs mix sont prêts tout de suite."),
        Ket27Desk.Type.footnote, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).testTag("stems-library-note"), color = ket.secondary,
        maxLines = 3, translate = false)
}
