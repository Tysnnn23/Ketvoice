package com.ketvoice.app.ui.chrome

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.glass.Cristal
import com.ketvoice.app.glass.LocalBackdropLayer
import com.ketvoice.app.glass.LocalOnGlass
import com.ketvoice.app.glass.PerformanceMode
import com.ketvoice.app.glass.cristalGlass
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.EBadge
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Ket27Glyphs
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.motion.Haptics
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.rememberHaptics
import com.ketvoice.app.ui.motion.symbolSlide
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Cristal tab bar (8.4): its sizes and its motion, in dp and seconds. Expanded it is the now-playing accessory
 * (48 dp) over the tab capsule (62 dp, Search as its last tab), 19 dp from the screen's sides and 6 dp above the
 * navigation inset; folded it is one row 28 dp in from the sides: the current tab's circle, the accessory and the
 * Search circle, 48 dp each in height, 14 dp apart. The pieces carry over from one layout to the other on one
 * critically damped spring; what appears only in one of them fades on that same spring.
 */
internal object CristalBar {
    const val GUTTER = 19f
    const val LIFT = 6f
    /** The bar never sits lower than a gesture bar's inset, even when Android reports none. */
    const val GESTURE_FLOOR = 15f
    const val HEIGHT = 62f
    const val INSET = 4f
    const val TAB_PADDING = 6.5f
    const val ICON = 28f
    const val ICON_GAP = 1f
    const val LABEL_SP = 10f
    const val LABEL_TRACKING_SP = .5f
    const val LABEL_LINE_SP = 12f
    const val GAP = 8f
    const val ACCESSORY = 48f
    const val INLINE_GUTTER = 28f
    const val INLINE_SPACING = 14f
    const val INLINE_LIFT = 7f
    const val INLINE_SIDE = 48f
    const val SELECTED_SCALE = 1.08f
    const val UNSELECTED_ALPHA = .65f
    /** The tabs of the capsule: Home, Explore, Library, then Search. */
    const val TABS = 4

    // ---- Motion.
    /** The spring the selected glyph pops on: one soft pass beyond the mark, about a third of a second. */
    const val GLYPH_DAMPING = .72f
    const val GLYPH_STIFFNESS = 320f
    /** The fold, and the fade of what is only in one layout (Compose's « medium low » stiffness, no bounce). */
    const val FOLD_DAMPING = 1f
    const val FOLD_STIFFNESS = 400f
    /** Tabs and labels coming back: hidden for the first half of 150 ms, in by 80 %, out of a 12 dp blur. */
    const val ENTER_MS = 150
    const val ENTER_FROM = .5f
    const val ENTER_TO = .8f
    const val ENTER_BLUR_DP = 12f
    /** The accessory under a finger. */
    const val PRESS_SCALE = 1.04f
    const val PRESS_STIFFNESS = 400f
    /** A sideways swipe of this many dp on the accessory changes the title. */
    const val SWIPE_DP = 72f
    /** A title too long for its line fades out over its last 36 dp. */
    const val LINE_FADE_DP = 36f

    /** A piece of the bar: left edge and width, and its foot measured up from the bar's foot, in dp. */
    data class Place(val x: Float, val bottom: Float, val w: Float, val h: Float) {
        fun lerp(to: Place, f: Float) = Place(x + (to.x - x) * f, bottom + (to.bottom - bottom) * f, w + (to.w - w) * f, h + (to.h - h) * f)
    }

    data class Frame(val height: Float, val tabs: Place, val accessory: Place, val search: Place)

    fun expanded(width: Float, track: Boolean): Frame {
        val w = (width - 2 * GUTTER).coerceAtLeast(0f)
        val tabs = Place(GUTTER, 0f, w, HEIGHT)
        val accessory = Place(GUTTER, HEIGHT + GAP, w, ACCESSORY)
        val folded = inline(width, track)
        return Frame(if (track) HEIGHT + GAP + ACCESSORY else HEIGHT, tabs, accessory, folded.search)
    }

