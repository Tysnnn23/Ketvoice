package com.ketvoice.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Track
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import androidx.compose.material3.Text as MaterialText

/** Text in Inter, localized through AppText (French is the source). */
@Composable
fun KText(text: String, style: TextStyle = Ket27Desk.Type.body, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
          weight: FontWeight? = null, maxLines: Int = 1, translate: Boolean = true) {
    MaterialText(if (translate) AppText.t(text) else text, modifier,
        style = androidx.compose.material3.LocalTextStyle.current.merge(style).let { if (weight != null) it.copy(fontWeight = weight) else it },
        color = if (color == Color.Unspecified) LocalKet.current.label else color,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** A label in the Ket 27 manner, shown under the pointer after a short wait. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tooltip(label: String, content: @Composable () -> Unit) {
    val ket = LocalKet.current
    TooltipArea(
        tooltip = {
            Box(Modifier.shadow(6.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(ket.raised)
                .border(.5.dp, ket.hairline, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                KText(label, Ket27Desk.Type.caption, color = ket.label)
            }
        },
        delayMillis = 600,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp)),
        content = content,
    )
}

/** A symbol button: round hover well, accent when [active], a spring press. */
@Composable
fun KetIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 32.dp,
                  glyph: Dp = 18.dp, active: Boolean = false, tint: Color = Color.Unspecified, enabled: Boolean = true) {
    val ket = LocalKet.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) .88f else 1f, Motion.pressSpring(pressed))
    Tooltip(label) {
        Box(
            modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
                .background(if (hovered && enabled) ket.hover else Color.Transparent)
                .hoverable(source)
                .pointerInput(enabled) {
                    detectTapGestures(onPress = { if (enabled) { pressed = true; tryAwaitRelease(); pressed = false } }, onTap = { if (enabled) onClick() })
                }
                .semantics { contentDescription = AppText.t(label) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(glyph), tint = when {
                !enabled -> ket.tertiary
                active -> ket.accent
                tint != Color.Unspecified -> tint
                else -> ket.label.copy(alpha = .86f)
            })
        }
    }
}

/** A capsule button (« Lecture », « Aléatoire »): filled with the accent, or tinted. */
@Composable
fun CapsuleButton(label: String, icon: ImageVector?, onClick: () -> Unit, filled: Boolean, accent: Color, modifier: Modifier = Modifier) {
    val ket = LocalKet.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg = if (filled) accent else ket.fill
    Row(
        modifier.height(36.dp).widthIn(min = 120.dp).clip(RoundedCornerShape(10.dp))
            .background(if (hovered) bg.copy(alpha = (bg.alpha * .85f)) else bg)
            .hoverable(source).clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        val fg = if (filled) Color.White else accent
        if (icon != null) { Icon(icon, null, Modifier.size(16.dp), tint = fg); Spacer(Modifier.width(8.dp)) }
        KText(label, Ket27Desk.Type.subheadline, color = fg, weight = FontWeight.SemiBold)
    }
}

