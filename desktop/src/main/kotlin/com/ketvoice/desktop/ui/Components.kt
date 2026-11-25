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
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.compose.ui.semantics.role
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
    val click by rememberUpdatedState(onClick)
    var pressed by remember { mutableStateOf(false) }
    // A press sinks it, the release bounces it back; under the pointer the glyph swells a little, as through a drop.
    val scale by animateFloatAsState(if (pressed) .86f else 1f, Motion.pressSpring(pressed))
    val lens by animateFloatAsState(if (hovered && enabled && !Motion.reduced) 1.12f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = .5f, stiffness = 520f))
    Tooltip(label) {
        Box(
            modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
                .background(if (hovered && enabled) ket.hover else Color.Transparent)
                .hoverable(source)
                .pointerInput(enabled) {
                    detectTapGestures(onPress = { if (enabled) { pressed = true; tryAwaitRelease(); pressed = false } }, onTap = { if (enabled) click() })
                }
                .keyboardControl(source, CircleShape, enabled) { click() }
                .semantics { contentDescription = AppText.t(label) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(glyph).graphicsLayer { scaleX = lens; scaleY = lens }, tint = when {
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
    val click by rememberUpdatedState(onClick)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) .94f else 1f, Motion.pressSpring(pressed))
    Row(
        modifier.magnetic(.12f, 3f).height(40.dp).widthIn(min = 128.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(20.dp))
            .background(if (hovered) bg.copy(alpha = (bg.alpha * .85f)) else bg)
            .hoverable(source)
            .pointerInput(Unit) { detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }, onTap = { click() }) }
            .keyboardControl(source, RoundedCornerShape(20.dp)) { click() }
            .semantics(mergeDescendants = true) { role = androidx.compose.ui.semantics.Role.Button }
            .padding(horizontal = 20.dp),
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
        KText(title, Ket27Desk.Type.title2, Modifier.weight(1f), weight = FontWeight.Bold)
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
    // Dragged past an end, the bar stretches that way (a rubber band) and springs back when let go.
    var pull by remember { mutableStateOf(0f) }
    val stretch by animateFloatAsState(if (dragging) pull else 0f, if (dragging) androidx.compose.animation.core.snap()
        else androidx.compose.animation.core.spring(dampingRatio = .42f, stiffness = 420f))
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
                    onDragStart = { o -> dragging = true; pull = 0f; local = (o.x / width).coerceIn(0f, 1f); onChange(local) },
                    onDragEnd = { dragging = false; pull = 0f; onDone(local) },
                    onDragCancel = { dragging = false; pull = 0f },
                ) { change, _ ->
                    val raw = change.position.x / width
                    local = raw.coerceIn(0f, 1f)
                    pull = if (Motion.reduced) 0f else ElasticSlider.pull((raw - local) * width, width)
                    onChange(local)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val fg = if (color == Color.Unspecified) ket.label.copy(alpha = if (active) .9f else .55f) else color
        val bg = if (track == Color.Unspecified) ket.fill else track
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val y = size.height / 2
            val s = stretch
            // The stretched end moves out, the bar thins a little as it is pulled.
            val start = if (s < 0f) s else 0f
            val end = size.width + (if (s > 0f) s else 0f)
            val hp = h.dp.toPx() * ElasticSlider.thin(s, size.width)
            drawLine(bg, Offset(start, y), Offset(end, y), hp, StrokeCap.Round)
            drawLine(fg, Offset(start, y), Offset(start + (end - start) * local, y), hp, StrokeCap.Round)
            if (active) drawCircle(Color.White, 6.dp.toPx(), Offset(start + (end - start) * local, y))
        }
    }
}

/** The rubber band of [ThinSlider]: how far its end follows a drag past it, and how it thins. */
internal object ElasticSlider {
    /** At most this share of the bar's width. */
    const val MAX = .06f

    fun pull(over: Float, width: Float): Float = FluidScroll.rubber(over, width * MAX)

    fun thin(pull: Float, width: Float): Float = 1f - (kotlin.math.abs(pull) / (width * MAX).coerceAtLeast(1f)) * .25f
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
                    val translate: Boolean = true, val action: () -> Unit = {})

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
    /** The point the menu grows out of (the button that opened it, or the pointer). */
    var from by mutableStateOf<IntOffset?>(null); private set
    fun open(list: List<MenuItem>, position: IntOffset, from: IntOffset? = null) { items = list; at = position; this.from = from ?: position }
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
        var placed by remember { mutableStateOf(IntOffset.Zero) }
        Box(Modifier.onGloballyPositioned { val p = it.positionInWindow(); placed = IntOffset(p.x.toInt(), p.y.toInt()) }) {
            MenuPanel(state.items, onPick = { state.close(); it.action() }, origin = { size -> MenuGrow.origin(state.from, placed, size) })
        }
    }
}

@Composable
private fun MenuPanel(items: List<MenuItem>, onPick: (MenuItem) -> Unit, origin: (androidx.compose.ui.geometry.Size) -> TransformOrigin = { TransformOrigin(0f, 0f) }) {
    val ket = LocalKet.current
    val grow = remember { Animatable(if (Motion.reduced) 1f else .82f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, Motion.Springs.GROW.motion()) }
    var submenu by remember { mutableStateOf<MenuItem?>(null) }
    Row {
        Column(
            Modifier.graphicsLayer { scaleX = grow.value; scaleY = grow.value; alpha = ((grow.value - .82f) / .18f).coerceIn(0f, 1f); transformOrigin = origin(size) }
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

/** Where a menu grows from, as a fraction of its own size: the button's place relative to where the menu landed. */
internal object MenuGrow {
    fun origin(from: IntOffset?, placed: IntOffset, size: androidx.compose.ui.geometry.Size): TransformOrigin {
        if (from == null || size.width <= 0f || size.height <= 0f) return TransformOrigin(0f, 0f)
        return TransformOrigin(((from.x - placed.x) / size.width).coerceIn(0f, 1f), ((from.y - placed.y) / size.height).coerceIn(0f, 1f))
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
        KText(item.label, Ket27Desk.Type.subheadline, Modifier.weight(1f), color = fg, translate = item.translate && item.tag != "playlist")
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

/**
 * Keyboard use of a control drawn with pointer gestures (Windows 2.0): Tab reaches it, Enter activates it, and a ring in
 * the accent shows only while the keyboard has it (a pointer click does not move the focus).
 */
internal fun Modifier.keyboardControl(source: MutableInteractionSource, shape: androidx.compose.ui.graphics.Shape, enabled: Boolean = true,
                                      onActivate: () -> Unit): Modifier = composed {
    val activate by rememberUpdatedState(onActivate)
    this.onKeyEvent { e ->
        if (enabled && e.type == KeyEventType.KeyUp && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { activate(); true } else false
    }.focusable(enabled, source).focusRing(source, shape)
}

/** The accent ring of [keyboardControl], for controls already focusable (`clickable` with this [source]). */
internal fun Modifier.focusRing(source: MutableInteractionSource, shape: androidx.compose.ui.graphics.Shape): Modifier = composed {
    val focused by source.collectIsFocusedAsState()
    if (focused) Modifier.border(2.dp, LocalKet.current.accent, shape) else Modifier
}

internal val secondaryButton = PointerButton.Secondary
internal fun <T> stateListOf() = mutableStateListOf<T>()
