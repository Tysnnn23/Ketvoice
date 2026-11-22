package com.ketvoice.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.glass.Cristal
import com.ketvoice.app.glass.FrozenBackdrop
import com.ketvoice.app.glass.FrozenStills
import com.ketvoice.app.glass.GlassCompat
import com.ketvoice.app.glass.cristalFrost
import com.ketvoice.app.glass.cristalMenuShadow
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.motion.Motion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * The menus of the Cristal look (8.4), in dp, ms and their springs.
 *
 * A page's « … » menu waits two frames (the heaviest of its opening: every row measured, the still copy of the page
 * taken) and at most 150 ms more for that copy, then grows from the corner nearest its button, from 30 % of its
 * size, on a critically damped spring (stiffness 600), fully opaque by 40 % of the way; it closes in 170 ms.
 * A title's held menu lifts the title as a plate and opens its menu from 55 % on a softer spring (0.72, 420),
 * closing in 230 ms. Under reduced motion both only fade, quickly, without scaling.
 */
internal object CristalMenuMotion {
    const val PAGE_STIFFNESS = 600f
    const val PAGE_CLOSE_MS = 170
    const val PAGE_REDUCED_OPEN_MS = 120
    const val PAGE_REDUCED_CLOSE_MS = 100
    const val PAGE_FROM = .3f
    const val PAGE_ALPHA_RATE = 2.5f
    const val STILL_WAIT_MS = 150L
    const val HELD_DAMPING = .72f
    const val HELD_STIFFNESS = 420f
    const val HELD_CLOSE_MS = 230
    const val HELD_REDUCED_OPEN_MS = 140
    const val HELD_REDUCED_CLOSE_MS = 110
    const val HELD_FROM = .55f

    // Sizes.
    const val WIDTH_DP = 256f
    const val CORNER_DP = 30f
    const val PAGE_MARGIN_DP = 14f
    const val PAGE_TOP_DP = 8f
    const val PAGE_BOTTOM_DP = 12f
    const val HELD_MARGIN_DP = 12f
    const val HELD_GAP_DP = 10f
    const val HELD_EDGE_DP = 8f
    const val PLATE_CORNER_DP = 20f
    const val PLATE_PADDING_DP = 8f
    const val PLATE_SHADOW_DP = 20f

    fun pageOpen(reduced: Boolean): AnimationSpec<Float> =
        if (reduced) tween(PAGE_REDUCED_OPEN_MS, easing = LinearEasing) else spring(Spring.DampingRatioNoBouncy, PAGE_STIFFNESS)
    fun pageClose(reduced: Boolean): AnimationSpec<Float> =
        tween(if (reduced) PAGE_REDUCED_CLOSE_MS else PAGE_CLOSE_MS, easing = FastOutSlowInEasing)
    fun heldOpen(reduced: Boolean): AnimationSpec<Float> =
        if (reduced) tween(HELD_REDUCED_OPEN_MS, easing = LinearEasing) else spring(HELD_DAMPING, HELD_STIFFNESS)
    fun heldClose(reduced: Boolean): AnimationSpec<Float> =
        tween(if (reduced) HELD_REDUCED_CLOSE_MS else HELD_CLOSE_MS, easing = FastOutSlowInEasing)

    fun pageAlpha(p: Float): Float = (p * PAGE_ALPHA_RATE).coerceIn(0f, 1f)
    fun pageScale(p: Float): Float = lerp(PAGE_FROM, 1f, p)
    fun heldMenuAlpha(p: Float): Float = ((p - .1f) / .9f).coerceIn(0f, 1f)
    fun heldMenuScale(p: Float): Float = lerp(HELD_FROM, 1f, p)
    fun plateAlpha(p: Float): Float = (p * 3f).coerceIn(0f, 1f)

    /** Before growing: two frames, then the still copy of the page if one is on its way (at most [STILL_WAIT_MS]). */
    suspend fun awaitStill() {
        withFrameNanos { }
        withFrameNanos { }
        if (GlassCompat.api < android.os.Build.VERSION_CODES.S || AppSettings.reduceDynamicBlur.value || !AppSettings.liquidGlass.value) return
        withTimeoutOrNull(STILL_WAIT_MS) { snapshotFlow { FrozenStills.taking.intValue }.first { it == 0 } }
    }

    /** Where a page menu goes (px): under its button's right edge, hanging down from the top half, else at the bottom. */
    data class PagePlace(val x: Int, val y: Int, val hangsDown: Boolean)