    fun inline(width: Float, track: Boolean): Frame {
        val side = INLINE_SIDE
        val tabsX = if (track) INLINE_GUTTER else (width - side * 2 - INLINE_SPACING) / 2f
        val searchX = if (track) width - INLINE_GUTTER - side else tabsX + side + INLINE_SPACING
        val accessoryX = INLINE_GUTTER + side + INLINE_SPACING
        val accessoryW = (width - 2 * INLINE_GUTTER - 2 * side - 2 * INLINE_SPACING).coerceAtLeast(0f)
        return Frame(side + INLINE_LIFT, Place(tabsX, INLINE_LIFT, side, side), Place(accessoryX, INLINE_LIFT, accessoryW, side),
            Place(searchX, INLINE_LIFT, side, side))
    }

    /** The search field: the Search circle stretched over the accessory's place, from the tab circle to the margin. */
    fun field(width: Float): Place {
        val x = INLINE_GUTTER + INLINE_SIDE + INLINE_SPACING
        return Place(x, INLINE_LIFT, (width - INLINE_GUTTER - x).coerceAtLeast(INLINE_SIDE), INLINE_SIDE)
    }

    /** [fold] 0 expanded .. 1 folded; [stretch] 0 .. 1 the Search circle stretched into the field. */
    fun frame(width: Float, track: Boolean, fold: Float, stretch: Float): Frame {
        val f = maxOf(clamp(fold), clamp(stretch))
        val s = clamp(stretch)
        val a = expanded(width, track)
        val b = inline(width, track)
        return Frame(a.height + (b.height - a.height) * f, a.tabs.lerp(b.tabs, f), a.accessory.lerp(b.accessory, f),
            b.search.lerp(field(width), s))
    }

    /** The selected glyph's centre, from its tab of the capsule to the folded circle; y measured up from the foot. */
    fun glyphCenter(width: Float, track: Boolean, selected: Int, f: Float): Offset {
        val a = expanded(width, track).tabs
        val cell = (a.w - 2 * INSET) / TABS
        val from = Offset(a.x + INSET + cell * (selected + .5f), HEIGHT - INSET - TAB_PADDING - ICON / 2f)
        val b = inline(width, track).tabs
        val to = Offset(b.x + b.w / 2f, b.bottom + b.h / 2f)
        val k = clamp(f)
        return Offset(from.x + (to.x - from.x) * k, from.y + (to.y - from.y) * k)
    }

    /** Alpha and blur (dp) of a tab or a label [ms] after the bar began to unfold. */
    fun enter(ms: Float): Pair<Float, Float> {
        val t = if (ms.isFinite()) (ms / ENTER_MS).coerceIn(0f, 1f) else 1f
        val k = FastOutSlowInEasing.transform(((t - ENTER_FROM) / (ENTER_TO - ENTER_FROM)).coerceIn(0f, 1f))
        return k to ENTER_BLUR_DP * (1f - k)
    }

    private fun clamp(v: Float) = if (v.isFinite()) v.coerceIn(0f, 1f) else 0f

    fun glyphSpring(reduced: Boolean): AnimationSpec<Float> =
        if (reduced) snap() else spring(dampingRatio = GLYPH_DAMPING, stiffness = GLYPH_STIFFNESS)

    fun foldSpring(): AnimationSpec<Float> = spring(dampingRatio = FOLD_DAMPING, stiffness = FOLD_STIFFNESS)
}

private data class CristalTab(val icon: ImageVector, val label: String, val index: Int)

