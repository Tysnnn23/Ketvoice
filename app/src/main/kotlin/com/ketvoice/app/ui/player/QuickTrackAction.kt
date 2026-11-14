package com.ketvoice.app.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.glassPressScale
import com.ketvoice.app.ui.common.glassPressSpec
import com.ketvoice.app.ui.common.isLiquid27Glass
import com.ketvoice.app.ui.motion.Motion

/**
 * One of the quick actions at the top of a title's menu: a round glass button with
 * its name underneath. [active] tints it with the accent colour (a favourite, a
 * finished download).
 */
@Composable
internal fun QuickTrackAction(label: String, glyph: ImageVector, modifier: Modifier, active: Boolean = false, action: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val liquid27 = isLiquid27Glass()
    val pressure by animateFloatAsState(if (pressed && !Motion.reduced) 1f else 0f, glassPressSpec(pressed, liquid27), label = "quick-action")
    val accent = com.ketvoice.app.theme.LocalTheme.current.accent
    Column(modifier.clip(RoundedCornerShape(18.dp))
        .clickable(interactions, null, role = Role.Button, onClick = action).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        GlassPanel(Modifier.size(54.dp).graphicsLayer {
            val (width, height) = glassPressScale(pressure, liquid27)
            scaleX = width; scaleY = height
        }, CircleShape, GlassParams.circle(dark = true).copy(opticalPressure = pressure), captured = true) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AnimatedContent(glyph, transitionSpec = { fadeIn(tween(Motion.duration(160))).togetherWith(fadeOut(tween(Motion.duration(120)))) },
                    label = "quick-action-glyph") {
                    Icon(it, null, tint = if (active) accent else Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White.copy(alpha = .9f), fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
    }
}
