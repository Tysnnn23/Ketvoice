package com.ketvoice.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.motion.growContainer
import com.ketvoice.app.ui.motion.growContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

/** One choice of a [KetMenu]: a tick on the leading side when [checked], the symbol on the trailing side, as iOS draws it. */
internal class KetMenuItem(val label: String, val icon: ImageVector? = null, val checked: Boolean = false,
                           val destructive: Boolean = false, val tag: String? = null, val enabled: Boolean = true,
                           val onClick: () -> Unit)

/**
 * The pull-down menu of a page's « … » button (Ket 27): groups of choices on one card, a hairline between
 * groups, a tick beside the order or the view in use. Dismissed by any choice.
 *
 * 8.0: it grows out of the button it hangs from (GrowFromSource) — from the button's rectangle and corners
 * to its own, on a spring — and folds back into it when it closes; under the button, on the side of the
 * window the button is on, above it when there is no room below.
 */
@Composable
internal fun KetMenu(expanded: Boolean, onDismiss: () -> Unit, sections: List<List<KetMenuItem>>, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Shown while it opens, while it is open and while it folds back; gone once it is back in the button.
    var shown by remember { mutableStateOf(expanded) }
    var anchor by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val root = com.ketvoice.app.ui.motion.rootOnScreen()
    val grow = com.ketvoice.app.ui.motion.rememberGrowTransition(Unit) {
        anchor?.let { com.ketvoice.app.ui.motion.GrowSource(it.translate(root), minOf(it.width, it.height) / 2f) }
    }
    LaunchedEffect(expanded) {
        if (expanded) { shown = true; grow.open() }
        else if (shown) { grow.close(); shown = false }
    }
    if (!shown) return
    val gap = with(density) { 6.dp.roundToPx() }
    val margin = with(density) { 8.dp.roundToPx() }
    val position = remember(gap, margin) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
                                           layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                           popupContentSize: androidx.compose.ui.unit.IntSize): androidx.compose.ui.unit.IntOffset {
                anchor = androidx.compose.ui.geometry.Rect(anchorBounds.left.toFloat(), anchorBounds.top.toFloat(),
                    anchorBounds.right.toFloat(), anchorBounds.bottom.toFloat())
                val p = KetMenuPlacement.place(anchorBounds, windowSize, popupContentSize, gap, margin)
                return p
            }
        }
    }
    androidx.compose.ui.window.Popup(popupPositionProvider = position, onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
        // 8.3: glass over a still copy of the page taken as it opens, so the blur does not swim while it grows.
        com.ketvoice.app.glass.FrozenBackdrop {
        val shape = RoundedCornerShape(16.dp)
        com.ketvoice.app.glass.GlassPanel(modifier.growContainer(grow, 16.dp).testTag("ket-menu"), shape,
            com.ketvoice.app.glass.GlassParams.sheet(with(density) { 16.dp.toPx() }, dark = theme.isDark)) {
            Column(Modifier.widthIn(min = 230.dp).width(IntrinsicSize.Max).background(theme.card.copy(alpha = if (theme.isDark) .7f else .82f))
                .growContent(grow).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                val visible = sections.filter { it.isNotEmpty() }
                visible.forEachIndexed { index, section ->
                    if (index > 0) Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(6.dp).background(theme.hairline.copy(alpha = .5f)))
                    section.forEach { item ->
                        val tint = when {
                            !item.enabled -> theme.secondary
                            item.destructive -> DESTRUCTIVE
                            else -> theme.onBackground
                        }
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.width(26.dp)) {
                                        if (item.checked) Icon(KetIcons.Check, null, tint = theme.onBackground, modifier = Modifier.size(16.dp))
                                    }
                                    Text(item.label, color = tint, style = Ket27.Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f))
                                    if (item.icon != null) Icon(item.icon, null, tint = tint, modifier = Modifier.padding(start = 16.dp).size(19.dp))
                                }
                            },
                            onClick = { onDismiss(); item.onClick() }, enabled = item.enabled,
                            modifier = Modifier.heightIn(min = 46.dp)
                                .semantics { if (item.checked) selected = true; role = Role.Button }
                                .then(if (item.tag != null) Modifier.testTag(item.tag) else Modifier))
                    }
                }
            }
        }
        }
    }
}

/** Where a [KetMenu] goes, in window px: under its button with a gap, flush with the button's side of the window. */
internal object KetMenuPlacement {
    fun place(anchor: androidx.compose.ui.unit.IntRect, window: androidx.compose.ui.unit.IntSize,
              menu: androidx.compose.ui.unit.IntSize, gap: Int, margin: Int): androidx.compose.ui.unit.IntOffset {
        val fromEnd = anchor.center.x > window.width / 2
        val x = (if (fromEnd) anchor.right - menu.width else anchor.left)
            .coerceIn(margin, (window.width - margin - menu.width).coerceAtLeast(margin))
        val below = anchor.bottom + gap
        val y = if (below + menu.height <= window.height - margin) below
            else (anchor.top - gap - menu.height).coerceAtLeast(margin).takeIf { anchor.top - gap - menu.height >= margin }
                ?: (window.height - margin - menu.height).coerceAtLeast(margin)
        return androidx.compose.ui.unit.IntOffset(x, y)
    }
}

/** The round « … » button of a page bar and the menu it pulls down. */
@Composable
internal fun KetMenuButton(sections: List<List<KetMenuItem>>, description: String, modifier: Modifier = Modifier,
                           tag: String? = null, enabled: Boolean = true,
                           icon: androidx.compose.ui.graphics.vector.ImageVector = KetIcons.More) {
    val theme = LocalTheme.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        LiquidGlassButton({ open = true }, Modifier.size(48.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier), enabled = enabled) {
            Icon(icon, description, tint = theme.onBackground, modifier = Modifier.size(22.dp))
        }
        KetMenu(open, { open = false }, sections)
    }
}
