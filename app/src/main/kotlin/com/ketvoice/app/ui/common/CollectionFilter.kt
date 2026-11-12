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

@Composable
fun CollectionFilter(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(if (selected) theme.onBackground else theme.card,
        androidx.compose.animation.core.tween(Motion.fastMs()), label = "collection-filter-color")
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed && !Motion.reduced) .96f else 1f,
        Motion.pressSpring(pressed), label = "collection-filter-press")
    Box(modifier.heightIn(min = 48.dp).semantics { this.selected = selected }
        .graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(background)
        .clickable(interaction, null, role = Role.Tab, onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) theme.background else theme.secondary,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