/** A section's header, with an optional « Tout afficher » on the right. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = Ket27Desk.Space.sectionTop, bottom = Ket27Desk.Space.sectionBottom),
        verticalAlignment = Alignment.CenterVertically) {
        KText(title, Ket27Desk.Type.title2, Modifier.weight(1f))
        action?.invoke(this)
    }
}

/** Download progress ring: an arc on a faint track, a check once complete. */
@Composable
fun ProgressRing(progress: Float, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val ket = LocalKet.current
    Canvas(modifier.size(size).semantics { contentDescription = "${(progress * 100).toInt()} %" }) {
        val w = 2.dp.toPx()
        drawCircle(ket.fill, radius = this.size.minDimension / 2 - w / 2, style = Stroke(w))
        drawArc(ket.accent, -90f, 360f * progress.coerceIn(0f, 1f), false, topLeft = Offset(w / 2, w / 2),
            size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** A thin seek or volume bar; the knob shows while hovered or dragged. */
@Composable
fun ThinSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, onDone: (Float) -> Unit = {},
               tag: String = "slider", color: Color = Color.Unspecified, track: Color = Color.Unspecified) {
    val ket = LocalKet.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    var width by remember { mutableStateOf(1f) }
    var local by remember { mutableStateOf(value) }
    if (!dragging) local = value
    val active = hovered || dragging
    val h by animateFloatAsState(if (active) 6f else 4f, Motion.Springs.SNAPPY.motion())
    Box(
        modifier.height(16.dp).hoverable(source).testTag(tag)
            .onGloballyPositioned { width = it.size.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { o -> val v = (o.x / width).coerceIn(0f, 1f); onChange(v); onDone(v) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o -> dragging = true; local = (o.x / width).coerceIn(0f, 1f); onChange(local) },
                    onDragEnd = { dragging = false; onDone(local) },
                    onDragCancel = { dragging = false },
                ) { change, _ -> local = (change.position.x / width).coerceIn(0f, 1f); onChange(local) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val fg = if (color == Color.Unspecified) ket.label.copy(alpha = if (active) .9f else .55f) else color
        val bg = if (track == Color.Unspecified) ket.fill else track
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val y = size.height / 2
            val hp = h.dp.toPx()
            drawLine(bg, Offset(0f, y), Offset(size.width, y), hp, StrokeCap.Round)
            drawLine(fg, Offset(0f, y), Offset(size.width * local, y), hp, StrokeCap.Round)
            if (active) drawCircle(Color.White, 6.dp.toPx(), Offset(size.width * local, y))
        }
    }
}

/* ── Drag a title onto a playlist of the sidebar ── */

@Stable
class TrackDrag {
    var track by mutableStateOf<Track?>(null); private set
    var pointer by mutableStateOf(Offset.Zero); private set
    var hoveredTarget by mutableStateOf<String?>(null); private set
    private val targets = HashMap<String, Rect>()

    fun register(id: String, bounds: Rect) { targets[id] = bounds }
    fun unregister(id: String) { targets.remove(id) }
    fun start(t: Track, at: Offset) { track = t; pointer = at; hoveredTarget = targetAt(at) }
    fun move(at: Offset) { pointer = at; hoveredTarget = targetAt(at) }
    /** The playlist under the pointer when the title was let go, if any. */
    fun drop(): Pair<String, Track>? {
        val result = hoveredTarget?.let { id -> track?.let { id to it } }
        track = null; hoveredTarget = null
        return result
    }
    fun cancel() { track = null; hoveredTarget = null }
    private fun targetAt(p: Offset) = targets.entries.firstOrNull { it.value.contains(p) }?.key
}

val LocalTrackDrag = staticCompositionLocalOf { TrackDrag() }

/** Lets the row be dragged as [track] (onto a sidebar playlist). */
fun Modifier.draggableTrack(track: Track, drag: TrackDrag, onDrop: (String, Track) -> Unit): Modifier = composed {
    val origin = remember { arrayOf(Offset.Zero) }
    val latest by rememberUpdatedState(onDrop)
    onGloballyPositioned { origin[0] = it.positionInWindow() }
        .pointerInput(track.id) {
            detectDragGestures(
                onDragStart = { o -> drag.start(track, origin[0] + o) },
                onDragEnd = { drag.drop()?.let { (id, t) -> latest(id, t) } },
                onDragCancel = { drag.cancel() },
            ) { change, _ -> drag.move(origin[0] + change.position) }
        }
}

/** Marks a sidebar row as a place a title can be dropped on. */
fun Modifier.dropTarget(id: String, drag: TrackDrag): Modifier = onGloballyPositioned { drag.register(id, it.boundsInWindow()) }

/* ── Context menus ── */

data class MenuItem(val label: String, val icon: ImageVector? = null, val tag: String = label, val children: List<MenuItem> = emptyList(),
                    val action: () -> Unit = {})

/** Opens [items] where the secondary button was pressed. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.contextMenu(state: MenuState, items: () -> List<MenuItem>): Modifier = composed {
    val origin = remember { arrayOf(Offset.Zero) }
    val latest by rememberUpdatedState(items)
    onGloballyPositioned { origin[0] = it.positionInWindow() }
        .onPointerEvent(PointerEventType.Press) { e ->
            if (e.buttons.isSecondaryPressed) {
                val p = e.changes.first().position
                state.open(latest(), IntOffset((origin[0].x + p.x).toInt(), (origin[0].y + p.y).toInt()))
            }
        }
}

@Stable
class MenuState {
    var items by mutableStateOf<List<MenuItem>>(emptyList()); private set
    var at by mutableStateOf(IntOffset.Zero); private set
    fun open(list: List<MenuItem>, position: IntOffset) { items = list; at = position }
    fun close() { items = emptyList() }
    val isOpen get() = items.isNotEmpty()
}

val LocalMenu = staticCompositionLocalOf { MenuState() }

/** The one context menu of the window, grown from the pointer with a spring (GrowFromSource's manner). */
@Composable
fun ContextMenuHost(state: MenuState) {
    if (!state.isOpen) return
    val at = state.at
    Popup(
        popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
                                           layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize) =
                IntOffset(at.x.coerceAtMost(windowSize.width - popupContentSize.width - 8).coerceAtLeast(8),
                    at.y.coerceAtMost(windowSize.height - popupContentSize.height - 8).coerceAtLeast(8))
        },
        onDismissRequest = { state.close() },
        properties = PopupProperties(focusable = true),
    ) {
        MenuPanel(state.items, onPick = { state.close(); it.action() })
    }
}

