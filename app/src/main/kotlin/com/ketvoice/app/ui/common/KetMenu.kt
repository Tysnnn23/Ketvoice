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

/** One choice of a [KetMenu]: a tick on the leading side when [checked], the symbol on the trailing side, as iOS draws it. */
internal class KetMenuItem(val label: String, val icon: ImageVector? = null, val checked: Boolean = false,
                           val destructive: Boolean = false, val tag: String? = null, val enabled: Boolean = true,
                           val onClick: () -> Unit)

/**
 * The pull-down menu of a page's « … » button (Ket 27): groups of choices on one card, a hairline between
 * groups, a tick beside the order or the view in use. Dismissed by any choice.
 */
@Composable
internal fun KetMenu(expanded: Boolean, onDismiss: () -> Unit, sections: List<List<KetMenuItem>>, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    androidx.compose.material3.DropdownMenu(expanded, onDismiss, offset = DpOffset(0.dp, 6.dp),
        shape = RoundedCornerShape(16.dp), containerColor = theme.card, modifier = modifier.widthIn(min = 230.dp)) {
        val shown = sections.filter { it.isNotEmpty() }
        shown.forEachIndexed { index, section ->
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