/** The phone chrome in the Cristal look ([com.ketvoice.app.glass.GlassLook]); [FloatingChrome] hands over to it. */
@Composable
internal fun CristalChrome(collapsed: Boolean, onOpenPlayer: () -> Unit, searching: Boolean, typing: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("cristal-chrome")) {
        val width = maxWidth.value
        val hasTrack = Player.state.collectAsState().value.current != null
        val track = hasTrack && !typing
        val folded = collapsed && !searching
        val inlineTarget = folded || searching
        val reduced = Motion.reduced
        val fold = remember { Animatable(if (folded) 1f else 0f) }
        val stretch = remember { Animatable(if (searching) 1f else 0f) }
        LaunchedEffect(folded, reduced) { if (reduced) fold.snapTo(if (folded) 1f else 0f) else fold.animateTo(if (folded) 1f else 0f, CristalBar.foldSpring()) }
        LaunchedEffect(searching, reduced) { if (reduced) stretch.snapTo(if (searching) 1f else 0f) else stretch.animateTo(if (searching) 1f else 0f, CristalBar.foldSpring()) }
        // Read while measuring and drawing only: the fold never composes the bar again.
        val frame = remember(width, track) { { CristalBar.frame(width, track, fold.value, stretch.value) } }
        val inlineAmount = remember { { maxOf(fold.value, stretch.value).coerceIn(0f, 1f) } }
        val dark = LocalTheme.current.isDark
        Box(Modifier.fillMaxWidth().layout { measurable, constraints ->
            val h = frame().height.dp.roundToPx().coerceIn(constraints.minHeight, constraints.maxHeight)
            val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
            layout(placeable.width, h) { placeable.place(0, 0) }
        }) {
            androidx.compose.runtime.CompositionLocalProvider(LocalOnGlass provides true) {
                CristalTabs(width, track, frame, inlineAmount, inlineTarget, searching, reduced, dark)
                if (track) CristalNowPlaying(inlineTarget, dark, onOpenPlayer,
                    Modifier.cristalPlace { frame().accessory }.graphicsLayer { alpha = 1f - stretch.value.coerceIn(0f, 1f) })
                CristalSearch(frame, inlineAmount, stretch, searching, dark)
            }
        }
    }
}

/** Measures its content at [place] (dp) and puts it there, the parent's foot being the bar's foot. */
private fun Modifier.cristalPlace(place: () -> CristalBar.Place): Modifier = layout { measurable, constraints ->
    val p = place()
    val w = p.w.dp.roundToPx().coerceAtLeast(0)
    val h = p.h.dp.roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place(p.x.dp.roundToPx(), constraints.maxHeight - (p.bottom + p.h).dp.roundToPx())
    }
}

@Composable
private fun CristalTabs(width: Float, track: Boolean, frame: () -> CristalBar.Frame, inlineAmount: () -> Float,
                        inlineTarget: Boolean, searching: Boolean, reduced: Boolean, dark: Boolean) {
    val tabs = remember {
        listOf(CristalTab(Ket27Glyphs.Home, "Accueil", AppUi.HOME), CristalTab(Ket27Glyphs.Explore, "Explorer", AppUi.EXPLORER),
            CristalTab(Ket27Glyphs.Library, "Bibliothèque", AppUi.LIBRARY), CristalTab(Ket27Glyphs.Search, "Rechercher", -1))
    }
    val selected = tabs.indexOfFirst { it.index == AppUi.tab.value }.coerceIn(0, 2)
    val shape = remember { RoundedCornerShape(percent = 50) }
    val haptic = rememberHaptics()
    val focus = LocalFocusManager.current
    // The pieces only one layout has come in as the bar unfolds: a clock, in ms, from the moment it starts to.
    // Decided while composing, so the first frame of the unfolded capsule already has its tabs hidden.
    val wasInline = remember { booleanArrayOf(inlineTarget) }
    val enter = remember(inlineTarget) {
        Animatable(if (wasInline[0] && !inlineTarget && !reduced) 0f else CristalBar.ENTER_MS.toFloat())
    }
    androidx.compose.runtime.SideEffect { wasInline[0] = inlineTarget }
    LaunchedEffect(enter) {
        if (enter.value < CristalBar.ENTER_MS) enter.animateTo(CristalBar.ENTER_MS.toFloat(), tween(CristalBar.ENTER_MS, easing = LinearEasing))
    }
    // The selected glyph is drawn in its tab at rest, and on its own while it travels between the two layouts.
    val atRest by remember(inlineTarget) { derivedStateOf { !inlineTarget && inlineAmount() == 0f } }
    // A fresh selection each time the capsule unfolds: it starts on its tab, it does not travel there.
    val pill = remember(inlineTarget) { CristalPillMotion(selected) }
    androidx.compose.runtime.SideEffect { pill.reduced = reduced }
    LaunchedEffect(pill) { pill.run() }
    val backdrop = LocalBackdropLayer.current
    val glassPill = com.ketvoice.app.glass.GlassCompat.api >= Build.VERSION_CODES.S && backdrop != null && AppSettings.liquidGlass.value &&
        !AppSettings.reduceDynamicBlur.value
    val barGlass = rememberLayerBackdrop()
    val tabRow = rememberLayerBackdrop()
    val lensUp = glassPill && !pill.isFlat && !inlineTarget
    Box(Modifier.cristalPlace { frame().tabs }.testTag("cristal-tabs")) {
        Box(Modifier.fillMaxSize().clip(shape).cristalGlass(shape, dark, shadow = false, export = if (lensUp) barGlass else null)
            .then(if (inlineTarget) Modifier.clickable(remember { MutableInteractionSource() }, null, role = Role.Button,
                onClickLabel = AppText.t(if (searching) "Fermer la recherche" else "Afficher les onglets")) {
                focus.clearFocus(); haptic(Haptics.Kind.Tick)
                if (searching) AppUi.closeOverlay() else ChromeCollapse.restoreAll()
            } else Modifier)) {
            if (!inlineTarget) CristalTabRow(width, tabs, selected, pill, enter, atRest, reduced, dark, glassPill, tabRow)
        }
        if (lensUp && backdrop != null) CristalLensPill(pill, width, backdrop, barGlass, tabRow, dark)
    }
    if (!atRest) CristalTravellingGlyph(width, track, tabs[selected], selected, inlineAmount, inlineTarget, searching, dark)
}