    fun placePage(anchor: Rect?, width: Int, height: Int, panelW: Int, panelH: Int, margin: Int, top: Int, bottom: Int): PagePlace {
        val right = anchor?.right?.roundToInt() ?: (width - margin)
        val x = (right - panelW).coerceIn(margin, (width - margin - panelW).coerceAtLeast(margin))
        val lowest = (bottom - panelH).coerceAtLeast(top)
        val hangsDown = anchor != null && anchor.center.y < height / 2f
        val y = if (hangsDown) anchor!!.top.roundToInt().coerceIn(top, lowest) else lowest
        return PagePlace(x, y, hangsDown)
    }

    /** Where a held menu's plate and menu go (px); [scale] is the plate's lift, the menu hangs [below] it or above. */
    data class HeldPlace(val plateX: Int, val plateY: Int, val scale: Float, val fromX: Float, val fromY: Float,
                         val menuX: Int, val menuY: Int, val alignEnd: Boolean, val below: Boolean)

    fun placeHeld(item: Rect, width: Int, plateW: Int, plateH: Int, menuW: Int, menuH: Int, margin: Int, gap: Int,
                  top: Int, bottom: Int): HeldPlace {
        val lift = ((width - margin * 2f) / plateW.coerceAtLeast(1)).coerceAtMost(1f)
        val visualW = plateW * lift
        val visualH = plateH * lift
        val left = (item.center.x - visualW / 2f).coerceIn(margin.toFloat(), (width - margin - visualW).coerceAtLeast(margin.toFloat()))
        var visualTop = (item.center.y - visualH / 2f).coerceIn(top.toFloat(), (bottom - visualH).coerceAtLeast(top.toFloat()))
        val below = when {
            visualTop + visualH + gap + menuH <= bottom -> true
            visualTop - gap - menuH >= top -> false
            else -> { visualTop = (bottom - menuH - gap - visualH).coerceAtLeast(top.toFloat()); true }
        }
        val cx = left + visualW / 2f
        val cy = visualTop + visualH / 2f
        val alignEnd = cx > width / 2f
        val menuX = (if (alignEnd) left + visualW - menuW else left).roundToInt()
            .coerceIn(margin, (width - margin - menuW).coerceAtLeast(margin))
        val menuY = if (below) (visualTop + visualH + gap).roundToInt() else (visualTop - gap - menuH).roundToInt()
        return HeldPlace((cx - plateW / 2f).roundToInt(), (cy - plateH / 2f).roundToInt(), lift,
            item.center.x - cx, item.center.y - cy, menuX, menuY, alignEnd, below)
    }
}

/** The colours of a Cristal menu's rows. */
internal data class CristalMenuInk(val label: Color, val secondary: Color, val separator: Color, val pressed: Color) {
    companion object {
        fun of(dark: Boolean) = if (dark) CristalMenuInk(Color.White, Color.White.copy(alpha = .70f), Color.White.copy(alpha = .10f),
            Color.White.copy(alpha = .10f))
        else CristalMenuInk(Color(0xFF1A1A1A), Color(0xFF1A1A1A).copy(alpha = .55f), Color.Black.copy(alpha = .09f),
            Color.Black.copy(alpha = .07f))
    }
}

/** A line of a Cristal menu: the symbol leading in a 28 dp column, the label, an optional tick at the end. */
@Composable
internal fun CristalMenuRow(label: String, icon: ImageVector?, ink: CristalMenuInk, modifier: Modifier = Modifier,
                            tint: Color? = null, iconTint: Color? = null, enabled: Boolean = true, checked: Boolean = false,
                            localize: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val alpha = if (enabled) 1f else .4f
    val foreground = tint ?: ink.label
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp))
        .background(if (pressed) ink.pressed else Color.Transparent)
        .clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { if (checked) selected = true; role = Role.Button }
        .heightIn(min = 44.dp).padding(start = 12.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, null, tint = (iconTint ?: foreground).let { it.copy(alpha = it.alpha * alpha) }, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, localize = localize, color = foreground.copy(alpha = foreground.alpha * alpha), maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 17.sp, lineHeight = 21.sp, letterSpacing = (-.3).sp), modifier = Modifier.weight(1f))
        if (checked) {
            Spacer(Modifier.width(10.dp))
            Icon(KetIcons.Check, null, tint = ink.label.copy(alpha = ink.label.alpha * alpha), modifier = Modifier.size(17.dp))
        }
    }
}

/** The break between groups: a 1 dp hairline 18 dp in from the panel's edges, 17 dp of height in all. */
@Composable
internal fun CristalMenuSeparator(ink: CristalMenuInk) {
    Box(Modifier.fillMaxWidth().height(17.dp).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(ink.separator))
    }
}

