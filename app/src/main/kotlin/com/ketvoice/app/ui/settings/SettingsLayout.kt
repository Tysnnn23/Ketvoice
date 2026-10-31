package com.ketvoice.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.BuildConfig
import com.ketvoice.app.glass.*
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.GlassBackButton
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text

internal val LocalSettingsCategory = staticCompositionLocalOf<SettingsCategory?> { null }

@Composable
internal fun SettingsLayout(category: SettingsCategory?, onBack: () -> Unit, content: @Composable () -> Unit) {
    val theme = LocalTheme.current
    val background = rememberGlassBackdrop(theme.background)
    Box(Modifier.fillMaxSize().background(theme.background)) {
        // Independent background ONLY. No setting/control can sample its own draw layer.
        BackdropCapture(background, Modifier.matchParentSize()) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(theme.background)
                drawRect(Brush.radialGradient(listOf(theme.accent.copy(alpha = .08f), Color.Transparent),
                    center = Offset(size.width, 0f), radius = size.width * 1.2f))
            }
        }
        CompositionLocalProvider(LocalBackdropLayer provides background, LocalSettingsCategory provides category) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    GlassBackButton(onBack)
                    Text(category?.title ?: "Réglages", color = theme.onBackground,
                        fontSize = if (category == null) 30.sp else 23.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = (-.6).sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 16.dp))
                }
                content()
            }
        }
    }
}

@Composable
internal fun SettingsHome(connected: Boolean, name: String, onCategory: (SettingsCategory) -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxSize().testTag("settings-home").verticalScroll(rememberScrollState())
        .navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 32.dp)) {
        // Matte account summary: no full-page frosted cards or wallpaper behind small labels.
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(theme.card)
            .clickable(role = Role.Button) { onCategory(SettingsCategory.Account) }.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(theme.accent.copy(alpha = .13f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Person, null, tint = theme.accent, modifier = Modifier.size(25.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(if (connected) name.ifBlank { "YouTube Music" } else "Compte YouTube Music", localize = !connected,
                    color = theme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (connected) "Connecté" else "Se connecter", color = theme.secondary,
                    fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = theme.secondary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(24.dp))
        SettingsCategoryGroup(listOf(SettingsCategory.Appearance, SettingsCategory.Playback, SettingsCategory.Lyrics, SettingsCategory.Canvas), onCategory)
        Spacer(Modifier.height(18.dp))
        SettingsCategoryGroup(listOf(SettingsCategory.Sources, SettingsCategory.Downloads, SettingsCategory.LocalMusic, SettingsCategory.Storage), onCategory)
        Spacer(Modifier.height(18.dp))
        SettingsCategoryGroup(listOf(SettingsCategory.About), onCategory)
        Text("KETVOICE  ${BuildConfig.VERSION_NAME}", localize = false, color = theme.secondary,
            fontSize = 11.sp, letterSpacing = 1.4.sp, modifier = Modifier.padding(start = 6.dp, top = 24.dp))
    }
}

@Composable
private fun SettingsCategoryGroup(categories: List<SettingsCategory>, onCategory: (SettingsCategory) -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(theme.card)) {
        categories.forEachIndexed { index, category ->
            Row(Modifier.fillMaxWidth().testTag("settings-category-${category.name}")
                .clickable(role = Role.Button) { onCategory(category) }.heightIn(min = 70.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(category.icon(), null, tint = theme.accent, modifier = Modifier.size(23.dp))
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(category.title, color = theme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    val summary = when (category) {
                        SettingsCategory.Appearance -> "Thème"
                        SettingsCategory.Playback -> "Égaliseur"
                        SettingsCategory.Lyrics -> "Source des paroles"
                        SettingsCategory.Canvas -> "Activer les Canvas vidéo"
                        SettingsCategory.Sources -> "Sources personnalisées"
                        SettingsCategory.Downloads -> "Qualité de téléchargement"
                        SettingsCategory.LocalMusic -> "Dossier de musique locale"
                        SettingsCategory.Storage -> "Cache des chansons utilisé"
                        else -> "Version"
                    }
                    Text(summary, color = theme.secondary, fontSize = 12.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = theme.secondary, modifier = Modifier.size(20.dp))
            }
            if (index != categories.lastIndex) Box(Modifier.padding(start = 55.dp, end = 16.dp)
                .fillMaxWidth().height(.5.dp).background(theme.onBackground.copy(alpha = .07f)))
        }
    }
}

private fun SettingsCategory.icon(): ImageVector = when (this) {
    SettingsCategory.Account -> Icons.Filled.Person
    SettingsCategory.Appearance -> Icons.Filled.ColorLens
    SettingsCategory.Design -> Icons.Filled.Tune
    SettingsCategory.Playback -> Icons.Filled.Tune
    SettingsCategory.Lyrics -> Icons.Filled.Subtitles
    SettingsCategory.Canvas -> Icons.Filled.Videocam
    SettingsCategory.Sources -> Icons.Filled.Wifi
    SettingsCategory.Downloads -> Icons.Filled.CloudDownload
    SettingsCategory.LocalMusic -> Icons.Filled.Folder
    SettingsCategory.Storage -> Icons.Filled.Storage
    SettingsCategory.About -> Icons.Filled.Info
}