/** The tab row of the expanded capsule, laid out at its full size inside the capsule whatever the capsule's size. */
@Composable
private fun CristalTabRow(width: Float, tabs: List<CristalTab>, selected: Int, pill: CristalPillMotion, enter: Animatable<Float, *>,
                          atRest: Boolean, reduced: Boolean, dark: Boolean, glassPill: Boolean, tabRow: LayerBackdrop) {
    val density = LocalDensity.current
    val haptic = rememberHaptics()
    val focus = LocalFocusManager.current
    val innerW = (width - 2 * CristalBar.GUTTER - 2 * CristalBar.INSET).coerceAtLeast(0f)
    val innerH = CristalBar.HEIGHT - 2 * CristalBar.INSET
    val step = innerW / CristalBar.TABS
    pill.stepDp = step
    LaunchedEffect(selected) { pill.select(selected) }
    val stepPx = with(density) { step.dp.toPx() }
    val currentStep by rememberUpdatedState(stepPx)
    val currentSelected by rememberUpdatedState(selected)
    val indicator = Cristal.indicator(dark)
    Box(Modifier.padding(CristalBar.INSET.dp).layout { measurable, constraints ->
        val placeable = measurable.measure(Constraints.fixed(innerW.dp.roundToPx(), innerH.dp.roundToPx()))
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
    }) {
        if (pill.isFlat || !glassPill) Box(Modifier.fillMaxSize().pillPlace(pill, step, innerH, density).background(indicator, CircleShape)
            .testTag("cristal-flat-pill"))
        Row(Modifier.fillMaxSize().layerBackdrop(tabRow).pointerInput(Unit) {
            var total = 0f
            var start = 0
            var holding = false
            var lastTick = -1
            detectHorizontalDragGestures(
                onDragStart = {
                    holding = currentStep > 0f
                    if (!holding) return@detectHorizontalDragGestures
                    total = 0f; start = currentSelected; lastTick = start
                    pill.grab()
                },
                onDragCancel = { if (holding) pill.letGo(currentSelected); holding = false },
                onDragEnd = {
                    if (!holding) return@detectHorizontalDragGestures
                    holding = false
                    val next = (start + total / currentStep).coerceIn(0f, 2f).roundToInt()
                    pill.letGo(next)
                    if (next != currentSelected) { focus.clearFocus(); AppUi.selectTab(tabs[next].index) }
                },
                onHorizontalDrag = { change, dx ->
                    if (!holding) return@detectHorizontalDragGestures
                    change.consume()
                    total += dx
                    val at = (start + total / currentStep).coerceIn(0f, 2f)
                    pill.hold(at)
                    if (at.roundToInt() != lastTick) { haptic(Haptics.Kind.Tick); lastTick = at.roundToInt() }
                })
        }) {
            tabs.forEachIndexed { i, tab ->
                CristalTabCell(tab, i == selected, glyphShown = i != selected || atRest, enter, reduced, dark) {
                    focus.clearFocus()
                    if (tab.index < 0) { haptic(Haptics.Kind.Press); FloatingSearch.open() }
                    else { if (i != selected) haptic(Haptics.Kind.Tick); AppUi.selectTab(tab.index) }
                }
            }
        }
    }
}

