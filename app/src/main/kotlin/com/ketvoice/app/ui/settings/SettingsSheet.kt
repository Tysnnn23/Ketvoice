package com.ketvoice.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.theme.DesignTokens
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Text

/** Dialog window owns focus/back; its sheet never samples a backdrop from another window. */
@Composable
internal fun SettingsSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalTheme.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (theme.isDark) .50f else .30f))) {
            val maxSheetHeight = (maxHeight - 32.dp).coerceAtLeast(0.dp)
            Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
            GlassPanel(Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding()
                .padding(horizontal = DesignTokens.Space.small, vertical = DesignTokens.Space.small)
                .widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxSheetHeight),
                RoundedCornerShape(DesignTokens.Radius.sheet), GlassParams.sheet(28f, dark = theme.isDark), captured = true) {
                Column(Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures {} },
                    content = content)
            }
        }
    }
}

@Composable
internal fun SettingsAction(label: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = false,
                            modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    Box(modifier.heightIn(min = DesignTokens.Space.touch).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(label, color = (if (primary) theme.accent else theme.secondary).copy(alpha = if (enabled) 1f else .45f),
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium)
    }
}
