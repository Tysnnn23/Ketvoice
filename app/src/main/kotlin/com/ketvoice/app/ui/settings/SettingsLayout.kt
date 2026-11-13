package com.ketvoice.app.ui.settings

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.BuildConfig
import com.ketvoice.app.glass.*
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text

internal val LocalSettingsCategory = staticCompositionLocalOf<SettingsCategory?> { null }
internal val LocalSettingsTopInset = staticCompositionLocalOf { 0.dp }

@Composable
internal fun SettingsLayout(category: SettingsCategory?, onBack: () -> Unit, content: @Composable () -> Unit) {
    val theme = LocalTheme.current
    val background = rememberGlassBackdrop(theme.background)
    val density = androidx.compose.ui.platform.LocalDensity.current
    var headerHeight by remember(category) { mutableIntStateOf(0) }
    val inset = with(density) { headerHeight.toDp() }.coerceAtLeast(
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp)
    Box(Modifier.fillMaxSize().background(theme.background), contentAlignment = Alignment.TopCenter) {
        CompositionLocalProvider(LocalSettingsCategory provides category, LocalSettingsTopInset provides inset) {
            BackdropCapture(background, Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                content()
            }
            CompositionLocalProvider(LocalBackdropLayer provides background) {
                Row(Modifier.widthIn(max = 840.dp).fillMaxWidth()
                    .onSizeChanged { headerHeight = it.height }
                    .statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    com.ketvoice.app.ui.common.LiquidGlassButton(onBack, Modifier.size(48.dp)) {
                        Icon(KetIcons.ArrowLeft, "Retour", tint = theme.onBackground, modifier = Modifier.size(22.dp))
                    }
                    GlassPanel(Modifier.weight(1f).padding(start = 12.dp), RoundedCornerShape(24.dp),
                        GlassParams.pill(dark = theme.isDark)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) {
                            Text(category?.title ?: "Réglages", color = theme.onBackground,
                                fontSize = if (category == null) 29.sp else 25.sp, fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-.65).sp, lineHeight = 32.sp)
                            if (category != null) Text(category.supportingText(), color = theme.secondary,
                                fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsHome(connected: Boolean, name: String, onCategory: (SettingsCategory) -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxSize().testTag("settings-home").verticalScroll(rememberScrollState())
        .navigationBarsPadding().padding(horizontal = 20.dp).padding(top = LocalSettingsTopInset.current + 8.dp, bottom = 32.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(theme.card)
            .border(.5.dp, theme.onBackground.copy(alpha = .055f), RoundedCornerShape(22.dp))
            .clickable(role = Role.Button) { onCategory(SettingsCategory.Account) }
            .heightIn(min = 104.dp).padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(KetIcons.Account, null, tint = theme.onBackground.copy(alpha = .8f), modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f).padding(horizontal = 18.dp)) {
                Text(if (connected) name.ifBlank { "YouTube Music" } else "Compte YouTube Music", localize = !connected,
                    color = theme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
                Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (connected) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(theme.accent))
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(if (connected) "Connecté" else "Se connecter", color = theme.secondary,
                        fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            Icon(KetIcons.ChevronRight, null, tint = theme.secondary, modifier = Modifier.size(18.dp))
        }
        SettingsGroupHeading("Apparence et lecture")
        SettingsCategoryGroup(listOf(SettingsCategory.Appearance, SettingsCategory.Playback, SettingsCategory.Lyrics, SettingsCategory.Canvas), onCategory)
        SettingsGroupHeading("Votre musique")
        SettingsCategoryGroup(listOf(SettingsCategory.Sources, SettingsCategory.Downloads, SettingsCategory.LocalMusic, SettingsCategory.Storage), onCategory)
        SettingsGroupHeading("Ketvoice")
        SettingsCategoryGroup(listOf(SettingsCategory.About), onCategory)
        Text("KETVOICE  ${BuildConfig.VERSION_NAME}", localize = false, color = theme.secondary,
            fontSize = 11.sp, letterSpacing = 1.3.sp, modifier = Modifier.padding(start = 4.dp, top = 24.dp))
    }
}

@Composable
internal fun SettingsGroupHeading(title: String, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    Text(title, color = theme.secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        lineHeight = 18.sp, modifier = modifier.padding(start = 4.dp, end = 4.dp, top = 24.dp, bottom = 10.dp)
            .semantics { heading() })
}

@Composable
private fun SettingsCategoryGroup(categories: List<SettingsCategory>, onCategory: (SettingsCategory) -> Unit) {
    val theme = LocalTheme.current
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(theme.card)
        .border(.5.dp, theme.onBackground.copy(alpha = .055f), shape)) {
        categories.forEachIndexed { index, category ->
            Row(Modifier.fillMaxWidth().testTag("settings-category-${category.name}")
                .clickable(role = Role.Button) { onCategory(category) }.heightIn(min = 80.dp)
                .padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(category.icon(), null, tint = theme.onBackground.copy(alpha = .78f), modifier = Modifier.size(23.dp))
                Column(Modifier.weight(1f).padding(start = 18.dp, end = 14.dp)) {
                    Text(category.title, color = theme.onBackground, fontSize = 16.sp,
                        fontWeight = FontWeight.Medium, lineHeight = 22.sp)
                    Text(category.supportingText(), color = theme.secondary, fontSize = 13.sp,
                        lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
                }
                Icon(KetIcons.ChevronRight, null, tint = theme.secondary.copy(alpha = .8f), modifier = Modifier.size(18.dp))
            }
            if (index != categories.lastIndex) SettingsCellDivider()
        }
    }
}

@Composable
internal fun SettingsCellDivider() {
    val theme = LocalTheme.current
    Box(Modifier.padding(start = 61.dp, end = 20.dp).fillMaxWidth().height(.5.dp)
        .background(theme.onBackground.copy(alpha = .065f)))
}

private fun SettingsCategory.supportingText(): String = when (this) {
    SettingsCategory.Account -> "Comptes, synchronisation et publications"
    SettingsCategory.Appearance -> "Thème, verre et personnalisation"
    SettingsCategory.Design -> "Chaque finition peut être retirée séparément"
    SettingsCategory.Playback -> "Qualité audio, égaliseur et sortie"
    SettingsCategory.Lyrics -> "Sources, karaoké et traduction"
    SettingsCategory.Canvas -> "Vidéo et fonds réactifs à la musique"
    SettingsCategory.Sources -> "Services intégrés et sources personnalisées"
    SettingsCategory.Downloads -> "Qualité et musique hors ligne"
    SettingsCategory.LocalMusic -> "Dossiers et fichiers de l'appareil"
    SettingsCategory.Storage -> "Cache et espace utilisé"
    SettingsCategory.About -> "Version et mises à jour"
}

private fun SettingsCategory.icon(): ImageVector = when (this) {
    SettingsCategory.Account -> KetIcons.Account
    SettingsCategory.Appearance -> KetIcons.Appearance
    SettingsCategory.Design -> KetIcons.Sliders
    SettingsCategory.Playback -> KetIcons.Audio
    SettingsCategory.Lyrics -> KetIcons.Lyrics
    SettingsCategory.Canvas -> KetIcons.Canvas
    SettingsCategory.Sources -> KetIcons.Link
    SettingsCategory.Downloads -> KetIcons.Download
    SettingsCategory.LocalMusic -> KetIcons.Folder
    SettingsCategory.Storage -> KetIcons.Storage
    SettingsCategory.About -> KetIcons.Info
}