/** Puts the flat pill at the selection's place in the row ([step] and [rowH] in dp). */
private fun Modifier.pillPlace(pill: CristalPillMotion, step: Float, rowH: Float, density: Density): Modifier = layout { measurable, constraints ->
    val rest = with(density) { Size(step.dp.toPx(), rowH.dp.toPx()) }
    val lifted = with(density) { CristalPillPhysics.liftedSize(rest, CristalBar.INSET.dp.toPx(), CristalBar.INSET.dp.toPx(), CristalPillPhysics.GROW_DP.dp.toPx()) }
    val s = pill.size(rest, lifted)
    val w = s.width.roundToInt().coerceAtLeast(1)
    val h = s.height.roundToInt().coerceAtLeast(1)
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(constraints.maxWidth, constraints.maxHeight) {
        val cx = pill.position * rest.width + rest.width / 2f
        placeable.place((cx - w / 2f).roundToInt(), (rest.height / 2f - h / 2f).roundToInt())
    }
}

@Composable
private fun RowScope.CristalTabCell(tab: CristalTab, selected: Boolean, glyphShown: Boolean, enter: Animatable<Float, *>,
                                    reduced: Boolean, dark: Boolean, onClick: () -> Unit) {
    val content = Cristal.content(dark)
    val tint = if (selected) content else content.copy(alpha = CristalBar.UNSELECTED_ALPHA)
    val scale = animateFloatAsState(if (selected) CristalBar.SELECTED_SCALE else 1f, CristalBar.glyphSpring(reduced), label = "cristal-glyph")
    val density = LocalDensity.current.density
    val entering: GraphicsLayerScope.() -> Unit = { applyEnter(enter.value, density) }
    Column(
        Modifier.weight(1f).fillMaxSize().clip(CircleShape)
            .clickable(remember { MutableInteractionSource() }, androidx.compose.material3.ripple(), role = if (tab.index < 0) Role.Button else Role.Tab,
                onClick = onClick)
            .semantics { this.selected = selected && tab.index >= 0 }
            .padding(vertical = CristalBar.TAB_PADDING.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(CristalBar.ICON.dp).graphicsLayer {
            if (!glyphShown) { alpha = 0f; return@graphicsLayer }
            scaleX = scale.value; scaleY = scale.value
            // The selected glyph is the one that carries over between the two layouts; the others come in.
            if (!selected) entering()
        })
        Text(tab.label, modifier = Modifier.padding(top = CristalBar.ICON_GAP.dp).graphicsLayer(entering), color = tint,
            style = TextStyle(fontSize = CristalBar.LABEL_SP.sp, letterSpacing = CristalBar.LABEL_TRACKING_SP.sp,
                lineHeight = CristalBar.LABEL_LINE_SP.sp, fontWeight = FontWeight.Medium),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun GraphicsLayerScope.applyEnter(ms: Float, density: Float) {
    val (alpha, blurDp) = CristalBar.enter(ms)
    this.alpha = alpha
    // No effect at rest: a layer that holds one is drawn offscreen for as long as it does.
    renderEffect = if (blurDp > 0f) BlurEffect(blurDp * density, blurDp * density) else null
}

/** The selected glyph on its way between its tab and the folded circle, over everything else. */
@Composable
private fun CristalTravellingGlyph(width: Float, track: Boolean, tab: CristalTab, selected: Int, inlineAmount: () -> Float,
                                   inlineTarget: Boolean, searching: Boolean, dark: Boolean) {
    val scale = if (inlineTarget) 1f else CristalBar.SELECTED_SCALE
    Icon(tab.icon, contentDescription = null, tint = Cristal.content(dark), modifier = Modifier.layout { measurable, constraints ->
        val side = CristalBar.ICON.dp.roundToPx()
        val placeable = measurable.measure(Constraints.fixed(side, side))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val c = CristalBar.glyphCenter(width, track, selected, inlineAmount())
            placeable.place((c.x.dp.toPx() - side / 2f).roundToInt(), (constraints.maxHeight - c.y.dp.toPx() - side / 2f).roundToInt())
        }
    }.graphicsLayer { scaleX = scale; scaleY = scale }.testTag(if (searching) "cristal-search-close" else "cristal-folded-tab"))
}

/**
 * The lifted selection: a lens over the bar that bends, bottom to top, the page, the bar's own glass, the selection's
 * fill and the tab row. Clear while it is up (the fill fades as the material comes on), with a rim and a contact
 * shadow that leave first on landing, then the bend. In the performance mode it moves the same way without a lens,
 * and keeps its fill. Android 12 draws no lens either (it needs Android 13).
 */
@Composable
private fun CristalLensPill(pill: CristalPillMotion, width: Float, page: Backdrop, barGlass: LayerBackdrop, tabRow: LayerBackdrop,
                            dark: Boolean) {
    val density = LocalDensity.current
    val indicator = Cristal.indicator(dark)
    val shape = remember { RoundedCornerShape(percent = 50) }
    val composite = remember(page, barGlass, tabRow, indicator) {
        PillBackdrop(page, barGlass, tabRow) {
            if (PerformanceMode()) indicator else indicator.copy(alpha = indicator.alpha * (1f - pill.material))
        }
    }
    val innerW = (width - 2 * CristalBar.GUTTER - 2 * CristalBar.INSET).coerceAtLeast(0f)
    val step = innerW / CristalBar.TABS
    val rowH = CristalBar.HEIGHT - 2 * CristalBar.INSET
    Box(Modifier.fillMaxSize().layout { measurable, constraints ->
        val rest = Size(step.dp.toPx(), rowH.dp.toPx())
        val lifted = CristalPillPhysics.liftedSize(rest, CristalBar.INSET.dp.toPx(), CristalBar.INSET.dp.toPx(), CristalPillPhysics.GROW_DP.dp.toPx())
        val s = pill.size(rest, lifted)
        val w = s.width.roundToInt().coerceAtLeast(1)
        val h = s.height.roundToInt().coerceAtLeast(1)
        val placeable = measurable.measure(Constraints.fixed(w, h))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val inset = CristalBar.INSET.dp.toPx()
            val cx = inset + pill.position * rest.width + rest.width / 2f
            val cy = inset + rest.height / 2f
            placeable.place((cx - w / 2f).roundToInt(), (cy - h / 2f).roundToInt())
        }
    }.testTag("cristal-lens-pill").drawBackdrop(
        backdrop = composite,
        shape = { shape },
        effects = {
            if (!PerformanceMode() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lens(refractionHeight = Cristal.PILL_LENS_HEIGHT_DP * density.density * pill.glassPresence,
                    refractionAmount = Cristal.PILL_LENS_AMOUNT_DP * density.density * pill.material,
                    depthEffect = false, chromaticAberration = true)
            }
        },
        highlight = {
            val a = pill.material * CristalPillPhysics.rim(pill.glassPresence)
            if (a > 0f) Highlight.Default.copy(alpha = a) else null
        },
        shadow = {
            val a = pill.material * CristalPillPhysics.rim(pill.glassPresence)
            if (a > 0f) Cristal.PILL_SHADOW.copy(alpha = a) else null
        },
        innerShadow = { null }
    ))
}

/** What the lifted pill bends: the page, the bar's glass, the selection's fill (under the tabs) and the tab row. */
private class PillBackdrop(private val page: Backdrop, private val bar: Backdrop, private val tabs: Backdrop,
                           private val fill: () -> Color) : Backdrop {
    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?,
                                        layerBlock: (GraphicsLayerScope.() -> Unit)?) {
        with(page) { drawBackdrop(density, coordinates, layerBlock) }
        with(bar) { drawBackdrop(density, coordinates, layerBlock) }
        val color = fill()
        // Past every edge: what is recorded includes the lens's margin.
        if (color.alpha > 0f) drawRect(color, topLeft = Offset(-size.width, -size.height), size = Size(size.width * 3f, size.height * 3f))
        with(tabs) { drawBackdrop(density, coordinates, layerBlock) }
    }
}

