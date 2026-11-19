package com.ketvoice.app.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.motion.Motion

/**
 * A filter or a sort order as a pill of glass (Ket 27): the one chosen is filled with the page's text
 * colour, the others are clear glass over the page. Keeps a 48 dp target and says whether it is chosen.
 */
@Composable
fun CollectionFilter(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(if (selected) theme.onBackground else theme.onBackground.copy(alpha = 0f),
        androidx.compose.animation.core.tween(Motion.fastMs()), label = "collection-filter-color")
    val ink by animateColorAsState(if (selected) theme.background else theme.onBackground,
        androidx.compose.animation.core.tween(Motion.fastMs()), label = "collection-filter-ink")
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed && !Motion.reduced) .96f else 1f,
        Motion.pressSpring(pressed), label = "collection-filter-press")
    Box(modifier.heightIn(min = 48.dp).semantics { this.selected = selected }
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interaction, null, role = Role.Tab, onClick = onClick), contentAlignment = Alignment.Center) {
        // A filter of the page's content: a plain capsule, not glass (7.1).
        Box(Modifier.heightIn(min = 38.dp).clip(CircleShape).background(plainFill(theme.isDark)).background(fill)
            .padding(horizontal = 16.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
            Text(label, color = ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