/** A page's « … » menu ([KetMenu]) in the Cristal look: frost over a still copy of the page, grown from its button. */
@Composable
internal fun CristalPageMenu(expanded: Boolean, onDismiss: () -> Unit, sections: List<List<KetMenuItem>>) {
    var shown by remember { mutableStateOf(expanded) }
    val progress = remember { Animatable(0f) }
    val reduced = Motion.reduced
    LaunchedEffect(expanded) {
        if (expanded) {
            shown = true
            if (progress.value == 0f) CristalMenuMotion.awaitStill()
            progress.animateTo(1f, CristalMenuMotion.pageOpen(reduced))
        } else if (shown) {
            progress.animateTo(0f, CristalMenuMotion.pageClose(reduced))
            shown = false
        }
    }
    if (!shown) return
    val density = LocalDensity.current
    val insetTop = WindowInsets.safeDrawing.getTop(density)
    val insetBottom = WindowInsets.safeDrawing.getBottom(density)
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val position = remember {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
                                           layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                           popupContentSize: androidx.compose.ui.unit.IntSize): IntOffset {
                anchor = Rect(anchorBounds.left.toFloat(), anchorBounds.top.toFloat(), anchorBounds.right.toFloat(), anchorBounds.bottom.toFloat())
                return IntOffset.Zero
            }
        }
    }
    androidx.compose.ui.window.Popup(popupPositionProvider = position, onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
        FrozenBackdrop(scale = Cristal.MENU_SCALE) {
            CristalPageMenuLayout(progress, reduced, { anchor }, insetTop, insetBottom, onDismiss) {
                val ink = CristalMenuInk.of(LocalTheme.current.isDark)
                sections.filter { it.isNotEmpty() }.forEachIndexed { index, section ->
                    if (index > 0) CristalMenuSeparator(ink)
                    section.forEach { item ->
                        CristalMenuRow(item.label, item.icon, ink, tint = if (item.destructive) DESTRUCTIVE else null,
                            enabled = item.enabled, checked = item.checked,
                            modifier = if (item.tag != null) Modifier.testTag(item.tag) else Modifier) { onDismiss(); item.onClick() }
                    }
                }
            }
        }
    }
}

@Composable
private fun CristalPageMenuLayout(progress: Animatable<Float, *>, reduced: Boolean, anchor: () -> Rect?, insetTop: Int, insetBottom: Int,
                                  onDismiss: () -> Unit, rows: @Composable () -> Unit) {
    val dark = LocalTheme.current.isDark
    val shape = RoundedCornerShape(CristalMenuMotion.CORNER_DP.dp)
    var hangsDown by remember { mutableStateOf(true) }
    val growth: GraphicsLayerScope.() -> Unit = {
        val p = progress.value
        alpha = CristalMenuMotion.pageAlpha(p)
        if (!reduced) {
            transformOrigin = TransformOrigin(1f, if (hangsDown) 0f else 1f)
            val s = CristalMenuMotion.pageScale(p)
            scaleX = s; scaleY = s
        }
    }
    Layout(modifier = Modifier.fillMaxSize(), content = {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
            .background(Color.Black.copy(alpha = if (dark) .30f else .14f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss))
        Box(Modifier.cristalMenuShadow(RoundedCornerShape(CristalMenuMotion.CORNER_DP.dp)))
        Box(Modifier.pointerInput(Unit) {}.cristalFrost(shape, dark, shadow = false).testTag("ket-menu"), propagateMinConstraints = true) {
            Column(Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)) { rows() }
        }
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val margin = CristalMenuMotion.PAGE_MARGIN_DP.dp.roundToPx()
        val top = insetTop + CristalMenuMotion.PAGE_TOP_DP.dp.roundToPx()
        val bottom = height - insetBottom - CristalMenuMotion.PAGE_BOTTOM_DP.dp.roundToPx()
        val scrim = measurables[0].measure(Constraints.fixed(width, height))
        val panelW = CristalMenuMotion.WIDTH_DP.dp.roundToPx().coerceAtMost(width - margin * 2).coerceAtLeast(0)
        val panel = measurables[2].measure(Constraints(minWidth = panelW, maxWidth = panelW, maxHeight = (bottom - top).coerceAtLeast(0)))
        val plate = measurables[1].measure(Constraints.fixed(panel.width, panel.height))
        val place = CristalMenuMotion.placePage(anchor(), width, height, panel.width, panel.height, margin, top, bottom)
        hangsDown = place.hangsDown
        layout(width, height) {
            scrim.place(0, 0)
            plate.placeWithLayer(place.x, place.y, layerBlock = growth)
            panel.placeWithLayer(place.x, place.y) { growth(); compositingStrategy = CompositingStrategy.Offscreen }
        }
    }
}