/** The folded row's Search circle, which stretches into the search field. */
@Composable
private fun CristalSearch(frame: () -> CristalBar.Frame, inlineAmount: () -> Float, stretch: Animatable<Float, *>, searching: Boolean,
                          dark: Boolean) {
    val shown by remember { derivedStateOf { inlineAmount() > 0f } }
    if (!shown && !searching) return
    val shape = remember { RoundedCornerShape(percent = 50) }
    val haptic = rememberHaptics()
    val content = Cristal.content(dark)
    Box(Modifier.cristalPlace { frame().search }.graphicsLayer { alpha = inlineAmount() }.testTag("cristal-search")) {
        Box(Modifier.fillMaxSize().clip(shape).cristalGlass(shape, dark, shadow = false)
            .then(if (!searching) Modifier.clickable(remember { MutableInteractionSource() }, null, role = Role.Button) {
                haptic(Haptics.Kind.Press); FloatingSearch.open()
            } else Modifier), contentAlignment = Alignment.Center) {
            val glyphAlpha = { (1f - stretch.value * 2.2f).coerceIn(0f, 1f) }
            val glyph by remember { derivedStateOf { glyphAlpha() > 0f } }
            if (!searching || glyph) Icon(Ket27Glyphs.Search, contentDescription = if (searching) null else "Rechercher", tint = content,
                modifier = Modifier.size(CristalBar.ICON.dp).graphicsLayer { alpha = glyphAlpha() })
        }
        if (searching) CristalSearchField(stretch)
    }
}