@Composable
private fun MenuPanel(items: List<MenuItem>, onPick: (MenuItem) -> Unit) {
    val ket = LocalKet.current
    val grow = remember { Animatable(if (Motion.reduced) 1f else .92f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, Motion.Springs.GROW.motion()) }
    var submenu by remember { mutableStateOf<MenuItem?>(null) }
    Row {
        Column(
            Modifier.graphicsLayer { scaleX = grow.value; scaleY = grow.value; alpha = ((grow.value - .92f) / .08f).coerceIn(0f, 1f); transformOrigin = TransformOrigin(0f, 0f) }
                .width(IntrinsicSize.Max).widthIn(min = 220.dp)
                .shadow(16.dp, RoundedCornerShape(Ket27Desk.Radius.menu), ambientColor = Color.Black.copy(.3f), spotColor = Color.Black.copy(.3f))
                .clip(RoundedCornerShape(Ket27Desk.Radius.menu)).background(ket.raised)
                .border(.5.dp, ket.hairline, RoundedCornerShape(Ket27Desk.Radius.menu)).padding(5.dp).testTag("context-menu"),
        ) {
            items.forEach { item ->
                if (item.label == "-") { Box(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp).height(.5.dp).background(ket.hairline)); return@forEach }
                MenuRow(item, onPick = { if (item.children.isNotEmpty()) submenu = item else onPick(item) },
                    onHover = { submenu = if (item.children.isNotEmpty()) item else null })
            }
        }
        submenu?.let { sub -> Box(Modifier.padding(start = 4.dp, top = 5.dp)) { MenuPanel(sub.children, onPick) } }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun MenuRow(item: MenuItem, onPick: () -> Unit, onHover: () -> Unit) {
    val ket = LocalKet.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(7.dp))
            .background(if (hovered) ket.accent else Color.Transparent)
            .hoverable(source).onPointerEvent(PointerEventType.Enter) { onHover() }
            .clickable(onClick = onPick).padding(horizontal = 10.dp).testTag("menu:${item.tag}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (hovered) Color.White else ket.label
        if (item.icon != null) { Icon(item.icon, null, Modifier.size(15.dp), tint = fg); Spacer(Modifier.width(10.dp)) }
        KText(item.label, Ket27Desk.Type.subheadline, Modifier.weight(1f), color = fg, translate = item.tag != "playlist")
        if (item.children.isNotEmpty()) Icon(com.ketvoice.app.ui.common.KetIcons.ChevronRight, null, Modifier.size(12.dp), tint = fg)
    }
}

/** Hover highlight for rows and cards. */
@Composable
fun rememberHover(): Pair<MutableInteractionSource, Boolean> {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    return source to hovered
}

internal val secondaryButton = PointerButton.Secondary
internal fun <T> stateListOf() = mutableStateListOf<T>()
