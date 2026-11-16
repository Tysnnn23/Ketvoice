package com.ketvoice.app.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.motion.DrawnCheck
import com.ketvoice.app.ui.motion.Haptics
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.rememberHaptics

/**
 * A check box, as Ket 27 draws it: the round mark of an
 * iOS list — an empty ring, then the app's colour with a check that draws itself, and a tick in
 * the hand when the listener sets it.
 */
@Composable
internal fun KetCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val theme = LocalTheme.current
    val haptic = rememberHaptics()
    val fill by animateColorAsState(if (checked) theme.accent else Color.Transparent, tween(Motion.duration(160)), label = "check-fill")
    val drawn by animateFloatAsState(if (checked) 1f else 0f,
        if (Motion.reduced) snap() else if (checked) tween(220) else snap(), label = "check-mark")
    val swell by animateFloatAsState(if (checked) 1f else .92f,
        if (Motion.reduced) snap() else spring(dampingRatio = .5f, stiffness = 600f), label = "check-swell")
    // Alone, the mark keeps a 48 dp target; inside a row that handles the tap, it is only the mark.
    Box(modifier.then(if (onCheckedChange != null) Modifier.size(48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox,
        onValueChange = { haptic(Haptics.Kind.Tick); onCheckedChange(it) }) else Modifier.size(24.dp)).testTag("ket-checkbox"),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(22.dp).graphicsLayer { scaleX = swell; scaleY = swell; alpha = if (enabled) 1f else .45f }.clip(CircleShape)
            .background(fill).border(1.6.dp, if (checked) theme.accent else theme.secondary.copy(alpha = .7f), CircleShape),
            contentAlignment = Alignment.Center) {
            if (drawn > 0f) DrawnCheck(drawn, Modifier.size(16.dp), tint = onAccent(theme.accent))
        }
    }
}
