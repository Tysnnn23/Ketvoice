package com.ketvoice.app.ui.settings

import com.ketvoice.app.theme.pageBackground
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.BuildConfig
import com.ketvoice.app.glass.*
import com.ketvoice.app.theme.Ket27
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
    Box(Modifier.fillMaxSize().pageBackground(theme), contentAlignment = Alignment.TopCenter) {
        CompositionLocalProvider(LocalSettingsCategory provides category, LocalSettingsTopInset provides inset) {
            BackdropCapture(background, Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                content()
            }
            Ket27SettingsBar(category, onBack, Modifier.widthIn(max = 840.dp).fillMaxWidth().onSizeChanged { headerHeight = it.height })
        }
    }
}

/**
 * Ket 27: the bar of an iOS 27 page once content scrolls under it — one uniform, nearly solid
 * bar, a glass back button and the title of the page in its middle. The root page keeps its
 * title large, in the content.
 */
@Composable
private fun Ket27SettingsBar(category: SettingsCategory?, onBack: () -> Unit, modifier: Modifier) {
    val theme = LocalTheme.current
    Column(modifier.background(theme.background.copy(alpha = .94f)).testTag("ket27-settings-bar")) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().height(54.dp).padding(horizontal = Ket27.Metrics.margin)) {
            com.ketvoice.app.ui.common.LiquidGlassButton(onBack, Modifier.align(Alignment.CenterStart).size(44.dp)) {
                Icon(KetIcons.ChevronRight, "Retour", tint = theme.onBackground,
                    modifier = Modifier.size(20.dp).graphicsLayer { scaleX = -1f })
            }
            if (category != null) Text(category.title, color = theme.onBackground, style = Ket27.Type.headline, maxLines = 1,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp).semantics { heading() })
        }
        Box(Modifier.fillMaxWidth().height(.5.dp).background(theme.hairline))
    }
}

/**
 * A row that answers the finger the way a list row does on iOS: it darkens at once under the
 * touch and lets go slowly, instead of giving no sign at all.
 */
@Composable
internal fun Modifier.rowPress(role: Role = Role.Button, onClick: () -> Unit): Modifier {
    // The same highlight as the rows of titles (drawn without recomposing, at once under reduced motion).
    val ink = LocalTheme.current.onBackground
    val highlight = remember(ink) { com.ketvoice.app.ui.common.RowHighlight(ink) }
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    return clickable(source, highlight, role = role, onClick = onClick)
}

/** A symbol on its coloured tile, as in the Settings of iOS. */
@Composable
internal fun Ket27Tile(icon: ImageVector, index: Int) {
    Box(Modifier.size(Ket27.Metrics.tile).clip(RoundedCornerShape(Ket27.Metrics.tileRadius)).background(Ket27.tile(index)),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp))
    }
}

/** The shape and colour of a group of rows: iOS's inset grouped list (Ket 27). */
@Composable
internal fun Modifier.settingsGroup(): Modifier {
    val theme = LocalTheme.current
    return clip(RoundedCornerShape(Ket27.Metrics.groupRadius)).background(theme.card)
}

@Composable
internal fun SettingsHome(connected: Boolean, name: String, onCategory: (SettingsCategory) -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxSize().testTag("settings-home").verticalScroll(rememberScrollState())
        .navigationBarsPadding().padding(horizontal = Ket27.Metrics.margin).padding(top = LocalSettingsTopInset.current + 8.dp, bottom = 32.dp)) {
        Text("Réglages", color = theme.onBackground, style = Ket27.Type.largeTitle,
            modifier = Modifier.padding(start = 4.dp, bottom = 14.dp).semantics { heading() }.testTag("ket27-large-title"))
        Row(Modifier.fillMaxWidth().settingsGroup()
            .clickable(role = Role.Button) { onCategory(SettingsCategory.Account) }
            .heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 12.dp),
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
    // iOS 27 writes its section headers in sentence case, larger and heavier.
    Text(ket27Heading(com.ketvoice.app.data.AppText.t(title)), localize = false, color = theme.onBackground,
        style = Ket27.Type.headline, modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 9.dp).semantics { heading() })
}

@Composable
private fun SettingsCategoryGroup(categories: List<SettingsCategory>, onCategory: (SettingsCategory) -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxWidth().settingsGroup()) {
        categories.forEachIndexed { index, category ->
            Row(Modifier.fillMaxWidth().testTag("settings-category-${category.name}")
                .rowPress { onCategory(category) }.heightIn(min = 60.dp)
                .padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Ket27Tile(category.icon(), category.ordinal)
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 14.dp)) {
                    Text(category.title, color = theme.onBackground, fontSize = 17.sp,
                        fontWeight = FontWeight.Normal, lineHeight = 22.sp)
                    Text(category.supportingText(), color = theme.secondary, fontSize = 13.sp,
                        lineHeight = 18.sp, modifier = Modifier.padding(top = 1.dp))
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
    // iOS aligns the separator with the text of the row and runs it to the edge of the group.
    Box(Modifier.padding(start = 58.dp).fillMaxWidth().height(.5.dp).background(theme.hairline))
}

/** "APPARENCE" as iOS 27 writes a header: "Apparence". Names written in capitals on purpose keep them. */
internal fun ket27Heading(title: String): String {
    if (title.isEmpty() || title.any { it.isLowerCase() }) return title
    return title.split(' ').joinToString(" ") { word ->
        NAMES[word] ?: word.lowercase()
    }.replaceFirstChar { it.titlecase() }
}
private val NAMES = mapOf("API" to "API", "KETVOICE" to "Ketvoice", "KETMIX" to "Ketmix", "KET" to "Ket", "QUEST" to "Quest",
    "DISCORD" to "Discord", "STUDIO" to "Studio", "YOUTUBE" to "YouTube", "MUSIC" to "Music", "CANVAS" to "Canvas")

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