@Composable
private fun CristalSearchField(stretch: Animatable<Float, *>) {
    val requester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val asked = FloatingSearch.focusAsked.value
    LaunchedEffect(asked) {
        if (asked) {
            // A frame for the field to be placed before it takes the focus (and brings the keyboard).
            kotlinx.coroutines.delay(32)
            runCatching { requester.requestFocus() }
            FloatingSearch.focusAsked.value = false
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { focus.clearFocus() } }
    com.ketvoice.app.ui.common.CompactSearchField(FloatingSearch.query.value, { FloatingSearch.query.value = it },
        { FloatingSearch.onSubmit?.invoke() },
        Modifier.fillMaxSize().padding(horizontal = 6.dp).graphicsLayer { alpha = ((stretch.value - .5f) / .4f).coerceIn(0f, 1f) }
            .focusRequester(requester).testTag("floating-search-field"),
        filled = false)
}

/**
 * The now-playing accessory: the same row at two densities. Expanded, a 40 dp play button and Next; folded, a 32 dp
 * play button and no Next. The whole glass swells by 4 % under a finger; a sideways swipe changes the title.
 */
@Composable
private fun CristalNowPlaying(inline: Boolean, dark: Boolean, onOpen: () -> Unit, modifier: Modifier) {
    val ps = Player.state.collectAsState().value
    val current = ps.current ?: return
    val art by ArtworkRepo.covers.collectAsState()
    val cover = com.ketvoice.app.data.MediaOverrides.cover(current, art["t:${current.id}"] ?: current.coverUrl)
    val content = Cristal.content(dark)
    val secondary = content.copy(alpha = .7f)
    val shape = remember { RoundedCornerShape(percent = 50) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduced = Motion.reduced
    val press = animateFloatAsState(if (pressed) CristalBar.PRESS_SCALE else 1f,
        if (reduced) snap() else spring(stiffness = CristalBar.PRESS_STIFFNESS), label = "cristal-press")
    val haptic = rememberHaptics()
    val glyphSlot = if (inline) 32.dp else 40.dp
    val glyph = if (inline) 24.dp else 29.dp
    Box(modifier
        .cristalSkipSwipe(haptic)
        .graphicsLayer { scaleX = press.value; scaleY = press.value }
        .onGloballyPositioned { AppUi.miniPlayerBounds.value = it.boundsInRoot() }
        .clip(shape).cristalGlass(shape, dark, shadow = false)
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction(AppText.t("Suivant")) { Player.next(); true },
                CustomAccessibilityAction(AppText.t("Précédent")) { Player.previous(); true })
        }
        .clickable(source, null) { onOpen() }
        .testTag("cristal-now-playing"), contentAlignment = Alignment.Center) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = if (inline) 8.dp else 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val canvases by com.ketvoice.app.data.CanvasRepo.paths.collectAsState()
            val canvas = canvases[current.id]?.takeIf { !com.ketvoice.app.data.MediaOverrides.canvasHiddenInPlayer(current) }?.takeIf {
                AppSettings.animatedArtwork.value && !reduced && !AppSettings.highPerformanceMode.value
            }
            val artShape = RoundedCornerShape(5.7.dp)
            Box(Modifier.size(30.4.dp).onGloballyPositioned { AppUi.miniCoverBounds.value = it.boundsInRoot() }.clip(artShape)) {
                if (canvas != null) com.ketvoice.app.ui.player.ArtworkCanvas(current.copy(coverUrl = cover), canvas, ps.isPlaying, Modifier.fillMaxSize())
                else Cover(current.title, cover, size = 30.4.dp, radius = 5.7.dp)
            }
            Spacer(Modifier.width(if (inline) 8.dp else 9.dp))
            Column(Modifier.weight(1f).onGloballyPositioned { com.ketvoice.app.ui.player.MiniPlayerAnchors.title = it.boundsInRoot() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (current.explicit) { EBadge(); Spacer(Modifier.width(4.dp)) }
                    FadingLine(current.title, TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .15.sp), content,
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                FadingLine(current.artist, TextStyle(fontSize = 12.sp, letterSpacing = .35.sp), secondary)
            }
            Box(Modifier.size(glyphSlot).onGloballyPositioned { com.ketvoice.app.ui.player.MiniPlayerAnchors.play = it.boundsInRoot() }
                .clip(CircleShape).clickable(remember { MutableInteractionSource() }, null) { haptic(Haptics.Kind.Press); Player.toggle() },
                contentAlignment = Alignment.Center) {
                com.ketvoice.app.ui.player.MorphingTransportGlyph(ps.playWhenReady, Modifier.size(glyph), content)
            }
            if (!inline) {
                Spacer(Modifier.width(4.dp))
                var taps by remember { mutableIntStateOf(0) }
                Box(Modifier.size(40.dp).onGloballyPositioned { com.ketvoice.app.ui.player.MiniPlayerAnchors.next = it.boundsInRoot() }
                    .clip(CircleShape).clickable(remember { MutableInteractionSource() }, null) { taps++; haptic(Haptics.Kind.Press); Player.next() }
                    .testTag("mini-next"), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Suivant", tint = content,
                        modifier = Modifier.size(25.dp).symbolSlide(taps, 1))
                }
            }
        }
    }
}

/** One line that, only where it is too long for its place, fades out over its last [CristalBar.LINE_FADE_DP]. */
@Composable
private fun FadingLine(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    var overflows by remember(text) { mutableStateOf(false) }
    Text(text, localize = false, color = color, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
        onTextLayout = { overflows = it.hasVisualOverflow },
        modifier = modifier.then(if (!overflows) Modifier else Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fade = CristalBar.LINE_FADE_DP.dp.toPx()
                drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
                    blendMode = BlendMode.DstIn)
            }))
}

/** A sideways swipe of [CristalBar.SWIPE_DP] or more on the accessory: towards the start is the next title. */
private fun Modifier.cristalSkipSwipe(haptic: (Haptics.Kind) -> Unit): Modifier = pointerInput(Unit) {
    var dragged = 0f
    detectHorizontalDragGestures(
        onDragStart = { dragged = 0f },
        onDragEnd = {
            val threshold = CristalBar.SWIPE_DP.dp.toPx()
            when {
                dragged <= -threshold -> { haptic(Haptics.Kind.Tick); Player.next() }
                dragged >= threshold -> { haptic(Haptics.Kind.Tick); Player.previous() }
            }
        },
        onHorizontalDrag = { change, dx -> change.consume(); dragged += dx })
}
