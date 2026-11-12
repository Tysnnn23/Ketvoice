package com.ketvoice.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.glass.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text

/** The dialog captures its own independent artwork scene, never the Activity or sheet itself. */
@Composable
internal fun SettingsSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalTheme.current
    val player by Player.state.collectAsState()
    val covers by ArtworkRepo.covers.collectAsState()
    val cover = player.current?.let { covers["t:${it.id}"] ?: it.coverUrl }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val backdrop = rememberGlassBackdrop(theme.background)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            BackdropCapture(backdrop, Modifier.matchParentSize()) {
                Box(Modifier.fillMaxSize().background(theme.background)) {
                    if (cover != null) AsyncImage(cover, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().alpha(if (theme.isDark) .28f else .18f))
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = if (theme.isDark) .32f else .10f)))
                }
            }
            Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
            val compactLandscape = maxWidth > 640.dp && maxHeight < 600.dp
            val alignment = if (compactLandscape) Alignment.Center else Alignment.BottomCenter
            BoxWithConstraints(Modifier.align(alignment).widthIn(max = 560.dp).fillMaxWidth()
                .statusBarsPadding().imePadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp)) {
                CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
                    GlassPanel(Modifier.fillMaxWidth().heightIn(max = maxHeight), RoundedCornerShape(28.dp),
                        GlassParams.sheet(28f, dark = theme.isDark)) {
                        Column(Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures {} }) {
                            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp)
                                .width(32.dp).height(4.dp).clip(CircleShape)
                                .background(theme.secondary.copy(alpha = .3f)))
                            content()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsSheetHeader(title: String, subtitle: String = "", onClose: () -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(top = 5.dp, end = 8.dp)) {
            Text(title, color = theme.onBackground, fontSize = 22.sp, lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (-.4).sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = theme.secondary, fontSize = 13.sp,
                lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClose),
            contentAlignment = Alignment.Center) {
            Icon(KetIcons.Close, "Fermer", tint = theme.secondary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun SettingsAction(label: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = false,
                            modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val shape = RoundedCornerShape(14.dp)
    val alpha = if (enabled) 1f else .45f
    Box(modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(shape)
        .background(if (primary) theme.onBackground.copy(alpha = .08f * alpha) else Color.Transparent)
        .then(if (primary) Modifier.border(.5.dp, theme.onBackground.copy(alpha = .12f * alpha), shape) else Modifier)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = (if (primary) theme.onBackground else theme.secondary).copy(alpha = alpha),
            fontSize = 14.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium)
    }
}
